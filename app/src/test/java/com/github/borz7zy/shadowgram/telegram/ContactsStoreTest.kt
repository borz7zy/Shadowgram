/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.telegram

import com.github.borz7zy.shadowgram.models.ContactItem.LastSeen
import com.github.borz7zy.shadowgram.models.ContactRow
import com.github.borz7zy.shadowgram.models.ContactRow.Companion.toContactRows
import com.github.borz7zy.shadowgram.models.ContactRow.Segment
import com.github.borz7zy.shadowgram.models.SortingRule
import com.github.borz7zy.shadowgram.models.SortingStrategy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Collator
import java.util.Locale

class ContactsStoreTest {
    private val store = ContactsStore { function, handler ->
        if (function is TdApi.GetContacts) {
            handler(TdApi.Users())
        }
    }

    private fun user(
        id: Long,
        firstName: String,
        lastName: String = "",
        isContact: Boolean = true,
        status: TdApi.UserStatus = TdApi.UserStatusEmpty(),
    ) = TdApi.User().apply {
        this.id = id
        this.firstName = firstName
        this.lastName = lastName
        this.isContact = isContact
        this.status = status
    }

    private fun snapshot() = runBlocking { store.contacts().first() }

    private fun rows(strategy: SortingStrategy, reverse: Boolean = false) =
        snapshot().items.toContactRows(SortingRule(strategy, reverse), Collator.getInstance(Locale.ROOT))

    private fun List<ContactRow>.describe() = map {
        when (it) {
            is ContactRow.Header -> it.title
            is ContactRow.Contact -> "${it.contact.id}:${it.segment}"
        }
    }

    @Test
    fun keepsOnlyContactsAndFollowsUpdates() {
        store.onUpdate(TdApi.UpdateUser(user(1, "Alice", "Smith")))
        store.onUpdate(TdApi.UpdateUser(user(2, "Stranger", isContact = false)))
        store.onUpdate(TdApi.UpdateUserStatus(2, TdApi.UserStatusOnline(0)))

        assertTrue(snapshot().isLoaded)
        assertEquals(listOf("Alice Smith"), snapshot().items.map { it.name })
        assertEquals(LastSeen(LastSeen.Kind.LONG_AGO), snapshot().items.single().lastSeen)

        store.onUpdate(TdApi.UpdateUserStatus(1, TdApi.UserStatusOffline(100)))
        assertEquals(LastSeen(LastSeen.Kind.OFFLINE, 100), snapshot().items.single().lastSeen)

        // Removed from the contacts
        store.onUpdate(TdApi.UpdateUser(user(1, "Alice", "Smith", isContact = false)))
        assertEquals(emptyList<Long>(), snapshot().items.map { it.id })
    }

    @Test
    fun sortsAndGroupsContacts() {
        store.onUpdate(TdApi.UpdateUser(user(1, "bob", status = TdApi.UserStatusRecently())))
        store.onUpdate(TdApi.UpdateUser(user(2, "Alice", status = TdApi.UserStatusOffline(100))))
        store.onUpdate(TdApi.UpdateUser(user(3, "Bill", status = TdApi.UserStatusOnline(0))))
        store.onUpdate(TdApi.UpdateUser(user(4, "42", status = TdApi.UserStatusOffline(200))))
        store.onUpdate(TdApi.UpdateUser(user(5, "Boris")))

        assertEquals(
            listOf("#", "4:SINGLE", "A", "2:SINGLE", "B", "3:FIRST", "1:MIDDLE", "5:LAST"),
            rows(SortingStrategy.NAME).describe(),
        )
        assertEquals(
            listOf("B", "5:FIRST", "1:MIDDLE", "3:LAST", "A", "2:SINGLE", "#", "4:SINGLE"),
            rows(SortingStrategy.NAME, reverse = true).describe(),
        )

        // Online, then by exact time, then the approximate statuses; a single group
        assertEquals(
            listOf("3:FIRST", "4:MIDDLE", "2:MIDDLE", "1:MIDDLE", "5:LAST"),
            rows(SortingStrategy.LAST_SEEN).describe(),
        )
        assertTrue(
            rows(SortingStrategy.LAST_SEEN).all { it is ContactRow.Contact && it.showLastSeen }
        )
    }
}
