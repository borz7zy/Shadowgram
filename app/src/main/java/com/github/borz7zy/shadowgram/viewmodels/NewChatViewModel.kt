/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.github.borz7zy.shadowgram.ShadowgramApplication
import com.github.borz7zy.shadowgram.models.Result
import com.github.borz7zy.shadowgram.models.Result.Companion.flatMap
import com.github.borz7zy.shadowgram.models.Result.Companion.map
import org.drinkless.tdlib.TdApi

/**
 * Things made from the new chat menu of the active account.
 */
class NewChatViewModel(application: Application) : AndroidViewModel(application) {
    enum class Action {
        /**
         * Add the user with a phone number to the contacts.
         */
        ADD_CONTACT,

        /**
         * Create a basic group with a title and no other members.
         */
        CREATE_GROUP,

        /**
         * Create a channel with a title.
         */
        CREATE_CHANNEL,
    }

    /**
     * @param text The phone number or the title, depending on the [action]
     */
    suspend fun submit(action: Action, text: String): Result<Unit, TdApi.Error> {
        val client = getApplication<ShadowgramApplication>().accountsRepository.activeClient()

        return when (action) {
            // A contact needs a name and only the number is asked, so the user is looked up
            // and saved with their own name
            Action.ADD_CONTACT -> client.send(TdApi.SearchUserByPhoneNumber(text, false))
                .flatMap { user ->
                    client.send(
                        TdApi.AddContact(
                            user.id,
                            TdApi.ImportedContact(text, user.firstName, user.lastName, null),
                            false,
                        )
                    )
                }

            Action.CREATE_GROUP -> client.send(
                TdApi.CreateNewBasicGroupChat(longArrayOf(), text, 0)
            )

            Action.CREATE_CHANNEL -> client.send(
                TdApi.CreateNewSupergroupChat(text, false, true, "", null, 0, false)
            )
        }.map { }
    }
}
