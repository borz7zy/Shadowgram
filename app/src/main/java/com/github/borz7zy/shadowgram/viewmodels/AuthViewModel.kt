/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.github.borz7zy.shadowgram.ShadowgramApplication
import com.github.borz7zy.shadowgram.models.Result
import com.github.borz7zy.shadowgram.telegram.TelegramClient
import org.drinkless.tdlib.TdApi

/**
 * Sign in of a new account.
 */
class AuthViewModel(application: Application) : AndroidViewModel(application) {
    sealed interface Step {
        /**
         * TDLib is loading or the last step has been accepted.
         */
        data object Loading : Step

        data object PhoneNumber : Step

        /**
         * @param phoneNumber The phone number the code has been sent to
         */
        data class Code(val phoneNumber: String) : Step

        data class QrCode(val link: String) : Step

        /**
         * @param hint The hint of the two-step verification password, may be empty
         */
        data class Password(val hint: String) : Step

        /**
         * A step this screen cannot handle yet, like registration or email confirmation.
         */
        data object Unsupported : Step

        data object Done : Step
    }

    /**
     * @param step The current [Step]
     * @param isSubmitting Whether TDLib is checking the last submitted value
     * @param error The message of the error of the last submitted value, if any
     */
    data class UiState(
        val step: Step = Step.Loading,
        val isSubmitting: Boolean = false,
        val error: String? = null,
    )

    private val accountsRepository = getApplication<ShadowgramApplication>().accountsRepository
    private val client = MutableStateFlow<TelegramClient?>(null)

    /**
     * Whether the user asked to change the phone number after it has been accepted.
     */
    private val isChangingPhoneNumber = MutableStateFlow(false)

    private val isSubmitting = MutableStateFlow(false)

    private val error = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState = combine(
        client.flatMapLatest { it?.authorizationState ?: flowOf(null) },
        isChangingPhoneNumber,
        isSubmitting,
        error,
    ) { authorizationState, isChangingPhoneNumber, isSubmitting, error ->
        UiState(
            when (authorizationState) {
                null,
                is TdApi.AuthorizationStateLoggingOut,
                is TdApi.AuthorizationStateClosing,
                is TdApi.AuthorizationStateClosed,
                is TdApi.AuthorizationStateWaitTdlibParameters -> Step.Loading

                is TdApi.AuthorizationStateWaitPhoneNumber -> Step.PhoneNumber

                is TdApi.AuthorizationStateWaitCode -> when (isChangingPhoneNumber) {
                    true -> Step.PhoneNumber
                    false -> Step.Code(authorizationState.codeInfo.phoneNumber)
                }

                is TdApi.AuthorizationStateWaitOtherDeviceConfirmation ->
                    Step.QrCode(authorizationState.link)

                is TdApi.AuthorizationStateWaitPassword -> when (isChangingPhoneNumber) {
                    true -> Step.PhoneNumber
                    false -> Step.Password(authorizationState.passwordHint)
                }

                is TdApi.AuthorizationStateReady -> if (isSubmitting) Step.Loading else Step.Done

                else -> Step.Unsupported
            },
            isSubmitting,
            error,
        )
    }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            UiState(),
        )

    init {
        viewModelScope.launch {
            client.value = accountsRepository.newAccountClient()
        }
    }

    /**
     * Submit the value of the current step.
     */
    fun submit(value: String) {
        val function = when (uiState.value.step) {
            is Step.PhoneNumber -> TdApi.SetAuthenticationPhoneNumber(value, null)
            is Step.Code -> TdApi.CheckAuthenticationCode(value)
            is Step.Password -> TdApi.CheckAuthenticationPassword(value)
            else -> return
        }

        submitRequest { it.send(function) }
    }

    fun requestQrCode() {
        val step = uiState.value.step
        if (step !is Step.PhoneNumber && step !is Step.Code) return

        submitRequest {
            it.send(TdApi.RequestQrCodeAuthentication(accountsRepository.signedInUserIds()))
        }
    }

    private fun submitRequest(request: suspend (TelegramClient) -> Result<TdApi.Ok, TdApi.Error>) {
        if (isSubmitting.value) return
        val currentClient = client.value ?: return
        isSubmitting.value = true
        error.value = null

        viewModelScope.launch {
            try {
                when (val result = request(currentClient)) {
                    is Result.Success -> isChangingPhoneNumber.value = false
                    is Result.Failure -> error.value = result.error.message
                }
            } finally {
                isSubmitting.value = false
            }
        }
    }

    fun cancelQrCode() {
        if (isSubmitting.value || uiState.value.step !is Step.QrCode) return
        val currentClient = client.value ?: return
        isSubmitting.value = true
        error.value = null

        viewModelScope.launch {
            try {
                when (val result = accountsRepository.cancelQrSignIn(currentClient)) {
                    is Result.Success -> client.value = result.data
                    is Result.Failure -> error.value = result.error.message
                }
            } finally {
                isSubmitting.value = false
            }
        }
    }

    /**
     * Go back to the phone number step.
     */
    fun changePhoneNumber() {
        error.value = null
        isChangingPhoneNumber.value = true
    }
}
