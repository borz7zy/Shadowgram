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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
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

    private val textInputLayouts
        get() = listOf(phoneNumberTextInputLayout, codeTextInputLayout, passwordTextInputLayout)

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
        val qrBackCallback = requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner, enabled = false
        ) { viewModel.cancelQrCode() }

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
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest { (step, isSubmitting, error) ->
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
                        else -> null
                    }

                    linearProgressIndicator.isVisible = step is Step.Loading || isSubmitting
                    contentLinearLayout.isVisible = step !is Step.Loading

                    val qrLink = (step as? Step.QrCode)?.link
                    if (qrLink != displayedQrLink) {
                        qrCodeImageView.setImageDrawable(qrLink?.let(::createQrDrawable))
                        displayedQrLink = qrLink
                    }
                    qrCodeImageView.isVisible = qrLink != null
                    qrBackCallback.isEnabled = step is Step.QrCode
                    qrErrorTextView.isVisible = step is Step.QrCode && error != null
                    qrErrorTextView.text = if (step is Step.QrCode) error else null
                    requestQrCodeButton.isVisible = step is Step.PhoneNumber || step is Step.Code
                    requestQrCodeButton.isEnabled = !isSubmitting
                    cancelQrCodeButton.isVisible = step is Step.QrCode
                    cancelQrCodeButton.isEnabled = !isSubmitting

                    descriptionTextView.text = when (step) {
                        is Step.PhoneNumber -> getString(R.string.auth_phone_number_description)
                        is Step.Code -> getString(
                            R.string.auth_code_description,
                            PhoneNumberParser.parse(step.phoneNumber)?.formatted
                                ?: step.phoneNumber
                        )
                        is Step.Password -> getString(R.string.auth_password_description)
                        is Step.QrCode -> getString(R.string.auth_qr_description)
                        else -> getString(R.string.auth_unsupported_step)
                    }

                    textInputLayouts.forEach {
                        it.isVisible = it === textInputLayout
                        it.isEnabled = !isSubmitting
                        it.error = error.takeIf { _ -> it === textInputLayout }
                    }

                    passwordTextInputLayout.helperText = (step as? Step.Password)?.hint
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { getString(R.string.auth_password_hint_format, it) }

                    val hasDialpad = textInputLayout in dialpadTextInputLayouts

                    dialpadConstraintLayout.isVisible = hasDialpad

                    // Show the cursor, the dialpad types where it is
                    textInputLayout?.editText?.requestFocus()

                    if (hasDialpad) {
                        // The keyboard may still be there from the password
                        WindowCompat.getInsetsController(requireActivity().window, view)
                            .hide(WindowInsetsCompat.Type.ime())
                    }

                    nextButton.isVisible = textInputLayout != null
                    nextButton.isEnabled = !isSubmitting
                    submitButtonsLayout.isVisible = textInputLayout != null

                    changePhoneNumberButton.isVisible = step is Step.Code || step is Step.Password
                    changePhoneNumberButton.isEnabled = !isSubmitting
                }
            }
        }
    }

    private fun createQrDrawable(link: String): BitmapDrawable {
        // Encode at one pixel per module. Keep the built-in white quiet zone and crisp edges.
        val matrix = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 0, 0)
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) Color.BLACK else Color.WHITE
        }
        val bitmap = Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        return BitmapDrawable(resources, bitmap).apply { isFilterBitmap = false }
    }

    /**
     * Show the country of the phone number and split the number in groups.
     */
    private fun onPhoneNumberChanged(editable: Editable) {
        if (isFormattingPhoneNumber) {
            return
        }

        val phoneNumber = PhoneNumberParser.parse(editable.toString())

        // Never empty, to keep the dialpad where it is
        phoneNumberTextInputLayout.helperText = phoneNumber?.country ?: " "

        val formatted = phoneNumber?.formatted ?: return
        if (formatted == editable.toString()) {
            return
        }

        val digitsBeforeCursor = editable
            .take(Selection.getSelectionEnd(editable).coerceAtLeast(0))
            .count { it in '0'..'9' }

        isFormattingPhoneNumber = true
        editable.replace(0, editable.length, formatted)
        isFormattingPhoneNumber = false

        Selection.setSelection(
            editable,
            PhoneNumberParser.offsetAfterDigits(formatted, digitsBeforeCursor)
        )
    }

    private fun submit() {
        textInputLayouts.firstOrNull { it.isVisible }?.editText?.text?.let {
            viewModel.submit(it.toString().trim())
        }
    }
}
