package com.github.borz7zy.shadowgram.telegram

import com.github.borz7zy.shadowgram.R
import org.drinkless.tdlib.TdApi
import org.junit.Assert.*
import org.junit.Test

class TelegramErrorTextTest {
    @Test fun authenticationFailuresUseSpecificDescriptions() {
        assertEquals(R.string.auth_error_code, TelegramErrorText.describe(TdApi.Error(400, "PHONE_CODE_INVALID")).resource)
        assertEquals(R.string.auth_error_expired, TelegramErrorText.describe(TdApi.Error(400, "EMAIL_CODE_EXPIRED")).resource)
        assertEquals(R.string.auth_error_password, TelegramErrorText.describe(TdApi.Error(400, "PASSWORD_HASH_INVALID")).resource)
        assertEquals(R.string.auth_error_banned, TelegramErrorText.describe(TdApi.Error(400, "PHONE_NUMBER_BANNED")).resource)
    }

    @Test fun floodWaitCarriesServerTimeout() {
        val message = TelegramErrorText.describe(TdApi.Error(429, "FLOOD_WAIT_123"))
        assertEquals(R.string.auth_error_flood, message.resource)
        assertEquals(listOf(123), message.arguments)
    }

    @Test fun plainRetryAfterIsAlsoUnderstood() {
        assertEquals(listOf(25), TelegramErrorText.describe(TdApi.Error(429, "Too Many Requests: retry after 25")).arguments)
    }

    @Test fun protectedContentAndMissingRightsHaveReadableDescriptions() {
        assertEquals(R.string.action_error_protected, TelegramErrorText.describe(TdApi.Error(400, "CHAT_FORWARDS_RESTRICTED")).resource)
        assertEquals(R.string.action_error_admin, TelegramErrorText.describe(TdApi.Error(400, "CHAT_ADMIN_REQUIRED")).resource)
    }

    @Test fun unknownErrorsDoNotExposeRawPayloadsToTheUi() {
        val message = TelegramErrorText.describe(TdApi.Error(400, "UNKNOWN_ERROR internal details"))
        assertEquals(R.string.auth_error_generic, message.resource)
        assertEquals(listOf(400), message.arguments)
    }
}
