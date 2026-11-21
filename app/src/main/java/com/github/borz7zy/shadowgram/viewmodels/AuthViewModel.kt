/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.viewmodels

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.ShadowgramApplication
import com.github.borz7zy.shadowgram.models.Result
import com.github.borz7zy.shadowgram.telegram.TelegramClient
import com.github.borz7zy.shadowgram.telegram.TelegramErrorText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

class AuthViewModel(application: Application) : AndroidViewModel(application) {
    sealed interface Step {
        /**
         * TDLib is loading or the last step has been accepted.
         */
        data object Loading : Step
        data object PhoneNumber : Step
        data class Code(val info: TdApi.AuthenticationCodeInfo) : Step {
            val phoneNumber get() = info.phoneNumber
        }
        data class QrCode(val link: String) : Step
        data class Password(val hint: String, val canRecover: Boolean) : Step
        data class Recovery(val email: String) : Step
        data class Registration(val terms: String, val minimumAge: Int) : Step
        data object Email : Step
        data class EmailCode(val email: String) : Step
        data class Premium(val days: Int, val supportEmail: String) : Step
        data object Done : Step
    }

    data class UiState(
        val step: Step = Step.Loading,
        val isSubmitting: Boolean = false,
        val error: String? = null,
        val resendSeconds: Int = 0,
        val canResend: Boolean = false,
    )

    private val accountsRepository = getApplication<ShadowgramApplication>().accountsRepository
    private val client = MutableStateFlow<TelegramClient?>(null)
    private val isChangingPhoneNumber = MutableStateFlow(false)
    private val isSubmitting = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val recovering = MutableStateFlow(false)
    private var resendAt = 0L
    private var codeInfo: TdApi.AuthenticationCodeInfo? = null
    private var emailResendAt = 0L

    @OptIn(ExperimentalCoroutinesApi::class)
    private val authorization = client.flatMapLatest { it?.authorizationState ?: flowOf(null) }
        .onEach { state ->
            if (state is TdApi.AuthorizationStateWaitCode && state.codeInfo !== codeInfo) {
                codeInfo = state.codeInfo
                resendAt = SystemClock.elapsedRealtime() + state.codeInfo.timeout * 1000L
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private data class Controls(val changing: Boolean, val submitting: Boolean, val error: String?, val recovering: Boolean)
    private val controls = combine(isChangingPhoneNumber, isSubmitting, error, recovering, ::Controls)
    private val clock = flow { while (true) { emit(SystemClock.elapsedRealtime()); delay(1000) } }

    val uiState = combine(authorization, controls, clock) { state, controls, now ->
        val step = if (controls.changing) Step.PhoneNumber else when (state) {
            is TdApi.AuthorizationStateWaitPhoneNumber -> Step.PhoneNumber
            is TdApi.AuthorizationStateWaitCode -> Step.Code(state.codeInfo)
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> Step.QrCode(state.link)
            is TdApi.AuthorizationStateWaitPassword -> if (controls.recovering) Step.Recovery(state.recoveryEmailAddressPattern)
                else Step.Password(state.passwordHint, state.hasRecoveryEmailAddress)
            is TdApi.AuthorizationStateWaitRegistration -> Step.Registration(state.termsOfService.text.text, state.termsOfService.minUserAge)
            is TdApi.AuthorizationStateWaitEmailAddress -> Step.Email
            is TdApi.AuthorizationStateWaitEmailCode -> Step.EmailCode(state.codeInfo.emailAddressPattern)
            is TdApi.AuthorizationStateWaitPremiumPurchase -> Step.Premium(state.premiumDayCount, state.supportEmailAddress)
            is TdApi.AuthorizationStateReady -> if (controls.submitting) Step.Loading else Step.Done
            else -> Step.Loading
        }
        val seconds = when (step) {
            is Step.Code -> ((resendAt - now + 999) / 1000).toInt().coerceAtLeast(0)
            is Step.EmailCode, is Step.Recovery -> ((emailResendAt - now + 999) / 1000).toInt().coerceAtLeast(0)
            else -> 0
        }
        UiState(step, controls.submitting, controls.error, seconds,
            seconds == 0 && (step is Step.EmailCode || step is Step.Recovery || (step is Step.Code && step.info.nextType != null)))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    init {
        viewModelScope.launch { client.value = accountsRepository.newAccountClient() }
    }

    fun submit(value: String, lastName: String = "", termsAccepted: Boolean = false) {
        val function = when (val step = uiState.value.step) {
            is Step.PhoneNumber -> {
                val digits = value.filter { it in '0'..'9' }
                TdApi.SetAuthenticationPhoneNumber(
                    if (digits.isEmpty()) value else "+$digits",
                    null,
                )
            }
            is Step.Code -> TdApi.CheckAuthenticationCode(value)
            is Step.Password -> TdApi.CheckAuthenticationPassword(value)
            is Step.Email -> TdApi.SetAuthenticationEmailAddress(value)
            is Step.EmailCode -> TdApi.CheckAuthenticationEmailCode(TdApi.EmailAddressAuthenticationCode(value))
            is Step.Recovery -> TdApi.RecoverAuthenticationPassword(value, "", "")
            is Step.Registration -> {
                if (!termsAccepted || value.isBlank()) {
                    error.value = getApplication<Application>().getString(R.string.auth_error_name)
                    return
                }
                TdApi.RegisterUser(value, lastName, false)
            }
            else -> return
        }
        submitRequest { it.send(function) }
    }

    fun resendCode() {
        if (!uiState.value.canResend) return
        submitRequest {
            val result = if (uiState.value.step is Step.Recovery) it.send(TdApi.RequestAuthenticationPasswordRecovery())
                else it.send(TdApi.ResendAuthenticationCode(TdApi.ResendCodeReasonUserRequest()))
            if (result is Result.Success) emailResendAt = SystemClock.elapsedRealtime() + 60_000
            result
        }
    }

    fun recoverPassword() {
        if ((uiState.value.step as? Step.Password)?.canRecover != true) return
        submitRequest {
            it.send(TdApi.RequestAuthenticationPasswordRecovery()).also { result ->
                if (result is Result.Success) {
                    emailResendAt = SystemClock.elapsedRealtime() + 60_000
                    recovering.value = true
                }
            }
        }
    }

    fun cancelRecovery() { recovering.value = false; error.value = null }

    fun requestQrCode() {
        val step = uiState.value.step
        if (step !is Step.PhoneNumber && step !is Step.Code) return
        submitRequest { it.send(TdApi.RequestQrCodeAuthentication(accountsRepository.signedInUserIds())) }
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
                    is Result.Failure -> error.value = TelegramErrorText.message(getApplication(), result.error)
                }
            } finally { isSubmitting.value = false }
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
                    is Result.Failure -> error.value = TelegramErrorText.message(getApplication(), result.error)
                }
            } finally { isSubmitting.value = false }
        }
    }

    fun changePhoneNumber() {
        error.value = null
        recovering.value = false
        isChangingPhoneNumber.value = true
    }
}
