/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.viewmodels

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import com.github.borz7zy.shadowgram.ShadowgramApplication
import com.github.borz7zy.shadowgram.ext.CONTACTS_SORTING_REVERSE_KEY
import com.github.borz7zy.shadowgram.ext.CONTACTS_SORTING_STRATEGY_KEY
import com.github.borz7zy.shadowgram.ext.contactsSortingRule
import com.github.borz7zy.shadowgram.ext.preferenceFlow
import com.github.borz7zy.shadowgram.models.ContactRow
import com.github.borz7zy.shadowgram.models.ContactRow.Companion.toContactRows
import com.github.borz7zy.shadowgram.models.FlowResult
import com.github.borz7zy.shadowgram.models.SortingRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/**
 * Contacts of the active account and their sorting. Kept across configuration changes, so
 * nothing is reloaded.
 */
class ContactsViewModel(application: Application) : AndroidViewModel(application) {
    /**
     * @param sortingRule The [SortingRule] the rows have been made with
     * @param rows The rows, in display order
     */
    data class ContactList(
        val sortingRule: SortingRule,
        val rows: List<ContactRow>,
    )

    private val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(application)!!

    val sortingRule = sharedPreferences.preferenceFlow(
        CONTACTS_SORTING_STRATEGY_KEY,
        CONTACTS_SORTING_REVERSE_KEY,
        getter = SharedPreferences::contactsSortingRule,
    )
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            sharedPreferences.contactsSortingRule,
        )

    private val contacts = flow {
        emitAll(
            getApplication<ShadowgramApplication>().accountsRepository.activeClient()
                .contacts.contacts()
        )
    }

    val contactList = combine(contacts, sortingRule) { contacts, sortingRule ->
        when (contacts.isLoaded) {
            true -> FlowResult.Success(
                ContactList(sortingRule, contacts.items.toContactRows(sortingRule))
            )

            false -> FlowResult.Loading
        }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            FlowResult.Loading,
        )

    fun setSortingRule(sortingRule: SortingRule) {
        sharedPreferences.contactsSortingRule = sortingRule
    }
}
