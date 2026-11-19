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
import com.github.borz7zy.shadowgram.models.Result
import com.github.borz7zy.shadowgram.telegram.Downloads
import com.github.borz7zy.shadowgram.telegram.ProfilePage
import com.github.borz7zy.shadowgram.telegram.giftOwner
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/**
 * One tab of a profile. Gift collections and the two voice modes keep separate pages.
 */
class ProfilePageViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
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

    private data class PageKey(val kind: ProfilePage.Kind, val collectionId: Int)

    private val pages = HashMap<PageKey, Deferred<ProfilePage>>()

    private fun page(key: PageKey) = pages.getOrPut(key) {
        viewModelScope.async {
            ProfilePage(client.await(), getApplication(), chatId, key.kind, key.collectionId)
        }
    }

    val selectedGiftCollectionId = savedStateHandle.getStateFlow(ARG_GIFT_COLLECTION_ID, 0)

    val giftCollections = flow {
        if (kind.value != ProfilePage.Kind.GIFTS) return@flow
        val client = client.await()
        val chat = (client.send(TdApi.GetChat(chatId)) as? Result.Success)?.data
        val owner = chat?.giftOwner() ?: return@flow
        val collections = (client.send(TdApi.GetGiftCollections(owner)) as? Result.Success)
            ?.data?.collections?.toList() ?: return@flow
        val selectedId = selectedGiftCollectionId.value
        if (selectedId != 0 && collections.none { it.id == selectedId }) {
            selectGiftCollection(0)
        }
        emit(collections)
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun selectGiftCollection(id: Int) {
        savedStateHandle[ARG_GIFT_COLLECTION_ID] = id
    }

    suspend fun giftCollectionIcon(collection: TdApi.GiftCollection): String? {
        val file = collection.icon?.sticker ?: return null
        if (file.local.isDownloadingCompleted) return file.local.path
        return (client.await().send(TdApi.DownloadFile(file.id, 8, 0, 0, true)) as? Result.Success)
            ?.data?.local?.takeIf { it.isDownloadingCompleted }?.path
    }

    /**
     * @param kind The kind the items are of
     */
    data class Items(
        val kind: ProfilePage.Kind,
        val items: List<ProfileItem>,
        val collectionId: Int,
        val giftLoadFailed: Boolean,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val items = combine(kind, selectedGiftCollectionId, ::PageKey)
        .flatMapLatest { key ->
            flow { emitAll(page(key).await().snapshots()) }.map<_, FlowResult<Items, Nothing>> {
                if (it.isLoaded || it.giftLoadFailed) {
                    FlowResult.Success(Items(key.kind, it.items, key.collectionId, it.giftLoadFailed))
                } else {
                    FlowResult.Loading
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, FlowResult.Loading)

    /** Start or cancel downloading a file, its row shows the progress. */
    fun toggleDownload(document: MessageItem.Document) {
        viewModelScope.launch { Downloads.toggleDownload(client.await(), document) }
    }

    fun loadMore(retry: Boolean = false) {
        val key = PageKey(kind.value, selectedGiftCollectionId.value)
        val current = (items.value as? FlowResult.Success)?.data
        if (current?.kind != key.kind || current.collectionId != key.collectionId) return
        val page = page(key)
        viewModelScope.launch { page.await().loadMore(retry) }
    }

    companion object {
        const val ARG_CHAT_ID = "chat_id"
        const val ARG_KIND = "kind"
        private const val ARG_GIFT_COLLECTION_ID = "gift_collection_id"
    }
}
