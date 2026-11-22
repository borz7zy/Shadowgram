/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.telegram

import android.content.Context
import android.util.Log
import com.github.borz7zy.shadowgram.notifications.logNotificationDelivery
import com.github.borz7zy.shadowgram.videonote.VideoNoteGenerator
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import com.github.borz7zy.shadowgram.models.Result
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import kotlin.coroutines.resume

/**
 * A TDLib client of a single account.
 *
 * @param parameters The TDLib parameters, sent as soon as TDLib asks for them
 * @param context For the work TDLib asks the app to do, transcoding video notes
 * @param fallbackKey The database key tried once when [parameters]' one is refused: the database
 *   may be encrypted already, or not yet, if the app died in between
 */
class TelegramClient(
    private val parameters: TdApi.SetTdlibParameters,
    context: Context,
    private var fallbackKey: ByteArray? = null,
    private val onCallUpdate: (TdApi.Object, TelegramClient) -> Unit = { _, _ -> },
    private val onNotificationUpdate: (TdApi.Object, TelegramClient) -> Unit = { _, _ -> },
) {
    private val _initError = MutableStateFlow<TdApi.Error?>(null)

    /** Why TDLib refused the parameters, null while it hasn't. Nothing else happens after it. */
    val initError = _initError.asStateFlow()

    private val _authorizationState = MutableStateFlow<TdApi.AuthorizationState?>(null)

    /**
     * The current authorization state, null until TDLib reports the first one.
     */
    val authorizationState = _authorizationState.asStateFlow()

    private val notificationOptionsReady = CompletableDeferred<Boolean>()
    private val connectionState = MutableStateFlow<TdApi.ConnectionState?>(null)
    private val havePendingNotifications = MutableStateFlow(false)
    private val _reactionNotificationSettings = MutableStateFlow<TdApi.ReactionNotificationSettings?>(null)
    internal val reactionNotificationSettings = _reactionNotificationSettings.asStateFlow()
    private val communityNames = ConcurrentHashMap<Long, String>()

    internal fun communityName(id: Long): String? = communityNames[id]

    internal suspend fun isAuthorized(): Boolean =
        combine(authorizationState, initError, ::Pair).first { (state, error) ->
            error != null || state != null && state !is TdApi.AuthorizationStateWaitTdlibParameters
        }.first is TdApi.AuthorizationStateReady

    internal suspend fun awaitNotificationSetup() = notificationOptionsReady.await()

    internal suspend fun awaitNotificationSync() {
        combine(connectionState, havePendingNotifications) { connection, pending ->
            connection is TdApi.ConnectionStateReady && !pending
        }.first { it }
    }

    internal fun setOnline(online: Boolean) {
        send(TdApi.SetOption("online", TdApi.OptionValueBoolean(online))) { result ->
            logNotificationDelivery { "TDLib presence online=$online accepted=${result is TdApi.Ok}" }
        }
    }

    /**
     * The chats of this account. Created with the client so that no update is missed.
     */
    val chats = ChatsStore { function, handler -> client.send(function, handler) }

    /**
     * The contacts of this account. Created with the client so that no update is missed.
     */
    val contacts = ContactsStore { function, handler -> client.send(function, handler) }

    /**
     * Stickers and GIFs for the sticker keyboard, loaded the first time it opens.
     */
    val stickers = StickersStore { function, handler -> client.send(function, handler) }

    /**
     * Transcodes the video notes of this account as TDLib asks for them, even with no chat open.
     */
    val videoNotes = VideoNoteGenerator(context.applicationContext, this)

    private val updateListeners = CopyOnWriteArrayList<(TdApi.Object) -> Unit>()

    // Must be declared after the flows and stores, updates start coming as soon as the client is created
    private val client: Client = Client.create(::onUpdate, null, null)
    private val networkMonitor = TelegramNetworkMonitor(context.applicationContext) { client.send(it, {}) }

    /**
     * Send a request to TDLib and wait for its result.
     */
    suspend fun <T : TdApi.Object> send(
        function: TdApi.Function<T>
    ): Result<T, TdApi.Error> = suspendCancellableCoroutine { continuation ->
        client.send(function) { result ->
            continuation.resume(
                when (result) {
                    is TdApi.Error -> Result.Failure(result)
                    else -> @Suppress("UNCHECKED_CAST") Result.Success(result as T)
                }
            )
        }
    }

    /**
     * Also get every update, on the TDLib thread, in order. Used by short-lived views of the
     * account (an open chat), long-lived ones get theirs from [onUpdate] directly.
     */
    fun addUpdateListener(listener: (TdApi.Object) -> Unit) = updateListeners.add(listener)

    fun removeUpdateListener(listener: (TdApi.Object) -> Unit) = updateListeners.remove(listener)

    /**
     * Send a request without waiting, [handler] is called on the TDLib thread.
     */
    fun send(function: TdApi.Function<*>, handler: (TdApi.Object) -> Unit) =
        client.send(function, handler)

    // Called on the TDLib thread
    private fun onUpdate(update: TdApi.Object) {
        chats.onUpdate(update)
        contacts.onUpdate(update)
        videoNotes.onUpdate(update)
        stickers.onUpdate(update)
        when (update) {
            is TdApi.UpdateCommunity -> {
                if (update.community.haveAccess) communityNames[update.community.id] = update.community.name
                else communityNames.remove(update.community.id)
            }
            is TdApi.UpdateReactionNotificationSettings -> {
                _reactionNotificationSettings.value = update.notificationSettings
            }
            is TdApi.UpdateConnectionState -> {
                connectionState.value = update.state
                logNotificationDelivery { "TDLib connection ready=${update.state is TdApi.ConnectionStateReady}" }
            }
            is TdApi.UpdateNewMessage -> if (!update.message.isOutgoing) {
                logNotificationDelivery {
                    "TDLib incoming message ageMs=${System.currentTimeMillis() - update.message.date * 1_000L}"
                }
            }
            is TdApi.UpdateHavePendingNotifications -> {
                havePendingNotifications.value = update.haveDelayedNotifications ||
                    update.haveUnreceivedNotifications
                logNotificationDelivery {
                    "TDLib notifications pending delayed=${update.haveDelayedNotifications} " +
                        "unreceived=${update.haveUnreceivedNotifications}"
                }
            }
            is TdApi.UpdateAuthorizationState,
            is TdApi.UpdateActiveNotifications,
            is TdApi.UpdateNotificationGroup,
            is TdApi.UpdateNotification -> onNotificationUpdate(update, this)
        }
        when (update) {
            is TdApi.UpdateCall, is TdApi.UpdateNewCallSignalingData, is TdApi.UpdateAuthorizationState ->
                onCallUpdate(update, this)
        }
        updateListeners.forEach { it(update) }

        if (update !is TdApi.UpdateAuthorizationState) {
            return
        }

        val authorizationState = update.authorizationState
        if (authorizationState is TdApi.AuthorizationStateClosed) networkMonitor.close()

        if (authorizationState is TdApi.AuthorizationStateWaitTdlibParameters) {
            Log.i(LOG_TAG, "TDLib requests parameters")
            sendParameters()
        }

        _authorizationState.value = authorizationState
    }

    private fun sendParameters() {
        client.send(parameters) { result ->
            if (result !is TdApi.Error) {
                Log.i(LOG_TAG, "TDLib parameters accepted")
                client.send(TdApi.GetOption(NOTIFICATION_GROUP_COUNT)) { option ->
                    if (option is TdApi.OptionValueEmpty) {
                        client.send(TdApi.SetOption(NOTIFICATION_GROUP_COUNT, TdApi.OptionValueInteger(20L))) {
                            notificationOptionsReady.complete(it is TdApi.Ok)
                        }
                    } else {
                        notificationOptionsReady.complete(option is TdApi.OptionValueInteger)
                    }
                }
                return@send
            }
            Log.e(LOG_TAG, "SetTdlibParameters failed: ${result.code} ${result.message}")
            when (val key = fallbackKey) {
                null -> _initError.value = result
                else -> {
                    fallbackKey = null
                    parameters.databaseEncryptionKey = key
                    sendParameters()
                }
            }
        }
    }

    companion object {
        internal const val NOTIFICATION_GROUP_COUNT = "notification_group_count_max"
        private val LOG_TAG = TelegramClient::class.simpleName!!
    }
}
