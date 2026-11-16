/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.telegram

import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.util.Locale

/**
 * Phone number and country parsing, backed by the country list of TDLib.
 */
object PhoneNumberParser {
    /**
     * @param formatted The phone number in international format, split in groups
     * @param country The flag and the name of the country, null if not known yet
     */
    data class PhoneNumber(val formatted: String, val country: String?)

    /**
     * Parse a phone number, or the beginning of it.
     *
     * @param getInfo The source of the phone number information, given the digits of the number
     * @return The parsed number, null if [phoneNumber] has no digits
     */
    fun parse(
        phoneNumber: String,
        getInfo: (String) -> TdApi.PhoneNumberInfo? = ::getPhoneNumberInfo,
    ): PhoneNumber? {
        val digits = phoneNumber.filter(::isDigit)
        if (digits.isEmpty()) {
            return null
        }

        val info = getInfo(digits) ?: return PhoneNumber("+$digits", null)

        // The digits yet to be typed are reported as '-'
        val nationalNumber = info.formattedPhoneNumber.trimEnd('-', ' ')

        val formatted = buildString {
            append('+')
            append(info.countryCallingCode)
            if (info.countryCallingCode.isNotEmpty() && nationalNumber.isNotEmpty()) {
                append(' ')
            }
            append(nationalNumber)
        }.takeIf {
            // Never let the formatting change what the user typed
            it.filter(::isDigit) == digits
        } ?: "+$digits"

        val country = info.country?.let {
            val name = Locale.forLanguageTag("und-${it.countryCode}").displayCountry
                .takeIf { name -> name.isNotEmpty() && name != it.countryCode }
                ?: it.name

            when (info.isAnonymous) {
                true -> name
                false -> "${flagOf(it.countryCode)} $name"
            }
        }

        return PhoneNumber(formatted, country)
    }

    /**
     * Get the offset in [text] right after the given number of digits, used to keep the cursor
     * in place when the separators of the text change.
     */
    fun offsetAfterDigits(text: CharSequence, digits: Int): Int {
        var seen = 0
        return text.indexOfFirst { isDigit(it) && ++seen == digits } + 1
    }

    private fun isDigit(char: Char) = char in '0'..'9'

    /**
     * Get the flag emoji of an ISO 3166-1 alpha-2 country code.
     */
    private fun flagOf(countryCode: String) = buildString {
        countryCode.forEach { appendCodePoint(0x1F1E6 + (it.uppercaseChar() - 'A')) }
    }

    // The country names come from Locale, TDLib ones are just a fallback: no need to load
    // the localized country list, the built-in English one is enough
    private fun getPhoneNumberInfo(digits: String) = runCatching {
        Client.execute(TdApi.GetPhoneNumberInfoSync("en", digits))
    }.getOrNull()
}
