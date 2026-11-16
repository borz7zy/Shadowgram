/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.repositories

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.github.borz7zy.shadowgram.BuildConfig
import com.github.borz7zy.shadowgram.models.Result
import com.github.borz7zy.shadowgram.models.Result.Companion.getOrNull
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
) {
    private val sharedPreferences = context.getSharedPreferences(
        "accounts", Context.MODE_PRIVATE
    )

    /**
     * Lazy TDLib initializer: the native library is loaded off the main thread the first time
     * a client is requested, never at app startup.
     */
    private val tdlib = coroutineScope.async(Dispatchers.IO, CoroutineStart.LAZY) {
        // The first use of Client loads libtdjni.so
        // Verbose TDLib logs include authorization payloads such as QR login tokens.
        Client.execute(TdApi.SetLogVerbosityLevel(1))
    }

    private val clientsMutex = Mutex()
    private val clients = mutableMapOf<Int, TelegramClient>()

    private var accountIds: Set<Int>
        get() = sharedPreferences.getStringSet(ACCOUNT_IDS_KEY, null).orEmpty()
            .map(String::toInt)
            .toSet()
        set(value) = sharedPreferences.edit {
            putStringSet(ACCOUNT_IDS_KEY, value.map(Int::toString).toSet())
        }

    private var activeAccountId: Int?
        get() = sharedPreferences.getInt(ACTIVE_ACCOUNT_ID_KEY, -1).takeIf { it in accountIds }
        set(value) = sharedPreferences.edit {
            value?.also {
                putInt(ACTIVE_ACCOUNT_ID_KEY, it)
            } ?: remove(ACTIVE_ACCOUNT_ID_KEY)
        }

    /**
     * The slot used to sign in a new account.
     */
    private val newAccountId
        get() = (accountIds.maxOrNull() ?: -1) + 1

    /**
     * Get the client of the active account, or the one of the account to sign in when there is
     * none. Loads TDLib when needed.
     */
    suspend fun activeClient() = client(activeAccountId ?: newAccountId)

    /**
     * Get the client of the account to sign in. Loads TDLib when needed.
     */
    suspend fun newAccountClient() = client(newAccountId)

    suspend fun signedInUserIds(): LongArray = accountIds.mapNotNull { accountId ->
        val accountClient = client(accountId)
        val state = accountClient.authorizationState.first {
            it != null && it !is TdApi.AuthorizationStateWaitTdlibParameters
        }
        if (state !is TdApi.AuthorizationStateReady) return@mapNotNull null

        val result = accountClient.send(TdApi.GetOption("my_id"))
        (result.getOrNull() as? TdApi.OptionValueInteger)?.value
            ?.takeIf { it > 0 }
    }.toLongArray()

    /**
     * TDLib requires a fresh authorization state to go from QR confirmation back to phone login.
     * Only the client of the pending account is eligible for reset.
     */
    suspend fun cancelQrSignIn(pendingClient: TelegramClient): Result<TelegramClient, TdApi.Error> =
        withContext(NonCancellable) {
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

    private suspend fun client(accountId: Int): TelegramClient {
        tdlib.await()

        return clientsMutex.withLock { getOrCreateClient(accountId) }
    }

    private fun getOrCreateClient(accountId: Int): TelegramClient = clients.getOrPut(accountId) {
        TelegramClient(buildParameters(accountId), context).also { client ->
            coroutineScope.launch {
                val state = client.authorizationState.first {
                    it is TdApi.AuthorizationStateReady || it is TdApi.AuthorizationStateClosed
                }

                if (state is TdApi.AuthorizationStateReady) {
                    clientsMutex.withLock {
                        if (clients[accountId] === client && accountId !in accountIds) {
                            accountIds += accountId
                            activeAccountId = accountId
                        }
                    }
                }
            }
        }
    }

    private fun buildParameters(accountId: Int) = TdApi.SetTdlibParameters().apply {
        val accountDirectory = File(context.filesDir, "accounts/$accountId")

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
        private const val ACCOUNT_IDS_KEY = "account_ids"
        private const val ACTIVE_ACCOUNT_ID_KEY = "active_account_id"
    }
}
