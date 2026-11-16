/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.telegram

import com.github.borz7zy.shadowgram.models.ContactItem
import com.github.borz7zy.shadowgram.models.ContactItem.LastSeen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import org.drinkless.tdlib.TdApi

/**
 * Contacts of an account, kept up to date from TDLib updates. Works like [ChatsStore].
 *
 * @param send Sends a request to TDLib, the handler is called on the TDLib thread
 */
class ContactsStore(
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit) -> Unit,
) {
    /**
     * @param items The contacts, in no particular order
     * @param isLoaded Whether TDLib answered, so an empty list really is empty
     */
    data class ContactsSnapshot(
        val items: List<ContactItem>,
        val isLoaded: Boolean,
    )

    private val lock = Any()

    // Guarded by lock
    private val contacts = HashMap<Long, TdApi.User>()
    private val photoFileIdToUserIds = HashMap<Int, MutableSet<Long>>()
    private val requestedPhotoFileIds = HashSet<Int>()
    private var isLoadRequested = false
    private var isLoaded = false

    private val version = MutableStateFlow(0L)

    /**
     * The contacts, starts loading them when collected.
     */
    fun contacts(): Flow<ContactsSnapshot> = version
        .map { snapshot() }
        .distinctUntilChanged()
        .onStart { load() }
        .flowOn(Dispatchers.Default)

    /**
     * Apply a TDLib update. Must be called for every update, in order.
     */
    fun onUpdate(update: TdApi.Object) {
        val changed = synchronized(lock) { apply(update) }

        if (changed) {
            version.update { it + 1 }
        }
    }

    private fun apply(update: TdApi.Object): Boolean {
        when (update) {
            is TdApi.UpdateUser -> {
                val user = update.user

                if (!user.isContact) {
                    return contacts.remove(user.id) != null
                }

                contacts[user.id] = user
                trackPhoto(user)
            }

            is TdApi.UpdateUserStatus -> {
                val user = contacts[update.userId] ?: return false

                user.status = update.status
            }

            is TdApi.UpdateFile -> {
                val userIds = photoFileIdToUserIds[update.file.id] ?: return false

                userIds.forEach { userId ->
                    contacts[userId]?.profilePhoto?.takeIf { it.small.id == update.file.id }
                        ?.small = update.file
                }
            }

            else -> return false
        }

        return true
    }

    /**
     * Ask TDLib for all the contacts, their [TdApi.UpdateUser] come before the answer.
     */
    private fun load() {
        synchronized(lock) {
            if (isLoadRequested) {
                return
            }
            isLoadRequested = true
        }

        send(TdApi.GetContacts()) {
            synchronized(lock) {
                isLoaded = true
            }
            version.update { it + 1 }
        }
    }

    /**
     * Download the small photo of the user if needed, its [TdApi.UpdateFile] fills the path.
     *
     * ponytail: the photos of all the contacts are downloaded upfront, download them when rows
     * are bound if contact lists ever get too big for that.
     */
    private fun trackPhoto(user: TdApi.User) {
        val file = user.profilePhoto?.small ?: return

        photoFileIdToUserIds.getOrPut(file.id, ::HashSet).add(user.id)

        if (!file.local.isDownloadingCompleted && requestedPhotoFileIds.add(file.id)) {
            send(TdApi.DownloadFile(file.id, PHOTO_DOWNLOAD_PRIORITY, 0, 0, false)) {}
        }
    }

    private fun snapshot() = synchronized(lock) {
        ContactsSnapshot(contacts.values.map { it.toContactItem() }, isLoaded)
    }

    companion object {
        private const val PHOTO_DOWNLOAD_PRIORITY = 1

        private fun TdApi.User.toContactItem() = ContactItem(
            id = id,
            name = "$firstName $lastName".trim(),
            accentColorId = accentColorId,
            photoPath = profilePhoto?.small?.local?.takeIf { it.isDownloadingCompleted }?.path,
            minithumbnail = profilePhoto?.minithumbnail?.data,
            lastSeen = status.toLastSeen(),
        )
    }
}

internal fun TdApi.UserStatus.toLastSeen() = when (this) {
    is TdApi.UserStatusOnline -> LastSeen(LastSeen.Kind.ONLINE)
    is TdApi.UserStatusOffline -> LastSeen(LastSeen.Kind.OFFLINE, wasOnline)
    is TdApi.UserStatusRecently -> LastSeen(LastSeen.Kind.RECENTLY)
    is TdApi.UserStatusLastWeek -> LastSeen(LastSeen.Kind.LAST_WEEK)
    is TdApi.UserStatusLastMonth -> LastSeen(LastSeen.Kind.LAST_MONTH)
    else -> LastSeen(LastSeen.Kind.LONG_AGO)
}
