/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.github.borz7zy.shadowgram

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.ViewGroupCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreference
import com.github.borz7zy.shadowgram.ext.ENABLE_OFFLOAD_KEY
import com.github.borz7zy.shadowgram.ext.SKIP_SILENCE_KEY
import com.github.borz7zy.shadowgram.haptics.HapticSettingsPage
import com.github.borz7zy.shadowgram.notifications.NotificationSettingsPage
import com.github.borz7zy.shadowgram.notifications.logNotificationDelivery
import com.github.borz7zy.shadowgram.playback.MessagePlayer
import com.github.borz7zy.shadowgram.repositories.AccountsRepository
import com.github.borz7zy.shadowgram.ui.SettingsActionPreference
import com.github.borz7zy.shadowgram.viewmodels.SettingsViewModel
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity(R.layout.activity_settings) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        val content = findViewById<ViewGroup>(R.id.coordinatorLayout)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        ViewGroupCompat.installCompatInsetsDispatch(content)
        ViewCompat.setOnApplyWindowInsetsListener(content) { _, insets ->
            val safeArea = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            toolbar.updatePadding(left = safeArea.left, right = safeArea.right)
            insets
        }

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, RootSettingsFragment())
                .commit()
        }
    }

    class RootSettingsFragment : PreferenceFragmentCompat() {
        private val viewModel by viewModels<SettingsViewModel>()
        private val querySeparator = Regex("\\s+")
        private val sectionLinks = mutableListOf<Preference>()
        private val searchGroups = mutableListOf<PreferenceCategory>()
        private val searchableText = mutableMapOf<Preference, String>()
        private val sectionKey get() = arguments?.getString(ARG_PREFERENCE_ROOT)
        private var openingSection = false
        private var accountSwitchJob: Job? = null
        private var hapticsPage: HapticSettingsPage? = null
        private val pickHapticAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) {
            it?.let { uri -> hapticsPage?.import(uri) }
        }
        private var notificationPage: NotificationSettingsPage? = null
        private val pickNotificationSound = registerForActivityResult(ActivityResultContracts.OpenDocument()) {
            it?.let { uri -> notificationPage?.importSound(uri) }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            openingSection = false
            val activity = requireActivity()
            val appBar = activity.findViewById<AppBarLayout>(R.id.appBarLayout)
            activity.findViewById<CollapsingToolbarLayout>(R.id.settings_collapsing_toolbar).title =
                preferenceScreen.title ?: getString(R.string.title_activity_settings)
            val destinationKey = arguments?.getString(ARG_SCROLL_TO)
            if (savedInstanceState == null) {
                appBar.setExpanded(destinationKey == null, false)
                destinationKey?.let(::scrollToPreference)
            }

            setDivider(null)
            listView.clipToPadding = false
            listView.isVerticalScrollBarEnabled = false
            val horizontalPadding = view.paddingLeft
            val bottomPadding = (16 * resources.displayMetrics.density).toInt()
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
                val safeArea = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                view.updatePadding(
                    left = horizontalPadding + safeArea.left,
                    right = horizontalPadding + safeArea.right,
                )
                listView.updatePadding(bottom = bottomPadding + maxOf(safeArea.bottom, ime.bottom))
                insets
            }
            view.doOnAttach { ViewCompat.requestApplyInsets(it) }

            view.findViewById<View>(R.id.settings_search_box).isVisible = sectionKey == null
            when (sectionKey) {
                null -> bindSearch(view, appBar)
                SECTION_ACCOUNTS -> bindAccounts()
                SECTION_HAPTICS -> hapticsPage?.bind(viewLifecycleOwner)
                else -> notificationPage?.bind(viewLifecycleOwner)
            }
        }

        override fun onPause() {
            accountSwitchJob?.cancel()
            super.onPause()
        }

        override fun onStart() {
            super.onStart()
            val section = sectionKey ?: return
            if (section == SECTION_NOTIFICATIONS || section.startsWith("notifications_")) {
                logNotificationDelivery { "Notification settings started section=$section" }
            }
        }

        override fun onStop() {
            val section = sectionKey
            if (section != null && (section == SECTION_NOTIFICATIONS || section.startsWith("notifications_"))) {
                logNotificationDelivery { "Notification settings stopped section=$section" }
            }
            super.onStop()
        }

        private fun bindSearch(view: View, appBar: AppBarLayout) {
            val search = view.findViewById<TextInputEditText>(R.id.settings_search)
            val empty = view.findViewById<View>(R.id.settings_search_empty)
            search.setOnFocusChangeListener { _, focused ->
                if (focused) appBar.setExpanded(false, true)
            }
            search.doAfterTextChanged { text ->
                empty.isVisible = !filterSettings(text?.toString().orEmpty())
                listView.scrollToPosition(0)
            }
            search.setOnEditorActionListener { _, actionId, _ ->
                if (actionId != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
                WindowCompat.getInsetsController(requireActivity().window, search)
                    .hide(WindowInsetsCompat.Type.ime())
                true
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)
            when (rootKey) {
                null -> buildOverview()
                SECTION_AUDIO -> bindAudioActions()
                SECTION_HAPTICS -> hapticsPage = HapticSettingsPage(requireActivity(), preferenceScreen) {
                    pickHapticAudio.launch(arrayOf("audio/*"))
                }
                SECTION_NOTIFICATIONS -> {
                    val screen = preferenceScreen
                    for (section in screen.children().filterIsInstance<PreferenceScreen>().filter { it.isVisible }) {
                        screen.removePreference(section)
                        screen.addPreference(navigationRow(section, section.key).apply { key = section.key })
                    }
                }
                else -> if (rootKey.startsWith("notifications_")) {
                    preferenceScreen.isVisible = true
                    notificationPage = NotificationSettingsPage(
                        requireActivity(), preferenceScreen, rootKey,
                        arguments?.getLong(ARG_NOTIFICATION_ID) ?: 0L,
                        arguments?.getInt(ARG_NOTIFICATION_TOPIC) ?: 0,
                        { screen, id, topic -> openSection(screen, null, id, topic) },
                        { pickNotificationSound.launch(arrayOf("audio/*")) },
                    )
                }
            }
        }

        private fun buildOverview() {
            val root = preferenceScreen
            val sections = root.children().filterIsInstance<PreferenceScreen>()
            root.removeAll()
            for (section in sections) {
                val screenKey = requireNotNull(section.key)
                val link = navigationRow(section, screenKey).apply { key = screenKey }
                root.addPreference(link)
                sectionLinks.add(link)

                val results = PreferenceCategory(requireContext()).apply {
                    title = section.title
                    key = "search_$screenKey"
                    isVisible = false
                }
                root.addPreference(results)
                searchGroups.add(results)
                for (subgroup in section.children().filterIsInstance<PreferenceScreen>().filter { it.isVisible }) {
                    val result = navigationRow(subgroup, subgroup.key).apply { isVisible = false }
                    results.addPreference(result)
                    searchableText[result] = listOfNotNull(section.title, subgroup.title, subgroup.summary)
                        .joinToString(" ")
                }
                for (category in section.children().filterIsInstance<PreferenceCategory>()) {
                    for (setting in category.children()) {
                        val result = navigationRow(setting, screenKey, setting.key).apply {
                            isVisible = false
                        }
                        results.addPreference(result)
                        searchableText[result] = listOfNotNull(
                            section.title, category.title, setting.title, setting.summary,
                        ).joinToString(" ")
                    }
                }
            }
        }

        private fun navigationRow(source: Preference, screenKey: String, destinationKey: String? = null) =
            SettingsActionPreference(requireContext()).apply {
                title = source.title
                summary = source.summary
                icon = source.icon
                widgetLayoutResource = R.layout.settings_section_arrow
                isPersistent = false
                setOnPreferenceClickListener {
                    openSection(screenKey, destinationKey)
                    true
                }
            }

        private fun openSection(screenKey: String, destinationKey: String?, id: Long = 0, topic: Int = 0) {
            if (openingSection || parentFragmentManager.isStateSaved) return
            openingSection = true
            val activity = requireActivity()
            WindowCompat.getInsetsController(activity.window, requireView()).hide(WindowInsetsCompat.Type.ime())
            parentFragmentManager.commit {
                setReorderingAllowed(true)
                replace(R.id.settings, RootSettingsFragment().apply {
                    arguments = Bundle().apply {
                        putString(ARG_PREFERENCE_ROOT, screenKey)
                        putString(ARG_SCROLL_TO, destinationKey)
                        putLong(ARG_NOTIFICATION_ID, id)
                        putInt(ARG_NOTIFICATION_TOPIC, topic)
                    }
                })
                addToBackStack(null)
            }
        }

        private fun bindAccounts() {
            val group = requireNotNull(findPreference<PreferenceCategory>("settings_current_accounts"))
            if (group.preferenceCount == 0) {
                group.addPreference(accountStatusRow(R.string.settings_accounts_loading))
            }

            val repository = (requireActivity().application as ShadowgramApplication).accountsRepository
            viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    val accounts = repository.savedAccounts()
                    group.removeAll()
                    if (accounts.isEmpty()) {
                        group.addPreference(accountStatusRow(R.string.settings_accounts_empty))
                    }
                    for ((id, isActive, user) in accounts) {
                        val handle = user?.usernames?.activeUsernames?.firstOrNull()?.let { "@$it" }
                            ?: user?.phoneNumber?.takeIf(String::isNotBlank)?.let { "+${it.trimStart('+')}" }
                        val name = user?.let { "${it.firstName} ${it.lastName}".trim() }.orEmpty()
                        val detail = when {
                            isActive && handle != null -> getString(R.string.settings_account_active_detail, handle)
                            isActive -> getString(R.string.settings_account_active)
                            else -> handle
                        }
                        group.addPreference(SettingsActionPreference(requireContext()).apply {
                            key = "settings_account_$id"
                            title = name.ifEmpty { getString(R.string.settings_account_number, id + 1) }
                            summary = detail
                            setIcon(if (isActive) R.drawable.ic_check_circle else R.drawable.ic_person)
                            isSelectable = !isActive
                            isPersistent = false
                            if (!isActive) {
                                widgetLayoutResource = R.layout.settings_section_arrow
                                setOnPreferenceClickListener {
                                    switchAccount(repository, id)
                                    true
                                }
                            }
                        })
                    }
                }
            }
        }

        private fun switchAccount(repository: AccountsRepository, accountId: Int) {
            val screen = preferenceScreen
            if (!screen.isEnabled) return
            screen.isEnabled = false
            accountSwitchJob = viewLifecycleOwner.lifecycleScope.launch {
                try {
                    when (repository.activateAccount(accountId, authorizationTimeoutMillis = 5_000)) {
                        true -> {
                            if (MessagePlayer.state.track != null) MessagePlayer.stop()
                            startActivity(Intent(requireActivity(), MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            })
                        }
                        false -> Unit
                        null -> Toast.makeText(
                            requireContext(), R.string.settings_account_switch_failed, Toast.LENGTH_SHORT,
                        ).show()
                    }
                } finally {
                    screen.isEnabled = true
                }
            }
        }

        private fun accountStatusRow(titleRes: Int) = SettingsActionPreference(requireContext()).apply {
            setTitle(titleRes)
            setIcon(R.drawable.ic_person)
            isSelectable = false
            isPersistent = false
        }

        private fun bindAudioActions() {
            findPreference<SwitchPreference>(ENABLE_OFFLOAD_KEY)?.setOnPreferenceChangeListener { _, newValue ->
                lifecycleScope.launch {
                    viewModel.toggleOffload(newValue as Boolean)
                }
                true
            }

            findPreference<SwitchPreference>(SKIP_SILENCE_KEY)?.setOnPreferenceChangeListener { _, newValue ->
                lifecycleScope.launch {
                    viewModel.toggleSkipSilence(newValue as Boolean)
                }
                true
            }

            findPreference<Preference>("reset_local_stats")?.setOnPreferenceClickListener {
                showResetLocalStatsDialog()
                true
            }

            findPreference<Preference>("rescan_media_store")?.setOnPreferenceClickListener {
                showRescanMediaStoreDialog()
                true
            }
        }

        private fun filterSettings(query: String): Boolean {
            val terms = query.trim().split(querySeparator).filter(String::isNotEmpty)
            val searching = terms.isNotEmpty()
            sectionLinks.forEach { it.isVisible = !searching }
            var hasMatches = false
            for (group in searchGroups) {
                var groupHasMatches = false
                for (row in group.children()) {
                    val text = searchableText.getValue(row)
                    row.isVisible = searching && terms.all { text.contains(it, ignoreCase = true) }
                    groupHasMatches = groupHasMatches || row.isVisible
                }
                group.isVisible = groupHasMatches
                hasMatches = hasMatches || groupHasMatches
            }
            return !searching || hasMatches
        }

        private fun PreferenceGroup.children() = (0 until preferenceCount).map(::getPreference)

        private fun showResetLocalStatsDialog() {
            val context = requireActivity()
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.reset_local_stats_confirm_title)
                .setMessage(R.string.reset_local_stats_confirm_message)
                .setPositiveButton(R.string.reset_local_stats_confirm_positive) { _, _ ->
                    lifecycleScope.launch {
                        viewModel.resetLocalStats()

                        Toast.makeText(
                            context,
                            R.string.reset_local_stats_success,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> /* Do nothing */ }
                .show()
        }

        private fun showRescanMediaStoreDialog() {
            val context = requireActivity()
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.rescan_media_store_confirm_title)
                .setMessage(R.string.rescan_media_store_confirm_message)
                .setPositiveButton(R.string.rescan_media_store_confirm_positive) { _, _ ->
                    viewModel.rescanMediaStore()

                    Toast.makeText(
                        context,
                        R.string.rescan_media_store_started,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> /* Do nothing */ }
                .show()
        }

        private companion object {
            const val SECTION_ACCOUNTS = "settings_accounts"
            const val SECTION_AUDIO = "settings_audio"
            const val SECTION_HAPTICS = "settings_haptics"
            const val SECTION_NOTIFICATIONS = "settings_notifications"
            const val ARG_SCROLL_TO = "settings_scroll_to"
            const val ARG_NOTIFICATION_ID = "notification_id"
            const val ARG_NOTIFICATION_TOPIC = "notification_topic"
        }
    }
}
