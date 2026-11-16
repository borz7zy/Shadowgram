/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.github.borz7zy.shadowgram.datasources

import android.util.Patterns
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.models.ProviderArgument

/*
 * Stubs of Twelve's data sources: only the provider arguments that ProviderType and the manage
 * provider UI need, there is no data behind them.
 */

private fun serverArgument() = ProviderArgument(
    "server",
    String::class,
    R.string.provider_argument_server,
    required = true,
    hidden = false,
    validate = {
        when (Patterns.WEB_URL.matcher(it).matches()) {
            false -> ProviderArgument.ValidationError(
                "Invalid URL",
                R.string.provider_argument_validation_error_malformed_http_uri,
            )

            true -> null
        }
    }
)

private fun usernameArgument() = ProviderArgument(
    "username",
    String::class,
    R.string.provider_argument_username,
    required = true,
    hidden = false,
)

private fun passwordArgument(defaultValue: String?) = ProviderArgument(
    "password",
    String::class,
    R.string.provider_argument_password,
    required = true,
    hidden = true,
    defaultValue = defaultValue,
)

object SubsonicDataSource {
    val ARG_SERVER = serverArgument()
    val ARG_USERNAME = usernameArgument()
    val ARG_PASSWORD = passwordArgument(null)
    val ARG_USE_LEGACY_AUTHENTICATION = ProviderArgument(
        "use_legacy_authentication",
        Boolean::class,
        R.string.provider_argument_use_legacy_authentication,
        required = true,
        hidden = false,
        defaultValue = false,
    )
}

object JellyfinDataSource {
    val ARG_SERVER = serverArgument()
    val ARG_USERNAME = usernameArgument()
    val ARG_PASSWORD = passwordArgument("")
}

object AmpacheDataSource {
    val ARG_SERVER = serverArgument()
    val ARG_USERNAME = usernameArgument()
    val ARG_PASSWORD = passwordArgument("")
}
