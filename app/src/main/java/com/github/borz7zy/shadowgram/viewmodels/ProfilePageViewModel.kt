/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.github.borz7zy.shadowgram.ShadowgramApplication
import com.github.borz7zy.shadowgram.models.FlowResult
import com.github.borz7zy.shadowgram.models.MessageItem
import com.github.borz7zy.shadowgram.models.ProfileItem
import com.github.borz7zy.shadowgram.telegram.Downloads
import com.github.borz7zy.shadowgram.telegram.ProfilePage
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One tab of a profile. The voice tab switches between voice and video messages, each loaded
 * once and kept, so switching back and rotating reload nothing.
 */
class ProfilePageViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val chatId = savedStateHandle.get<Long>(ARG_CHAT_ID) ?: 0L

    private val client = viewModelScope.async {
        getApplication<ShadowgramApplication>().accountsRepository.activeClient()
    }

    /** What the page shows, only the voice tab changes it. */
    val kind = MutableStateFlow(
        savedStateHandle.get<String>(ARG_KIND)?.let(ProfilePage.Kind::valueOf)
            ?: ProfilePage.Kind.MEDIA
    )

    private val pages = HashMap<ProfilePage.Kind, Deferred<ProfilePage>>()

    private fun page(kind: ProfilePage.Kind) = pages.getOrPut(kind) {
        viewModelScope.async { ProfilePage(client.await(), getApplication(), chatId, kind) }
    }

    /**
     * @param kind The kind the items are of
     */
    data class Items(val kind: ProfilePage.Kind, val items: List<ProfileItem>)

    @OptIn(ExperimentalCoroutinesApi::class)
    val items = kind
        .flatMapLatest { kind ->
            flow { emitAll(page(kind).await().snapshots()) }.map<_, FlowResult<Items, Nothing>> {
                when (it.isLoaded) {
                    true -> FlowResult.Success(Items(kind, it.items))
                    false -> FlowResult.Loading
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, FlowResult.Loading)

    /** Start or cancel downloading a file, its row shows the progress. */
    fun toggleDownload(document: MessageItem.Document) {
        viewModelScope.launch { Downloads.toggleDownload(client.await(), document) }
    }

    fun loadMore() {
        val page = page(kind.value)
        viewModelScope.launch { page.await().loadMore() }
    }

    companion object {
        const val ARG_CHAT_ID = "chat_id"
        const val ARG_KIND = "kind"
    }
}
