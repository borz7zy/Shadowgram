/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.repositories

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.github.borz7zy.shadowgram.BuildConfig
import com.github.borz7zy.shadowgram.models.Result
import com.github.borz7zy.shadowgram.models.Result.Companion.getOrNull
import com.github.borz7zy.shadowgram.telegram.ChatsStore
import com.github.borz7zy.shadowgram.telegram.DatabaseKey
import com.github.borz7zy.shadowgram.telegram.TelegramClient
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.Locale

/**
 * Telegram accounts and their TDLib clients.
 *
 * An account is a slot with its own TDLib database. It becomes a signed in account, and the
 * active one, the first time its client gets authorized.
 */
class AccountsRepository(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val onAccountsChanged: () -> Unit,
    private val onNotificationUpdate: (Int, TdApi.Object, ChatsStore) -> Unit,
) {
    private val sharedPreferences by lazy {
        context.getSharedPreferences("accounts", Context.MODE_PRIVATE)
    }

    /**
     * Lazy TDLib initializer: the native library is loaded off the main thread the first time
     * a client is requested, never at app startup.
     */
    private val tdlib = coroutineScope.async(Dispatchers.IO, CoroutineStart.LAZY) {
        // The first use of Client loads libtdjni.so
        // Verbose TDLib logs include authorization payloads such as QR login tokens.
        Client.execute(TdApi.SetLogVerbosityLevel(1))
    }

    private val databaseKey by lazy { DatabaseKey.get(sharedPreferences) }

    private val clientsMutex = Mutex()
    private val clients = mutableMapOf<Int, TelegramClient>()

    private val accountIds: Set<Int>
        get() = sharedPreferences.getStringSet(ACCOUNT_IDS_KEY, null).orEmpty()
            .map(String::toInt)
            .toSet()

    private val activeAccountId: Int?
        get() = sharedPreferences.getInt(ACTIVE_ACCOUNT_ID_KEY, -1).takeIf { it in accountIds }

    /**
     * The slot used to sign in a new account.
     */
    private val newAccountId
        get() = (accountIds.maxOrNull() ?: -1) + 1

    /**
     * Get the client of the active account, or the one of the account to sign in when there is
     * none. Loads TDLib when needed.
     */
    suspend fun activeClient() = client { activeAccountId ?: newAccountId }

    /**
     * Get the client of the account to sign in. Loads TDLib when needed.
     */
    suspend fun newAccountClient() = client { newAccountId }

    data class SavedAccount(val id: Int, val isActive: Boolean, val user: TdApi.User?)

    suspend fun savedAccounts(): List<SavedAccount> = withContext(Dispatchers.IO) {
        val (ids, activeId) = clientsMutex.withLock { accountIds.sorted() to activeAccountId }
        ids.map { id ->
            async {
                val user = withTimeoutOrNull(3_000) {
                    val client = signedInClient(id) ?: return@withTimeoutOrNull null
                    if (client.isAuthorized()) client.send(TdApi.GetMe()).getOrNull() else null
                }
                SavedAccount(id, id == activeId, user)
            }
        }.awaitAll()
    }

    internal suspend fun signedInClients(receiverId: Long = 0): Map<Int, TelegramClient> =
        withContext(Dispatchers.IO) {
            tdlib.await()
            clientsMutex.withLock {
                val ids = accountIds
                val matched = ids.filter { sharedPreferences.getLong(receiverKey(it), 0) == receiverId }
                // An unknown receiver may be from a registration whose result didn't reach disk.
                // TDLib checks the receiver and encryption key again before processing each push.
                val selected = if (receiverId == 0L || matched.isEmpty()) ids else matched
                selected.associateWith(::getOrCreateClient)
            }
        }

    internal suspend fun signedInClient(accountId: Int): TelegramClient? = withContext(Dispatchers.IO) {
        tdlib.await()
        clientsMutex.withLock { accountId.takeIf { it in accountIds }?.let(::getOrCreateClient) }
    }

    internal suspend fun savePushReceiver(accountId: Int, client: TelegramClient, receiverId: Long) =
        withContext(Dispatchers.IO) {
            clientsMutex.withLock {
                if (clients[accountId] === client && client.authorizationState.value is TdApi.AuthorizationStateReady) {
                    sharedPreferences.edit(commit = true) { putLong(receiverKey(accountId), receiverId) }
                }
            }
        }

    /** Returns null for an unavailable account and true if the UI must reopen for another account. */
    internal suspend fun activateAccount(
        accountId: Int,
        authorizationTimeoutMillis: Long = Long.MAX_VALUE,
    ): Boolean? {
        val client = withTimeoutOrNull(authorizationTimeoutMillis) {
            signedInClient(accountId)?.takeIf { it.isAuthorized() }
        } ?: return null

        return clientsMutex.withLock {
            if (clients[accountId] !== client || accountId !in accountIds ||
                client.authorizationState.value !is TdApi.AuthorizationStateReady
            ) {
                return@withLock null
            }
            val changed = activeAccountId != accountId
            if (changed) sharedPreferences.edit { putInt(ACTIVE_ACCOUNT_ID_KEY, accountId) }
            changed
        }
    }

    suspend fun signedInUserIds(): LongArray = withContext(Dispatchers.IO) {
        val ids = clientsMutex.withLock { accountIds }
        ids.mapNotNull { accountId ->
            val accountClient = client { accountId }
            val state = accountClient.authorizationState.first {
                it != null && it !is TdApi.AuthorizationStateWaitTdlibParameters
            }
            if (state !is TdApi.AuthorizationStateReady) return@mapNotNull null

            val result = accountClient.send(TdApi.GetOption("my_id"))
            (result.getOrNull() as? TdApi.OptionValueInteger)?.value?.takeIf { it > 0 }
        }.toLongArray()
    }

    /**
     * TDLib requires a fresh authorization state to go from QR confirmation back to phone login.
     * Only the client of the pending account is eligible for reset.
     */
    suspend fun cancelQrSignIn(pendingClient: TelegramClient): Result<TelegramClient, TdApi.Error> =
        withContext(Dispatchers.IO + NonCancellable) {
            clientsMutex.withLock {
                val accountId = newAccountId
                if (clients[accountId] !== pendingClient ||
                    pendingClient.authorizationState.value !is TdApi.AuthorizationStateWaitOtherDeviceConfirmation
                ) {
                    return@withLock Result.Success(pendingClient)
                }

                when (val result = pendingClient.send(TdApi.LogOut())) {
                    is Result.Failure -> Result.Failure(result.error)
                    is Result.Success -> {
                        pendingClient.authorizationState.first { it is TdApi.AuthorizationStateClosed }
                        clients.remove(accountId)
                        Result.Success(getOrCreateClient(accountId))
                    }
                }
            }
        }

    private suspend fun client(accountId: () -> Int): TelegramClient = withContext(Dispatchers.IO) {
        tdlib.await()
        clientsMutex.withLock { getOrCreateClient(accountId()) }
    }

    private fun getOrCreateClient(accountId: Int): TelegramClient = clients.getOrPut(accountId) {
        val parameters = buildParameters(accountId)
        val isEncrypted = parameters.databaseEncryptionKey.isNotEmpty()
        val fallbackKey = if (isEncrypted) ByteArray(0) else databaseKey
        TelegramClient(parameters, context, fallbackKey) { update, chats ->
            onNotificationUpdate(accountId, update, chats)
        }.also { client ->
            if (!isEncrypted) coroutineScope.launch(Dispatchers.IO) { encrypt(accountId, client) }
            coroutineScope.launch(Dispatchers.IO) {
                val state = client.authorizationState.first {
                    it is TdApi.AuthorizationStateReady || it is TdApi.AuthorizationStateClosed
                }

                if (state is TdApi.AuthorizationStateReady) {
                    val added = clientsMutex.withLock {
                        if (clients[accountId] === client && accountId !in accountIds) {
                            val ids = accountIds + accountId
                            sharedPreferences.edit(commit = true) {
                                putStringSet(ACCOUNT_IDS_KEY, ids.map(Int::toString).toSet())
                                putInt(ACTIVE_ACCOUNT_ID_KEY, accountId)
                            }
                            true
                        } else {
                            false
                        }
                    }
                    if (added) onAccountsChanged()
                }
            }
        }
    }

    /**
     * Encrypt a database made before the app had a key, once TDLib has opened it. Already
     * encrypted (the flag lost to a crash) works as well: the key is the same.
     */
    private suspend fun encrypt(accountId: Int, client: TelegramClient) {
        client.authorizationState.first { it != null && it !is TdApi.AuthorizationStateWaitTdlibParameters }
        if (client.initError.value != null) return
        when (val result = client.send(TdApi.SetDatabaseEncryptionKey(databaseKey))) {
            is Result.Success -> sharedPreferences.edit(commit = true) {
                putBoolean(encryptedKey(accountId), true)
            }
            is Result.Failure -> Log.e(LOG_TAG, "SetDatabaseEncryptionKey failed: ${result.error.message}")
        }
    }

    private fun encryptedKey(accountId: Int) = "db_encrypted_$accountId"
    private fun receiverKey(accountId: Int) = "push_receiver_$accountId"

    private fun buildParameters(accountId: Int) = TdApi.SetTdlibParameters().apply {
        val accountDirectory = File(context.filesDir, "accounts/$accountId")

        // A new database starts encrypted, an old one is encrypted by [encrypt] once open
        if (!accountDirectory.exists()) {
            sharedPreferences.edit(commit = true) { putBoolean(encryptedKey(accountId), true) }
        }
        databaseEncryptionKey = when (sharedPreferences.getBoolean(encryptedKey(accountId), false)) {
            true -> databaseKey
            false -> ByteArray(0)
        }
        databaseDirectory = accountDirectory.absolutePath
        filesDirectory = File(accountDirectory, "files").absolutePath
        useFileDatabase = true
        useChatInfoDatabase = true
        useMessageDatabase = true
        useSecretChats = true
        apiId = BuildConfig.TELEGRAM_API_ID
        apiHash = BuildConfig.TELEGRAM_API_HASH
        systemLanguageCode = Locale.getDefault().language
        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
        systemVersion = "Android ${Build.VERSION.RELEASE}"
        applicationVersion = BuildConfig.VERSION_NAME
//        useTestDc = BuildConfig.TELEGRAM_TEST_DC == 1
    }

    companion object {
        private const val LOG_TAG = "AccountsRepository"
        private const val ACCOUNT_IDS_KEY = "account_ids"
        private const val ACTIVE_ACCOUNT_ID_KEY = "active_account_id"
    }
}
