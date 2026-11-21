/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.fragments

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.text.Editable
import android.text.Selection
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.CheckBox
import org.drinkless.tdlib.TdApi
import androidx.activity.addCallback
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.ActivityNavigatorExtras
import androidx.navigation.fragment.findNavController
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputLayout
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.ext.getViewProperty
import com.github.borz7zy.shadowgram.ext.navigateSafe
import com.github.borz7zy.shadowgram.ext.updatePadding
import com.github.borz7zy.shadowgram.telegram.PhoneNumberParser
import com.github.borz7zy.shadowgram.viewmodels.AuthViewModel
import com.github.borz7zy.shadowgram.viewmodels.AuthViewModel.Step

/**
 * Sign in to a Telegram account, opens the home page once done.
 */
class AuthFragment : Fragment(R.layout.fragment_auth) {
    // View models
    private val viewModel by viewModels<AuthViewModel>()

    // Views
    private val backspaceButton by getViewProperty<Button>(R.id.backspaceButton)
    private val changePhoneNumberButton by getViewProperty<Button>(R.id.changePhoneNumberButton)
    private val codeTextInputLayout by getViewProperty<TextInputLayout>(R.id.codeTextInputLayout)
    private val contentLinearLayout by getViewProperty<LinearLayout>(R.id.contentLinearLayout)
    private val descriptionTextView by getViewProperty<TextView>(R.id.descriptionTextView)
    private val dialpadConstraintLayout by getViewProperty<ConstraintLayout>(R.id.dialpadConstraintLayout)
    private val linearProgressIndicator by getViewProperty<LinearProgressIndicator>(R.id.linearProgressIndicator)
    private val nestedScrollView by getViewProperty<View>(R.id.nestedScrollView)
    private val nextButton by getViewProperty<Button>(R.id.nextButton)
    private val passwordTextInputLayout by getViewProperty<TextInputLayout>(R.id.passwordTextInputLayout)
    private val phoneNumberTextInputLayout by getViewProperty<TextInputLayout>(R.id.phoneNumberTextInputLayout)
    private val qrCodeImageView by getViewProperty<ImageView>(R.id.qrCodeImageView)
    private val qrErrorTextView by getViewProperty<TextView>(R.id.qrErrorTextView)
    private val requestQrCodeButton by getViewProperty<Button>(R.id.requestQrCodeButton)
    private val cancelQrCodeButton by getViewProperty<Button>(R.id.cancelQrCodeButton)
    private val submitButtonsLayout by getViewProperty<LinearLayout>(R.id.submitButtonsLayout)
    private val firstNameTextInputLayout by getViewProperty<TextInputLayout>(R.id.firstNameTextInputLayout)
    private val lastNameTextInputLayout by getViewProperty<TextInputLayout>(R.id.lastNameTextInputLayout)
    private val emailTextInputLayout by getViewProperty<TextInputLayout>(R.id.emailTextInputLayout)
    private val termsTextView by getViewProperty<TextView>(R.id.termsTextView)
    private val termsCheckBox by getViewProperty<CheckBox>(R.id.termsCheckBox)
    private val resendCodeButton by getViewProperty<Button>(R.id.resendCodeButton)
    private val recoverPasswordButton by getViewProperty<Button>(R.id.recoverPasswordButton)
    private val cancelRecoveryButton by getViewProperty<Button>(R.id.cancelRecoveryButton)

    private val textInputLayouts
        get() = listOf(phoneNumberTextInputLayout, codeTextInputLayout, passwordTextInputLayout,
            firstNameTextInputLayout, lastNameTextInputLayout, emailTextInputLayout)

    /**
     * The text input layouts filled with the dialpad instead of the keyboard.
     */
    private val dialpadTextInputLayouts
        get() = listOf(phoneNumberTextInputLayout, codeTextInputLayout)

    private val currentEditText
        get() = textInputLayouts.firstOrNull { it.isVisible }?.editText

    /**
     * Whether the phone number is being replaced with its formatted version.
     */
    private var isFormattingPhoneNumber = false
    private var phoneFormattingJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Insets
        ViewCompat.setOnApplyWindowInsetsListener(nestedScrollView) { v, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                        or WindowInsetsCompat.Type.displayCutout()
                        or WindowInsetsCompat.Type.ime()
            )

            v.updatePadding(
                insets,
                start = true,
                end = true,
                bottom = true,
            )

            windowInsets
        }

        nextButton.setOnClickListener {
            submit()
        }

        changePhoneNumberButton.setOnClickListener {
            viewModel.changePhoneNumber()
        }

        requestQrCodeButton.setOnClickListener { viewModel.requestQrCode() }
        cancelQrCodeButton.setOnClickListener { viewModel.cancelQrCode() }
        resendCodeButton.setOnClickListener { viewModel.resendCode() }
        recoverPasswordButton.setOnClickListener { viewModel.recoverPassword() }
        cancelRecoveryButton.setOnClickListener { viewModel.cancelRecovery() }
        termsCheckBox.setOnCheckedChangeListener { _, checked ->
            if (viewModel.uiState.value.step is Step.Registration) nextButton.isEnabled = checked && !viewModel.uiState.value.isSubmitting
        }
        val qrBackCallback = requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner, enabled = false
        ) { viewModel.cancelQrCode() }
        val stepBackCallback = requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, enabled = false) {
            if (viewModel.uiState.value.step is Step.Recovery) viewModel.cancelRecovery() else viewModel.changePhoneNumber()
        }

        textInputLayouts.forEach {
            it.editText?.setOnEditorActionListener { _, _, _ ->
                submit()
                true
            }
        }

        dialpadTextInputLayouts.forEach {
            it.editText?.showSoftInputOnFocus = false
        }

        phoneNumberTextInputLayout.editText?.doAfterTextChanged {
            it?.let(::onPhoneNumberChanged)
        }

        // Dialpad, the keys without a digit delete
        dialpadConstraintLayout.children.filterIsInstance<Button>().forEach { button ->
            val keyCode = button.text.toString().toIntOrNull()?.let {
                KeyEvent.KEYCODE_0 + it
            } ?: KeyEvent.KEYCODE_DEL

            button.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

                // Let the edit text handle the key as if it came from a keyboard
                currentEditText?.apply {
                    dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                    dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
                }
            }
        }

        backspaceButton.setOnLongClickListener {
            currentEditText?.text?.clear()
            true
        }

        var displayedQrLink: String? = null
        var displayedStep: Class<*>? = null
        var displayedCodeNumber: String? = null
        var formattedCodeNumber: String? = null
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest { state ->
                    val (step, isSubmitting, error) = state
                    val qrLink = (step as? Step.QrCode)?.link
                    val codeNumber = (step as? Step.Code)?.info?.phoneNumber
                    val newQrBitmap = if (qrLink != displayedQrLink && qrLink != null) {
                        withContext(Dispatchers.Default) { createQrBitmap(qrLink) }
                    } else null
                    if (codeNumber != displayedCodeNumber) {
                        formattedCodeNumber = codeNumber?.let { number ->
                            withContext(Dispatchers.Default) {
                                PhoneNumberParser.parse(number)?.formatted ?: number
                            }
                        }
                        displayedCodeNumber = codeNumber
                    }
                    val hadStep = displayedStep != null
                    val stepChanged = displayedStep != step.javaClass
                    displayedStep = step.javaClass
                    if (step is Step.Done) {
                        // Start over with the home page of the new account
                        findNavController().navigateSafe(
                            R.id.action_authFragment_to_mainActivity,
                            navigatorExtras = ActivityNavigatorExtras(
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_CLEAR_TASK
                            ),
                        )
                        return@collectLatest
                    }

                    val textInputLayout = when (step) {
                        is Step.PhoneNumber -> phoneNumberTextInputLayout
                        is Step.Code -> codeTextInputLayout
                        is Step.Password -> passwordTextInputLayout
                        is Step.Registration -> firstNameTextInputLayout
                        is Step.Email -> emailTextInputLayout
                        is Step.EmailCode, is Step.Recovery -> codeTextInputLayout
                        else -> null
                    }

                    linearProgressIndicator.isVisible = step is Step.Loading || isSubmitting
                    contentLinearLayout.isVisible = step !is Step.Loading

                    if (qrLink != displayedQrLink) {
                        qrCodeImageView.setImageDrawable(newQrBitmap?.let {
                            BitmapDrawable(resources, it).apply { isFilterBitmap = false }
                        })
                        displayedQrLink = qrLink
                    }
                    qrCodeImageView.isVisible = qrLink != null
                    qrBackCallback.isEnabled = step is Step.QrCode
                    stepBackCallback.isEnabled = step is Step.Code || step is Step.Password || step is Step.Registration ||
                        step is Step.Email || step is Step.EmailCode || step is Step.Recovery || step is Step.Premium
                    qrErrorTextView.isVisible = textInputLayout == null && error != null
                    qrErrorTextView.text = error
                    requestQrCodeButton.isVisible = step is Step.PhoneNumber || step is Step.Code
                    requestQrCodeButton.isEnabled = !isSubmitting
                    cancelQrCodeButton.isVisible = step is Step.QrCode
                    cancelQrCodeButton.isEnabled = !isSubmitting

                    descriptionTextView.text = when (step) {
                        is Step.PhoneNumber -> getString(R.string.auth_phone_number_description)
                        is Step.Code -> codeDescription(step.info, formattedCodeNumber ?: step.info.phoneNumber) +
                                if (step.info.nextType == null) "\n\n" + getString(R.string.auth_resend_unavailable) else ""
                        is Step.Password -> getString(R.string.auth_password_description)
                        is Step.QrCode -> getString(R.string.auth_qr_description)
                        is Step.Email -> getString(R.string.auth_email_description)
                        is Step.EmailCode -> getString(R.string.auth_email_code_description, step.email)
                        is Step.Recovery -> getString(R.string.auth_recovery_description, step.email)
                        is Step.Registration -> getString(R.string.auth_registration)
                        is Step.Premium -> getString(R.string.auth_premium_required, step.days, step.supportEmail)
                        else -> ""
                    }

                    textInputLayouts.forEach {
                        it.isVisible = it === textInputLayout || (step is Step.Registration && it === lastNameTextInputLayout)
                        it.isEnabled = !isSubmitting
                        it.error = error.takeIf { _ -> it === textInputLayout }
                    }

                    passwordTextInputLayout.helperText = (step as? Step.Password)?.hint
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { getString(R.string.auth_password_hint_format, it) }

                    termsTextView.isVisible = step is Step.Registration
                    termsCheckBox.isVisible = step is Step.Registration
                    if (step is Step.Registration) {
                        termsTextView.text = step.terms
                        termsCheckBox.text = if (step.minimumAge > 0) getString(R.string.auth_terms_accept, step.minimumAge)
                            else getString(R.string.auth_terms_accept_any)
                    }
                    resendCodeButton.isVisible = step is Step.Code || step is Step.EmailCode || step is Step.Recovery
                    resendCodeButton.isEnabled = state.canResend && !isSubmitting
                    resendCodeButton.text = if (state.resendSeconds > 0) getString(R.string.auth_resend_wait, state.resendSeconds)
                        else getString(R.string.auth_resend)
                    recoverPasswordButton.isVisible = (step as? Step.Password)?.canRecover == true
                    recoverPasswordButton.isEnabled = !isSubmitting
                    cancelRecoveryButton.isVisible = step is Step.Recovery
                    cancelRecoveryButton.isEnabled = !isSubmitting
                    val wordCode = step is Step.Code && (step.info.type is TdApi.AuthenticationCodeTypeSmsWord || step.info.type is TdApi.AuthenticationCodeTypeSmsPhrase)
                    val inputType = if (wordCode) android.text.InputType.TYPE_CLASS_TEXT else android.text.InputType.TYPE_CLASS_NUMBER
                    if (codeTextInputLayout.editText?.inputType != inputType) codeTextInputLayout.editText?.inputType = inputType
                    codeTextInputLayout.editText?.showSoftInputOnFocus = wordCode
                    val hasDialpad = textInputLayout in dialpadTextInputLayouts && !wordCode

                    dialpadConstraintLayout.isVisible = hasDialpad

                    // Show the cursor, the dialpad types where it is
                    if (stepChanged) {
                        if (hadStep && textInputLayout === codeTextInputLayout) codeTextInputLayout.editText?.text?.clear()
                        textInputLayout?.editText?.requestFocus()
                    }

                    if (hasDialpad && stepChanged) {
                        // The keyboard may still be there from the password
                        WindowCompat.getInsetsController(requireActivity().window, view)
                            .hide(WindowInsetsCompat.Type.ime())
                    }

                    nextButton.isVisible = textInputLayout != null
                    nextButton.isEnabled = !isSubmitting && (step !is Step.Registration || termsCheckBox.isChecked)
                    submitButtonsLayout.isVisible = textInputLayout != null

                    changePhoneNumberButton.isVisible = stepBackCallback.isEnabled
                    changePhoneNumberButton.isEnabled = !isSubmitting
                }
            }
        }
    }

    private fun createQrBitmap(link: String): Bitmap {
        // Encode at one pixel per module. Keep the built-in white quiet zone and crisp edges.
        val matrix = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 0, 0)
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    }

    /**
     * Show the country of the phone number and split the number in groups.
     */
    private fun onPhoneNumberChanged(editable: Editable) {
        if (isFormattingPhoneNumber) {
            return
        }

        val snapshot = editable.toString()
        phoneFormattingJob?.cancel()
        phoneFormattingJob = viewLifecycleOwner.lifecycleScope.launch {
            val phoneNumber = withContext(Dispatchers.Default) { PhoneNumberParser.parse(snapshot) }
            if (editable.toString() != snapshot) return@launch

            // Never empty, to keep the dialpad where it is
            phoneNumberTextInputLayout.helperText = phoneNumber?.country ?: " "
            val formatted = phoneNumber?.formatted ?: return@launch
            if (formatted == snapshot) return@launch

            val digitsBeforeCursor = snapshot
                .take(Selection.getSelectionEnd(editable).coerceAtLeast(0))
                .count { it in '0'..'9' }

            isFormattingPhoneNumber = true
            try {
                editable.replace(0, editable.length, formatted)
                Selection.setSelection(
                    editable,
                    PhoneNumberParser.offsetAfterDigits(formatted, digitsBeforeCursor)
                )
            } finally {
                isFormattingPhoneNumber = false
            }
        }
    }

    private fun submit() {
        textInputLayouts.firstOrNull { it.isVisible }?.editText?.text?.let {
            val value = if (viewModel.uiState.value.step is Step.Password) it.toString() else it.toString().trim()
            viewModel.submit(value, lastNameTextInputLayout.editText?.text?.toString()?.trim().orEmpty(), termsCheckBox.isChecked)
        }
    }

    private fun codeDescription(info: TdApi.AuthenticationCodeInfo, phone: String): String {
        return when (val type = info.type) {
            is TdApi.AuthenticationCodeTypeTelegramMessage -> getString(R.string.auth_code_telegram, phone)
            is TdApi.AuthenticationCodeTypeCall -> getString(R.string.auth_code_call, phone)
            is TdApi.AuthenticationCodeTypeFlashCall -> getString(R.string.auth_code_flash, phone)
            is TdApi.AuthenticationCodeTypeMissedCall -> getString(R.string.auth_code_missed, phone)
            is TdApi.AuthenticationCodeTypeFragment -> getString(R.string.auth_code_fragment, type.url)
            is TdApi.AuthenticationCodeTypeSmsWord -> getString(R.string.auth_code_word, phone, type.firstLetter)
            is TdApi.AuthenticationCodeTypeSmsPhrase -> getString(R.string.auth_code_phrase, phone, type.firstWord)
            else -> getString(R.string.auth_code_sms, phone)
        }
    }
}
