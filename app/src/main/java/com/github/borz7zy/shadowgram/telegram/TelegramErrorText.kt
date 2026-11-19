package com.github.borz7zy.shadowgram.telegram

import android.content.Context
import com.github.borz7zy.shadowgram.R
import org.drinkless.tdlib.TdApi

object TelegramErrorText {
    data class Description(val resource: Int, val arguments: List<Any> = emptyList())

    fun describe(error: TdApi.Error): Description {
        val code = error.message.orEmpty()
        val wait = Regex("(?:FLOOD_WAIT_|retry after )(\\d+)", RegexOption.IGNORE_CASE).find(code)
        if (wait != null) return Description(R.string.auth_error_flood, listOf(wait.groupValues[1].toIntOrNull() ?: 60))
        val text = when {
            code.contains("PHONE_NUMBER_BANNED") -> R.string.auth_error_banned
            code.contains("PHONE_NUMBER_INVALID") -> R.string.auth_error_phone
            code.contains("CODE_EXPIRED") -> R.string.auth_error_expired
            code.contains("CODE_INVALID") || code.contains("CODE_EMPTY") -> R.string.auth_error_code
            code.contains("PASSWORD_HASH_INVALID") -> R.string.auth_error_password
            code.contains("PASSWORD_RECOVERY") -> R.string.auth_error_recovery
            code.contains("EMAIL_ADDRESS_INVALID") || code.contains("EMAIL_NOT_ALLOWED") -> R.string.auth_error_email
            code.contains("FIRSTNAME_INVALID") || code.contains("LASTNAME_INVALID") -> R.string.auth_error_name
            code.contains("CHAT_ADMIN_REQUIRED") -> R.string.action_error_admin
            code.contains("CHAT_WRITE_FORBIDDEN") -> R.string.action_error_write
            code.contains("MESSAGE_DELETE_FORBIDDEN") -> R.string.action_error_delete
            code.contains("MESSAGE_NOT_FOUND") || code.contains("MESSAGE_ID_INVALID") -> R.string.action_error_message_missing
            code.contains("FORWARDS_RESTRICTED") || code.contains("CHAT_FORWARDS_RESTRICTED") -> R.string.action_error_protected
            code.contains("STICKERS_TOO_MUCH") -> R.string.action_error_sticker_limit
            code.contains("PINNED_DIALOGS_TOO_MUCH") || code.contains("PINNED_CHATS_TOO_MUCH") -> R.string.action_error_pin_limit
            code.contains("AUTH_RESTART") || code.contains("AUTH_TOKEN_EXPIRED") -> R.string.auth_error_restart
            code.contains("APP_SIGNUP_FORBIDDEN") -> R.string.auth_error_signup_forbidden
            error.code == 429 -> return Description(R.string.auth_error_flood, listOf(60))
            error.code == 500 || error.code == 502 || code.contains("NETWORK") || code.contains("TIMEOUT") -> R.string.auth_error_network
            else -> return Description(R.string.auth_error_generic, listOf(error.code))
        }
        return Description(text)
    }

    fun message(context: Context, error: TdApi.Error): String = describe(error).let {
        context.getString(it.resource, *it.arguments.toTypedArray())
    }
}
