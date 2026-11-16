/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.fragments

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.ext.getViewProperty
import com.github.borz7zy.shadowgram.models.Result
import com.github.borz7zy.shadowgram.ui.views.FullscreenLoadingProgressBar
import com.github.borz7zy.shadowgram.viewmodels.NewChatViewModel
import com.github.borz7zy.shadowgram.viewmodels.NewChatViewModel.Action
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

/**
 * Asks for the phone number of a new contact or the title of a new group or channel, then adds
 * or creates it.
 */
class NewChatDialogFragment : MaterialDialogFragment(R.layout.fragment_new_chat_dialog) {
    // View models
    private val viewModel by viewModels<NewChatViewModel>()

    // Views
    private val cancelMaterialButton by getViewProperty<MaterialButton>(R.id.cancelMaterialButton)
    private val confirmMaterialButton by getViewProperty<MaterialButton>(R.id.confirmMaterialButton)
    private val fullscreenLoadingProgressBar by getViewProperty<FullscreenLoadingProgressBar>(R.id.fullscreenLoadingProgressBar)
    private val iconImageView by getViewProperty<ImageView>(R.id.iconImageView)
    private val inputTextInputLayout by getViewProperty<TextInputLayout>(R.id.inputTextInputLayout)
    private val titleTextView by getViewProperty<TextView>(R.id.titleTextView)

    // Arguments
    private val action: Action
        get() = Action.valueOf(requireArguments().getString(ARG_ACTION)!!)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val action = action
        val isContact = action == Action.ADD_CONTACT

        iconImageView.setImageResource(
            when (action) {
                Action.ADD_CONTACT -> R.drawable.ic_person_add
                Action.CREATE_GROUP -> R.drawable.ic_group_add
                Action.CREATE_CHANNEL -> R.drawable.ic_campaign
            }
        )
        titleTextView.setText(
            when (action) {
                Action.ADD_CONTACT -> R.string.new_chat_add_contact
                Action.CREATE_GROUP -> R.string.new_chat_create_group
                Action.CREATE_CHANNEL -> R.string.new_chat_create_channel
            }
        )
        inputTextInputLayout.setHint(
            when (action) {
                Action.ADD_CONTACT -> R.string.new_chat_phone_number_hint
                Action.CREATE_GROUP -> R.string.new_chat_group_name_hint
                Action.CREATE_CHANNEL -> R.string.new_chat_channel_name_hint
            }
        )
        confirmMaterialButton.setText(
            when (isContact) {
                true -> R.string.new_chat_add
                false -> R.string.new_chat_create
            }
        )

        val editText = inputTextInputLayout.editText!!
        editText.inputType = when (isContact) {
            true -> InputType.TYPE_CLASS_PHONE
            false -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        editText.doOnTextChanged { _, _, _, _ ->
            inputTextInputLayout.error = null
        }

        cancelMaterialButton.setOnClickListener {
            findNavController().navigateUp()
        }

        confirmMaterialButton.setOnClickListener {
            val text = editText.text.toString().trim()

            if (text.isEmpty()) {
                inputTextInputLayout.error = getString(R.string.new_chat_error_empty)
                return@setOnClickListener
            }

            viewLifecycleOwner.lifecycleScope.launch {
                fullscreenLoadingProgressBar.withProgress {
                    when (val result = viewModel.submit(action, text)) {
                        is Result.Success -> findNavController().navigateUp()
                        is Result.Failure -> inputTextInputLayout.error = when {
                            isContact && result.error.code == ERROR_CODE_NOT_FOUND -> getString(
                                R.string.new_chat_error_user_not_found
                            )

                            else -> result.error.message
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val ARG_ACTION = "action"

        private const val ERROR_CODE_NOT_FOUND = 404

        /**
         * Create a [Bundle] to use as the arguments for this fragment.
         *
         * @param action What to ask for and do
         */
        fun createBundle(action: Action) = Bundle().apply {
            putString(ARG_ACTION, action.name)
        }
    }
}
