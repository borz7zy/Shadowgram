/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.github.borz7zy.shadowgram.viewmodels

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import com.github.borz7zy.shadowgram.ext.ALBUMS_SORTING_REVERSE_KEY
import com.github.borz7zy.shadowgram.ext.ALBUMS_SORTING_STRATEGY_KEY
import com.github.borz7zy.shadowgram.ext.albumsSortingRule
import com.github.borz7zy.shadowgram.ext.preferenceFlow
import com.github.borz7zy.shadowgram.models.FlowResult
import com.github.borz7zy.shadowgram.models.FlowResult.Companion.asFlowResult
import com.github.borz7zy.shadowgram.models.SortingRule

class AlbumsViewModel(application: Application) : TwelveViewModel(application) {
    val sortingRule = sharedPreferences.preferenceFlow(
        ALBUMS_SORTING_STRATEGY_KEY,
        ALBUMS_SORTING_REVERSE_KEY,
        getter = SharedPreferences::albumsSortingRule,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val albums = sortingRule
        .flatMapLatest { mediaRepository.albums(it) }
        .asFlowResult()
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(),
            FlowResult.Loading
        )

    fun setSortingRule(sortingRule: SortingRule) {
        sharedPreferences.albumsSortingRule = sortingRule
    }
}
