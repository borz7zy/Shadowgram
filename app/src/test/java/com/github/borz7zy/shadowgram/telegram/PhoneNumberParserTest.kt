/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.telegram

import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumberParserTest {
    private fun info(
        callingCode: String,
        formatted: String,
        countryCode: String? = null,
    ) = TdApi.PhoneNumberInfo().apply {
        country = countryCode?.let {
            TdApi.CountryInfo().apply {
                this.countryCode = it
                name = "Name of $it"
            }
        }
        countryCallingCode = callingCode
        formattedPhoneNumber = formatted
    }

    @Test
    fun parse() {
        // Complete number
        PhoneNumberParser.parse("79991234567") { info("7", "999 123 45 67", "RU") }!!.let {
            assertEquals("+7 999 123 45 67", it.formatted)
            assertTrue(it.country!!.startsWith("🇷🇺 "))
        }

        // Partial number, already formatted, the digits yet to be typed are dropped
        assertEquals(
            "+7 999 1",
            PhoneNumberParser.parse("+7 999 1") { info("7", "999 1-- -- --", "RU") }!!.formatted
        )

        // Calling code only
        assertEquals("+7", PhoneNumberParser.parse("7") { info("7", "--- --- -- --") }!!.formatted)

        // Unknown country
        PhoneNumberParser.parse("0123") { info("", "0123") }!!.let {
            assertEquals("+0123", it.formatted)
            assertNull(it.country)
        }

        // Unknown country code, TDLib name
        assertEquals(
            "Name of FT",
            PhoneNumberParser.parse("888") {
                info("888", "", "FT").apply { isAnonymous = true }
            }!!.country
        )

        // The typed digits are never changed
        assertEquals("+7999", PhoneNumberParser.parse("7999") { info("7", "000") }!!.formatted)
        assertEquals("+7999", PhoneNumberParser.parse("7999") { null }!!.formatted)

        // Nothing to parse
        assertNull(PhoneNumberParser.parse("+") { error("Must not be called") })
    }

    @Test
    fun offsetAfterDigits() {
        assertEquals(6, PhoneNumberParser.offsetAfterDigits("+7 999 123", 4))
        assertEquals(10, PhoneNumberParser.offsetAfterDigits("+7 999 123", 7))
        assertEquals(0, PhoneNumberParser.offsetAfterDigits("+7 999 123", 0))
    }
}
