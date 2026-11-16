/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.github.borz7zy.shadowgram.repositories

import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import com.github.borz7zy.shadowgram.models.Provider
import com.github.borz7zy.shadowgram.models.ProviderIdentifier
import com.github.borz7zy.shadowgram.models.ProviderType

/**
 * Stub of Twelve's ProvidersRepository with the same public API.
 * ponytail: providers live in memory only and there are none at startup, persist them (Room in
 * the original) once a real data source exists.
 */
class ProvidersRepository {
    private val _allProvidersToArguments = MutableStateFlow(listOf<Pair<Provider, Bundle>>())
    val allProvidersToArguments = _allProvidersToArguments.asStateFlow()

    val allProviders = allProvidersToArguments.map { allProvidersToArguments ->
        allProvidersToArguments.map { it.first }
    }

    /**
     * Get a flow of the [Provider].
     */
    fun provider(
        providerIdentifier: ProviderIdentifier
    ) = providerToArguments(providerIdentifier).map { it?.first }

    /**
     * Get a flow of the [Bundle] containing the arguments.
     */
    fun providerArguments(
        providerIdentifier: ProviderIdentifier
    ) = providerToArguments(providerIdentifier).map { it?.second }

    /**
     * Add a new provider.
     *
     * @return A [Pair] containing the [ProviderType] and the ID of the new provider
     */
    suspend fun addProvider(
        providerType: ProviderType, name: String, arguments: Bundle
    ): Pair<ProviderType, Long> {
        val typeId = System.nanoTime()

        _allProvidersToArguments.update {
            it + (Provider(providerType, typeId, name) to arguments)
        }

        return providerType to typeId
    }

    /**
     * Update an already existing provider.
     */
    suspend fun updateProvider(
        providerIdentifier: ProviderIdentifier,
        name: String,
        arguments: Bundle
    ) {
        _allProvidersToArguments.update { allProvidersToArguments ->
            allProvidersToArguments.map {
                when (it.first.identifier == providerIdentifier) {
                    true -> Provider(providerIdentifier, name) to arguments
                    false -> it
                }
            }
        }
    }

    /**
     * Delete a provider.
     */
    suspend fun deleteProvider(providerIdentifier: ProviderIdentifier) {
        _allProvidersToArguments.update { allProvidersToArguments ->
            allProvidersToArguments.filterNot { it.first.identifier == providerIdentifier }
        }
    }

    private fun providerToArguments(
        providerIdentifier: ProviderIdentifier
    ) = allProvidersToArguments.map {
        it.firstOrNull { (provider, _) -> provider.identifier == providerIdentifier }
    }
}
