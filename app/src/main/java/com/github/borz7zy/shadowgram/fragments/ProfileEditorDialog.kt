/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.fragments

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.setPadding
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.ext.toPx
import com.github.borz7zy.shadowgram.models.Result
import com.github.borz7zy.shadowgram.viewmodels.ProfileViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.time.LocalDate
import java.time.MonthDay
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Each field saves through its own TDLib operation, so a rejected edit can be retried independently. */
internal class ProfileEditorDialog : DialogFragment() {
    private val viewModel by viewModels<ProfileViewModel>({ requireParentFragment() })
    private val page get() = Page.valueOf(requireArguments().getString(ARG_PAGE)!!)

    enum class Page(@param:StringRes val title: Int) {
        OVERVIEW(R.string.profile_edit),
        NAME(R.string.profile_edit_name),
        BIO(R.string.profile_bio),
        NUMBER(R.string.profile_phone),
        USERNAME(R.string.profile_username),
        BIRTHDAY(R.string.profile_birthday),
        CHANNEL(R.string.profile_personal_channel),
        COLOR(R.string.profile_edit_color),
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val info = viewModel.info.value
        val user = info?.ownUser
        val builder = MaterialAlertDialogBuilder(requireContext()).setTitle(page.title)
        if (info == null || user == null) {
            // On process recreation the profile's data may still be coming from TDLib.
            return builder.setMessage(R.string.profile_loading)
                .setNegativeButton(android.R.string.cancel, null).create().also { dialog ->
                    lifecycleScope.launch {
                        viewModel.info.first { it?.ownUser != null }
                        val parent = requireParentFragment()
                        if (!parent.childFragmentManager.isStateSaved) {
                            dismissNow()
                            show(parent, page)
                        }
                    }
                }
        }

        return when (page) {
            Page.OVERVIEW -> overview(builder)
            Page.NAME -> editor(builder) {
                val first = field(R.string.auth_first_name, user.firstName, R.id.profileEditorFirst)
                val last = field(R.string.auth_last_name, user.lastName, R.id.profileEditorSecond)
                val request: () -> TdApi.Function<TdApi.Ok>? = {
                    if (first.value.isEmpty()) {
                        first.layout.error = getString(R.string.profile_first_name_required)
                        null
                    } else TdApi.SetName(first.value, last.value)
                }
                request
            }
            Page.BIO -> editor(builder) {
                val bio = field(R.string.profile_bio, info.about.orEmpty(), R.id.profileEditorFirst)
                val request: () -> TdApi.Function<TdApi.Ok> = { TdApi.SetBio(bio.value) }
                request
            }
            Page.NUMBER -> builder
                .setMessage(R.string.profile_number_telegram)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.profile_open_telegram) { _, _ -> openNumberSettings() }
                .create()
            Page.USERNAME -> editor(builder) {
                val username = field(
                    R.string.profile_username, user.usernames?.editableUsername.orEmpty(), R.id.profileEditorFirst,
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
                )
                val request: () -> TdApi.Function<TdApi.Ok> = {
                    TdApi.SetUsername(username.value.removePrefix("@"))
                }
                request
            }
            Page.BIRTHDAY -> birthday(builder, info.birthdate)
            Page.CHANNEL -> channels(builder, info.personalChannel?.id)
            Page.COLOR -> colors(builder, user)
        }
    }

    private fun overview(builder: MaterialAlertDialogBuilder): Dialog {
        val fields = listOf(Page.NAME, Page.BIO, Page.NUMBER, Page.USERNAME, Page.BIRTHDAY, Page.CHANNEL)
        return builder.setItems(fields.map { getString(it.title) }.toTypedArray(), null)
            .setNegativeButton(android.R.string.cancel, null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.listView.setOnItemClickListener { _, _, index, _ ->
                        show(requireParentFragment(), fields[index])
                    }
                }
            }
    }

    private fun birthday(builder: MaterialAlertDialogBuilder, birthdate: TdApi.Birthdate?): Dialog =
        editor(builder, clear = birthdate?.let { TdApi.SetBirthdate(null) }) {
            val number = InputType.TYPE_CLASS_NUMBER
            val day = field(R.string.profile_day, birthdate?.day?.toString().orEmpty(), R.id.profileEditorFirst, number)
            val month = field(R.string.profile_month, birthdate?.month?.toString().orEmpty(), R.id.profileEditorSecond, number)
            val year = field(
                R.string.profile_year_optional, birthdate?.year?.takeIf { it != 0 }?.toString().orEmpty(),
                R.id.profileEditorThird, number,
            )
            val request: () -> TdApi.Function<TdApi.Ok>? = {
                val entered = runCatching {
                    val d = day.value.toInt()
                    val m = month.value.toInt()
                    val y = if (year.value.isEmpty()) 0 else year.value.toInt().also { require(it > 0) }
                    if (y == 0) MonthDay.of(m, d)
                    else require(!LocalDate.of(y, m, d).isAfter(LocalDate.now()))
                    TdApi.Birthdate(d, m, y)
                }.getOrNull()
                if (entered == null) day.layout.error = getString(R.string.profile_invalid_birthday)
                entered?.let { TdApi.SetBirthdate(it) }
            }
            request
        }

    private fun channels(builder: MaterialAlertDialogBuilder, currentId: Long?): Dialog {
        val dialog = choices(builder)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            lifecycleScope.launch {
                when (val result = viewModel.suitablePersonalChannels()) {
                    is Result.Failure -> showError(result.error.message)
                    is Result.Success -> {
                        val chats = result.data
                        if (chats.isEmpty() && currentId == null) {
                            dialog.setTitle(R.string.profile_no_channels)
                            return@launch
                        }
                        val labels = chats.map { it.title } + getString(R.string.profile_no_channel)
                        val checked = if (currentId == null) chats.size else chats.indexOfFirst { it.id == currentId }
                        bindChoices(dialog, ArrayAdapter(
                            requireContext(), android.R.layout.simple_list_item_single_choice, labels
                        ), checked) { TdApi.SetPersonalChat(chats.getOrNull(it)?.id ?: 0L) }
                    }
                }
            }
        }
        return dialog
    }

    private fun colors(builder: MaterialAlertDialogBuilder, user: TdApi.User): Dialog {
        if (!user.isPremium) {
            return builder.setMessage(R.string.profile_color_premium)
                .setPositiveButton(android.R.string.ok, null).create()
        }
        val dialog = choices(builder)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            lifecycleScope.launch {
                val colors = viewModel.profileColors()
                val labels = listOf(getString(R.string.profile_color_default)) + colors.indices.map {
                    getString(R.string.profile_color_number, it + 1)
                }
                val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                    Configuration.UI_MODE_NIGHT_YES
                val adapter = object : ArrayAdapter<String>(
                    requireContext(), android.R.layout.simple_list_item_single_choice, labels,
                ) {
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val row = super.getView(position, convertView, parent) as TextView
                        val color = colors.getOrNull(position - 1)?.let {
                            if (dark) it.darkThemeColors else it.lightThemeColors
                        }?.paletteColors?.firstOrNull()
                        val swatch = color?.let {
                            val size = row.toPx(20)
                            GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(it or 0xFF000000.toInt())
                                setBounds(0, 0, size, size)
                            }
                        }
                        row.compoundDrawablePadding = row.toPx(12)
                        row.setCompoundDrawablesRelative(swatch, null, null, null)
                        return row
                    }
                }
                val checked = if (user.profileAccentColorId < 0) 0 else {
                    colors.indexOfFirst { it.id == user.profileAccentColorId }.let { if (it < 0) -1 else it + 1 }
                }
                bindChoices(dialog, adapter, checked) {
                    TdApi.SetProfileAccentColor(
                        colors.getOrNull(it - 1)?.id ?: -1, user.profileBackgroundCustomEmojiId,
                    )
                }
            }
        }
        return dialog
    }

    private fun choices(builder: MaterialAlertDialogBuilder): AlertDialog = builder
        .setSingleChoiceItems(emptyArray<CharSequence>(), -1, null)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(R.string.profile_save, null)
        .create()

    private fun bindChoices(
        dialog: AlertDialog,
        adapter: ArrayAdapter<String>,
        checked: Int,
        request: (Int) -> TdApi.Function<TdApi.Ok>,
    ) {
        val list = dialog.listView ?: return
        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        list.adapter = adapter
        list.choiceMode = android.widget.ListView.CHOICE_MODE_SINGLE
        if (list.checkedItemPosition == -1 && checked >= 0) list.setItemChecked(checked, true)
        save.isEnabled = list.checkedItemPosition >= 0
        list.setOnItemClickListener { _, _, _, _ -> save.isEnabled = true }
        save.setOnClickListener {
            val selected = list.checkedItemPosition
            if (selected >= 0) send(dialog, request(selected))
        }
    }

    private fun editor(
        builder: MaterialAlertDialogBuilder,
        clear: TdApi.Function<TdApi.Ok>? = null,
        form: LinearLayout.() -> (() -> TdApi.Function<TdApi.Ok>?),
    ): Dialog {
        val fields = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(toPx(24))
        }
        val request = fields.form()
        val scroll = ScrollView(requireContext()).apply { addView(fields) }
        builder.setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.profile_save, null)
        if (clear != null) builder.setNeutralButton(R.string.profile_remove, null)
        return builder.create().also { dialog ->
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    request()?.let { send(dialog, it) }
                }
                if (clear != null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    send(dialog, clear)
                }
            }
        }
    }

    private data class Field(val layout: TextInputLayout, val edit: TextInputEditText) {
        val value get() = edit.text?.toString()?.trim().orEmpty()
    }

    private fun LinearLayout.field(
        @StringRes label: Int,
        value: String,
        id: Int,
        type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
    ): Field {
        val layout = TextInputLayout(context).apply { hint = getString(label) }
        val edit = TextInputEditText(context).apply {
            this.id = id
            inputType = type
            setSingleLine()
            setText(value)
        }
        layout.addView(edit, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        addView(layout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { if (childCount != 0) topMargin = toPx(12) })
        return Field(layout, edit)
    }

    private fun send(dialog: AlertDialog, request: TdApi.Function<TdApi.Ok>) {
        val buttons = listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)
            .map(dialog::getButton).filterNotNull()
        buttons.forEach { it.isEnabled = false }
        isCancelable = false
        lifecycleScope.launch {
            when (val result = viewModel.request(request)) {
                is Result.Success -> dismissAllowingStateLoss()
                is Result.Failure -> {
                    buttons.forEach { it.isEnabled = true }
                    isCancelable = true
                    showError(result.error.message)
                }
            }
        }
    }

    private fun openNumberSettings() {
        val settings = Uri.parse("tg://settings/change_number")
        for (packageName in listOf("org.telegram.messenger", "org.telegram.messenger.web")) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, settings).setPackage(packageName))
                return
            } catch (_: ActivityNotFoundException) {
                // Telegram distributes both a Play Store and a direct-download build.
            }
        }
        showError(getString(R.string.profile_telegram_missing))
    }

    private fun showError(message: String) =
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()

    companion object {
        private const val ARG_PAGE = "profile_editor_page"

        fun show(profile: Fragment, page: Page) {
            val manager = profile.childFragmentManager
            val tag = "profile_editor_${page.name}"
            if (manager.isStateSaved || manager.findFragmentByTag(tag) != null) return
            ProfileEditorDialog().apply { arguments = bundleOf(ARG_PAGE to page.name) }.show(manager, tag)
        }
    }
}

internal fun formatBirthdate(date: TdApi.Birthdate): String = if (date.year == 0) {
    MonthDay.of(date.month, date.day).format(DateTimeFormatter.ofPattern("d MMMM"))
} else {
    LocalDate.of(date.year, date.month, date.day).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))
}
