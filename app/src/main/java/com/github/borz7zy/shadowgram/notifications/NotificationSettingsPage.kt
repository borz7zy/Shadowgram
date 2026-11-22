/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.notifications

import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.ShadowgramApplication
import com.github.borz7zy.shadowgram.haptics.HapticAudioSource
import com.github.borz7zy.shadowgram.models.Result
import com.github.borz7zy.shadowgram.models.Result.Companion.getOrNull
import com.github.borz7zy.shadowgram.telegram.TelegramClient
import com.github.borz7zy.shadowgram.ui.SettingsActionPreference
import com.github.borz7zy.shadowgram.ui.SettingsSwitchPreference
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi

internal class NotificationSettingsPage(
    private val activity: FragmentActivity,
    private val screen: PreferenceScreen,
    private val page: String,
    private val id: Long,
    private val topicId: Int,
    private val navigate: (String, Long, Int) -> Unit,
    private val pickSound: () -> Unit,
) {
    private val application = activity.application as ShadowgramApplication
    private val content = screen.findPreference<PreferenceCategory>("notifications_telegram_sounds") ?: screen
    private val refreshes = Channel<Unit>(Channel.CONFLATED)
    private lateinit var owner: LifecycleOwner
    private lateinit var client: TelegramClient
    private var writeJob: Job? = null
    private var previewJob: Job? = null
    private var previewPlayer: MediaPlayer? = null
    private var pendingSound: Uri? = null

    fun bind(owner: LifecycleOwner) {
        this.owner = owner
        screen.findPreference<Preference>("notifications_app_sound")?.setOnPreferenceClickListener {
            openAppSoundSettings()
            true
        }
        screen.findPreference<Preference>("notifications_app_sound_preview")?.setOnPreferenceClickListener {
            preview { application.audioHaptics.defaultNotificationSource() }
            true
        }
        owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = stopPreview()
        })
        content.removeAll()
        info(content, R.string.notifications_loading)
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (page == SOUNDS) application.audioHaptics.index()
                val account = application.accountsRepository.activeClient()
                if (withTimeoutOrNull(10_000) { account.isAuthorized() } != true) {
                    content.removeAll()
                    info(content, R.string.notifications_unavailable)
                    return@repeatOnLifecycle
                }
                client = account
                val listener: (TdApi.Object) -> Unit = { update ->
                    val relevant = when (update) {
                        is TdApi.UpdateScopeNotificationSettings -> page in SCOPE_PAGES || page == STORIES
                        is TdApi.UpdateReactionNotificationSettings -> page == REACTIONS
                        is TdApi.UpdateSavedNotificationSounds -> page != OTHER
                        is TdApi.UpdateChatNotificationSettings ->
                            page == EXCEPTIONS || page == STORY_EXCEPTIONS || page in CHAT_PAGES && id == update.chatId
                        is TdApi.UpdateChatDefaultDisableNotification -> page in CHAT_PAGES && id == update.chatId
                        is TdApi.UpdateChatVideoChat -> page in CHAT_PAGES && id == update.chatId
                        is TdApi.UpdateForumTopic -> page == TOPIC && id == update.chatId && topicId == update.forumTopicId
                        is TdApi.UpdateCommunityFullInfo -> page == COMMUNITY && id == update.communityId
                        is TdApi.UpdateCommunity -> page == COMMUNITIES
                        is TdApi.UpdateOption -> page == OTHER && update.name in OPTION_NAMES
                        else -> false
                    }
                    if (relevant) refreshes.trySend(Unit)
                }
                client.addUpdateListener(listener)
                try {
                    pendingSound?.let { uri ->
                        pendingSound = null
                        importSound(uri)
                    }
                    refreshes.trySend(Unit)
                    for (refresh in refreshes) {
                        writeJob?.join()
                        try {
                            render()
                        } catch (_: LoadFailed) {
                            content.removeAll()
                            row(content, R.string.notifications_load_failed) { refreshes.trySend(Unit) }
                        }
                    }
                } finally {
                    client.removeUpdateListener(listener)
                }
            }
        }
    }

    private suspend fun render() {
        when (page) {
            in SCOPE_PAGES -> scopePage(SCOPE_PAGES.getValue(page))
            STORIES -> storiesPage()
            REACTIONS -> reactionsPage()
            EXCEPTIONS, STORY_EXCEPTIONS -> exceptionsPage(page == STORY_EXCEPTIONS)
            CHAT, STORY_CHAT, TOPIC, COMMUNITY -> exceptionEditor()
            TOPICS -> topicsPage()
            COMMUNITIES -> communitiesPage()
            SOUNDS -> soundsPage()
            OTHER -> otherPage()
        }
    }

    private suspend fun scopePage(scope: TdApi.NotificationSettingsScope) {
        val settings = send(TdApi.GetScopeNotificationSettings(scope))
        val sounds = savedSounds()
        screen.removeAll()
        val group = category(R.string.notifications_messages)
        val edit: ((TdApi.ScopeNotificationSettings) -> Unit) -> Unit = { change ->
            write { setScope(scope, change) }
        }
        choice(group, R.string.notifications_mute, settings.muteFor, muteOptions(settings.muteFor), muteSummary(settings.muteFor)) { value ->
            edit { it.muteFor = requireNotNull(value) }
        }
        soundChoice(group, R.string.notifications_sound, settings.soundId, sounds) { value ->
            edit { it.soundId = requireNotNull(value) }
        }
        toggle(group, R.string.notifications_preview, settings.showPreview) { value ->
            edit { it.showPreview = value }
        }
        toggle(group, R.string.notifications_pinned, !settings.disablePinnedMessageNotifications) { value ->
            edit { it.disablePinnedMessageNotifications = !value }
        }
        toggle(group, R.string.notifications_mentions, !settings.disableMentionNotifications) { value ->
            edit { it.disableMentionNotifications = !value }
        }
        row(screen, R.string.notifications_exceptions) { navigate(EXCEPTIONS, scope.constructor.toLong(), 0) }
    }

    private suspend fun storiesPage() {
        val settings = coroutineScope {
            SCOPE_PAGES.values.map { scope -> async { send(TdApi.GetScopeNotificationSettings(scope)) } }
                .map { it.await() }
        }
        val sounds = savedSounds()
        screen.removeAll()
        info(screen, R.string.notifications_story_delivery)
        SCOPE_PAGES.values.zip(settings).forEach { (scope, current) ->
            val group = category(scope.title())
            val delivery = when {
                current.useDefaultMuteStories -> StoryDelivery.FREQUENT
                current.muteStories -> StoryDelivery.OFF
                else -> StoryDelivery.ON
            }
            choice(group, R.string.notifications_story_enabled, delivery, listOf(
                StoryDelivery.FREQUENT to string(R.string.notifications_story_top),
                StoryDelivery.ON to string(R.string.notifications_on),
                StoryDelivery.OFF to string(R.string.notifications_off),
            )) { value ->
                write {
                    setScope(scope) {
                        it.useDefaultMuteStories = value == StoryDelivery.FREQUENT
                        it.muteStories = value == StoryDelivery.OFF
                    }
                }
            }
            soundChoice(group, R.string.notifications_story_sound, current.storySoundId, sounds) { value ->
                write { setScope(scope) { it.storySoundId = requireNotNull(value) } }
            }
            toggle(group, R.string.notifications_story_poster, current.showStoryPoster) { value ->
                write { setScope(scope) { it.showStoryPoster = value } }
            }
        }
    }

    private suspend fun setScope(scope: TdApi.NotificationSettingsScope, change: (TdApi.ScopeNotificationSettings) -> Unit) {
        val current = send(TdApi.GetScopeNotificationSettings(scope))
        send(TdApi.SetScopeNotificationSettings(scope, current.apply(change)))
    }

    private suspend fun reactionsPage() {
        val settings = withTimeoutOrNull(10_000) {
            client.reactionNotificationSettings.filterNotNull().first()
        } ?: throw LoadFailed()
        val sounds = savedSounds()
        screen.removeAll()
        val group = category(R.string.notifications_reactions)
        info(group, R.string.notifications_reaction_delivery)
        reactionSource(group, R.string.notifications_message_reactions, settings.messageReactionSource) { new ->
            messageReactionSource = new
        }
        reactionSource(group, R.string.notifications_story_reactions, settings.storyReactionSource) { new ->
            storyReactionSource = new
        }
        reactionSource(group, R.string.notifications_poll_votes, settings.pollVoteSource) { new ->
            pollVoteSource = new
        }
        soundChoice(group, R.string.notifications_sound, settings.soundId, sounds) { value ->
            write { setReactions { it.soundId = requireNotNull(value) } }
        }
        toggle(group, R.string.notifications_reaction_preview, settings.showPreview) { value ->
            write { setReactions { it.showPreview = value } }
        }
    }

    private fun reactionSource(
        group: PreferenceGroup,
        @StringRes title: Int,
        source: TdApi.ReactionNotificationSource,
        change: TdApi.ReactionNotificationSettings.(TdApi.ReactionNotificationSource) -> Unit,
    ) {
        val choices = listOf(
            TdApi.ReactionNotificationSourceNone() to R.string.notifications_off,
            TdApi.ReactionNotificationSourceContacts() to R.string.notifications_contacts,
            TdApi.ReactionNotificationSourceAll() to R.string.notifications_everyone,
        )
        choice(group, title, source.constructor, choices.map { it.first.constructor to string(it.second) }) { value ->
            write { setReactions { it.change(choices.first { pair -> pair.first.constructor == value }.first) } }
        }
    }

    private suspend fun setReactions(change: (TdApi.ReactionNotificationSettings) -> Unit) {
        val current = client.reactionNotificationSettings.value ?: throw LoadFailed()
        val settings = TdApi.ReactionNotificationSettings(
            current.messageReactionSource, current.storyReactionSource, current.pollVoteSource,
            current.soundId, current.showPreview,
        )
        send(TdApi.SetReactionNotificationSettings(settings.apply(change)))
    }

    private suspend fun exceptionsPage(stories: Boolean) {
        val scope = SCOPE_PAGES.values.firstOrNull { it.constructor.toLong() == id }
        val ids = if (stories) send(TdApi.GetStoryNotificationSettingsExceptions()).chatIds
            else send(TdApi.GetChatNotificationSettingsExceptions(scope, true)).chatIds
        val chats = readChats(ids)
        screen.removeAll()
        val group = category(if (stories) R.string.notifications_story_exceptions else R.string.notifications_exceptions)
        row(group, R.string.notifications_add_exception) { pickChat(stories, scope) }
        if (chats.isEmpty()) info(group, R.string.notifications_no_exceptions)
        for (chat in chats) {
            action(group, chat.title) { navigate(if (stories) STORY_CHAT else CHAT, chat.id, 0) }
        }
    }

    private fun pickChat(stories: Boolean, scope: TdApi.NotificationSettingsScope?) {
        NotificationChatPicker(activity, owner, client, scope).show { chatId ->
            navigate(if (stories) STORY_CHAT else CHAT, chatId, 0)
        }
    }

    private suspend fun readChats(ids: LongArray): List<TdApi.Chat> =
        ids.distinct().mapNotNull { client.send(TdApi.GetChat(it)).getOrNull() }

    private suspend fun exceptionEditor() {
        val target = when (page) {
            COMMUNITY -> {
                val full = send(TdApi.GetCommunityFullInfo(id))
                val name = client.communityName(id) ?: string(R.string.notifications_communities)
                ExceptionTarget(name, full.notificationSettings)
            }
            TOPIC -> {
                val topic = send(TdApi.GetForumTopic(id, topicId))
                ExceptionTarget(topic.info.name, topic.notificationSettings)
            }
            else -> {
                val chat = send(TdApi.GetChat(id))
                ExceptionTarget(chat.title, chat.notificationSettings, chat)
            }
        }
        val sounds = savedSounds()
        val extras = target.chat?.let { chatExtras(it) }
        setPageTitle(target.title)
        screen.removeAll()

        val edit: ((TdApi.ChatNotificationSettings) -> Unit) -> Unit = { change ->
            write { editException(change) }
        }
        if (page == STORY_CHAT) {
            addStoryExceptionRows(target.settings, sounds, edit)
            addMessageExceptionRows(target.settings, sounds, edit)
        } else {
            addMessageExceptionRows(target.settings, sounds, edit)
            addStoryExceptionRows(target.settings, sounds, edit)
        }

        val actions = category(R.string.notifications_exceptions)
        if (extras != null) {
            if (extras.hasTopics) {
                row(actions, R.string.notifications_topics) { navigate(TOPICS, id, 0) }
            }
            toggle(actions, R.string.notifications_send_silently, target.chat.defaultDisableNotification) { value ->
                write { send(TdApi.ToggleChatDefaultDisableNotification(id, value)) }
            }
            extras.giftNotifications?.let { enabled ->
                toggle(actions, R.string.notifications_chat_gifts, enabled) { value ->
                    write { send(TdApi.ToggleChatGiftNotifications(id, value)) }
                }
            }
            extras.scheduledVideoChat?.let { call ->
                toggle(actions, R.string.notifications_video_chat, call.enabledStartNotification) { value ->
                    write { send(TdApi.ToggleVideoChatEnabledStartNotification(call.id, value)) }
                }
            }
        }
        row(actions, R.string.notifications_clear_exception) {
            editExceptionDefaults()
        }
    }

    private fun addMessageExceptionRows(
        settings: TdApi.ChatNotificationSettings,
        sounds: List<TdApi.NotificationSound>,
        edit: ((TdApi.ChatNotificationSettings) -> Unit) -> Unit,
    ) {
        val group = category(R.string.notifications_messages)
        val mute = settings.muteFor.takeUnless { settings.useDefaultMuteFor }
        choice(group, R.string.notifications_mute, mute, muteOptions(mute, inherit = true), muteSummary(mute)) { value ->
            edit {
                it.useDefaultMuteFor = value == null
                it.muteFor = value ?: 0
            }
        }
        soundChoice(group, R.string.notifications_sound, settings.soundId.takeUnless { settings.useDefaultSound },
            sounds, inherit = true) { value ->
            edit {
                it.useDefaultSound = value == null
                it.soundId = value ?: -1
            }
        }
        inheritedToggle(group, R.string.notifications_preview, settings.useDefaultShowPreview, settings.showPreview) { value ->
            edit {
                it.useDefaultShowPreview = value == null
                it.showPreview = value ?: true
            }
        }
        inheritedToggle(group, R.string.notifications_pinned, settings.useDefaultDisablePinnedMessageNotifications,
            !settings.disablePinnedMessageNotifications) { value ->
            edit {
                it.useDefaultDisablePinnedMessageNotifications = value == null
                it.disablePinnedMessageNotifications = value == false
            }
        }
        inheritedToggle(group, R.string.notifications_mentions, settings.useDefaultDisableMentionNotifications,
            !settings.disableMentionNotifications) { value ->
            edit {
                it.useDefaultDisableMentionNotifications = value == null
                it.disableMentionNotifications = value == false
            }
        }
    }

    private fun addStoryExceptionRows(
        settings: TdApi.ChatNotificationSettings,
        sounds: List<TdApi.NotificationSound>,
        edit: ((TdApi.ChatNotificationSettings) -> Unit) -> Unit,
    ) {
        val group = category(R.string.notifications_stories)
        inheritedToggle(group, R.string.notifications_story_enabled, settings.useDefaultMuteStories, !settings.muteStories) { value ->
            edit {
                it.useDefaultMuteStories = value == null
                it.muteStories = value == false
            }
        }
        soundChoice(group, R.string.notifications_story_sound, settings.storySoundId.takeUnless { settings.useDefaultStorySound },
            sounds, inherit = true) { value ->
            edit {
                it.useDefaultStorySound = value == null
                it.storySoundId = value ?: -1
            }
        }
        inheritedToggle(group, R.string.notifications_story_poster, settings.useDefaultShowStoryPoster, settings.showStoryPoster) { value ->
            edit {
                it.useDefaultShowStoryPoster = value == null
                it.showStoryPoster = value ?: true
            }
        }
    }

    private fun inheritedToggle(
        group: PreferenceGroup,
        @StringRes title: Int,
        inherited: Boolean,
        value: Boolean,
        change: (Boolean?) -> Unit,
    ) {
        choice(group, title, value.takeUnless { inherited }, listOf(
            null to string(R.string.notifications_default),
            true to string(R.string.notifications_on),
            false to string(R.string.notifications_off),
        ), change = change)
    }

    private fun editExceptionDefaults() {
        write {
            saveException(TdApi.ChatNotificationSettings(
                true, 0, true, -1, true, true,
                true, false, true, -1, true, true, true, false, true, false,
            ))
        }
    }

    private suspend fun editException(change: (TdApi.ChatNotificationSettings) -> Unit) {
        val current = when (page) {
            TOPIC -> send(TdApi.GetForumTopic(id, topicId)).notificationSettings
            COMMUNITY -> send(TdApi.GetCommunityFullInfo(id)).notificationSettings
            else -> send(TdApi.GetChat(id)).notificationSettings
        }
        saveException(current.apply(change))
    }

    private suspend fun saveException(settings: TdApi.ChatNotificationSettings) {
        send(when (page) {
            TOPIC -> TdApi.SetForumTopicNotificationSettings(id, topicId, settings)
            COMMUNITY -> TdApi.SetCommunityNotificationSettings(id, settings)
            else -> TdApi.SetChatNotificationSettings(id, settings)
        })
    }

    private suspend fun chatExtras(chat: TdApi.Chat): ChatExtras = coroutineScope {
        val call = async {
            val groupCallId = chat.videoChat?.groupCallId ?: 0
            if (groupCallId == 0) null
            else client.send(TdApi.GetGroupCall(groupCallId)).getOrNull()?.takeIf { it.scheduledStartDate > 0 }
        }
        val type = chat.type
        val group = if (type is TdApi.ChatTypeSupergroup) client.send(TdApi.GetSupergroup(type.supergroupId)).getOrNull() else null
        val hasTopics = when (type) {
            is TdApi.ChatTypeSupergroup -> group?.isForum == true
            is TdApi.ChatTypePrivate -> (client.send(TdApi.GetUser(type.userId)).getOrNull()?.type as? TdApi.UserTypeBot)
                ?.hasTopics == true
            else -> false
        }
        val status = group?.status
        val canManageGifts = group?.isChannel == true && (status is TdApi.ChatMemberStatusCreator ||
            status is TdApi.ChatMemberStatusAdministrator && status.rights.canPostMessages)
        val giftNotifications = if (canManageGifts) {
            client.send(TdApi.GetReceivedGifts().apply {
                businessConnectionId = ""
                ownerId = TdApi.MessageSenderChat(chat.id)
                offset = ""
                limit = 1
            }).getOrNull()?.areNotificationsEnabled
        } else null
        ChatExtras(hasTopics, giftNotifications, call.await())
    }

    private val topics = linkedMapOf<Int, TdApi.ForumTopicInfo>()
    private var nextTopics: TdApi.GetForumTopics? = TdApi.GetForumTopics(id, "", 0, 0, 0, 50)

    private suspend fun topicsPage() {
        if (topics.isEmpty()) loadMoreTopics()
        screen.removeAll()
        val group = category(R.string.notifications_topics)
        if (topics.isEmpty()) info(group, R.string.notifications_no_topics)
        for ((topicId, topic) in topics) action(group, topic.name) { navigate(TOPIC, id, topicId) }
        if (nextTopics != null) {
            row(group, R.string.notifications_more_topics) { write { loadMoreTopics() } }
        }
    }

    private suspend fun loadMoreTopics() {
        val request = nextTopics ?: return
        val result = send(request)
        result.topics.forEach { topics[it.info.forumTopicId] = it.info }
        nextTopics = if (result.nextOffsetForumTopicId == 0 || result.topics.isEmpty() ||
            result.nextOffsetForumTopicId == request.offsetForumTopicId &&
            result.nextOffsetMessageId == request.offsetMessageId && result.nextOffsetDate == request.offsetDate
        ) null else TdApi.GetForumTopics(
            id, "", result.nextOffsetDate, result.nextOffsetMessageId, result.nextOffsetForumTopicId, 50,
        )
    }

    private suspend fun communitiesPage() {
        val communities = send(TdApi.GetJoinedCommunities())
        screen.removeAll()
        val group = category(R.string.notifications_communities)
        if (communities.communityIds.isEmpty()) info(group, R.string.notifications_no_communities)
        for (communityId in communities.communityIds) {
            val label = client.communityName(communityId) ?: string(R.string.notifications_community_number, communityId)
            action(group, label) { navigate(COMMUNITY, communityId, 0) }
        }
    }

    private fun setPageTitle(title: String) {
        screen.title = title
        activity.findViewById<CollapsingToolbarLayout>(R.id.settings_collapsing_toolbar).title = title
    }

    private data class ExceptionTarget(
        val title: String,
        val settings: TdApi.ChatNotificationSettings,
        val chat: TdApi.Chat? = null,
    )

    private data class ChatExtras(
        val hasTopics: Boolean,
        val giftNotifications: Boolean?,
        val scheduledVideoChat: TdApi.GroupCall?,
    )

    private suspend fun otherPage() {
        client.awaitNotificationSetup()
        val values = coroutineScope {
            OPTION_NAMES.associateWith { name -> async { send(TdApi.GetOption(name)) } }.mapValues { it.value.await() }
        }
        screen.removeAll()
        val events = category(R.string.notifications_other)
        for (option in BOOLEAN_OPTIONS) {
            val stored = (values[option.name] as? TdApi.OptionValueBoolean)?.value ?: false
            toggle(events, option.title, stored != option.inverted, option.summary) { value ->
                write { send(TdApi.SetOption(option.name, TdApi.OptionValueBoolean(value != option.inverted))) }
            }
        }
        val limits = category(R.string.notifications_limits)
        for ((name, title, minimum, default) in INTEGER_OPTIONS) {
            val stored = (values[name] as? TdApi.OptionValueInteger)?.value ?: default
            val choices = (minimum..25L).map { value ->
                value to if (value == 0L) string(R.string.notifications_group_limit_off) else value.toString()
            }
            choice(limits, title, stored, choices) { value ->
                write { send(TdApi.SetOption(name, TdApi.OptionValueInteger(value))) }
            }
        }
        row(limits, R.string.notifications_android) {
            activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
            })
        }
        row(screen, R.string.notifications_reset) {
            confirm(R.string.notifications_reset, string(R.string.notifications_reset_message)) {
                write { send(TdApi.ResetAllNotificationSettings()) }
            }
        }
    }

    private suspend fun soundsPage() {
        val sounds = send(TdApi.GetSavedNotificationSounds()).notificationSounds
        val limits = soundLimits()
        content.removeAll()
        row(content, R.string.notifications_add_sound, string(
            R.string.notifications_sound_limit, limits.first, Formatter.formatShortFileSize(activity, limits.second),
        ), pickSound)
        if (sounds.isEmpty()) info(content, R.string.notifications_no_sounds)
        sounds.forEach { sound ->
            action(content, sound.label()) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(sound.label())
                    .setItems(arrayOf(string(R.string.notifications_sound_preview), string(R.string.notifications_sound_remove))) { _, which ->
                        when (which) {
                            0 -> previewSound(sound)
                            1 -> confirm(R.string.notifications_sound_remove, string(
                                R.string.notifications_sound_remove_message, sound.label(),
                            )) { write { send(TdApi.RemoveSavedNotificationSound(sound.id)) } }
                        }
                    }
                    .show()
            }
        }
    }

    private fun openAppSoundSettings() {
        owner.lifecycleScope.launch {
            withContext(Dispatchers.IO) { NotificationSoundChannels.ensureDefaultChannel(application) }
            val settings = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
                putExtra(Settings.EXTRA_CHANNEL_ID, NotificationSoundChannels.DEFAULT)
            }
            logNotificationDelivery { "Opening Android notification settings" }
            try {
                activity.startActivity(settings)
            } catch (_: ActivityNotFoundException) {
                activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
                })
            }
        }
    }

    fun importSound(uri: Uri) {
        if (!::client.isInitialized) {
            pendingSound = uri
            return
        }
        val account = client
        write {
            val (_, sizeLimit) = soundLimits()
            val upload = application.backgroundScope.async(Dispatchers.IO) {
                val cached = copySound(uri, sizeLimit) ?: return@async null
                try {
                    account.send(TdApi.AddSavedNotificationSound(TdApi.InputFileLocal(cached.absolutePath)))
                } finally {
                    cached.parentFile?.deleteRecursively()
                }
            }
            when (val result = upload.await()) {
                null -> toast(string(R.string.notifications_sound_invalid))
                is Result.Failure -> throw LoadFailed(result.error.message)
                is Result.Success -> Unit
            }
        }
    }

    private fun copySound(uri: Uri, limit: Long): File? {
        val resolver = application.contentResolver
        var folder: File? = null
        try {
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
                ?.substringAfterLast('/')?.takeIf { it.isNotBlank() && it !in setOf(".", "..") }
                ?: "sound"
            val temporary = java.nio.file.Files.createTempDirectory(application.cacheDir.toPath(), "notification-").toFile()
            folder = temporary
            val destination = File(temporary, name)
            resolver.openInputStream(uri)?.use { input ->
                destination.outputStream().use { output ->
                    // Read one extra byte to detect oversized files without copying the whole document.
                    val buffer = ByteArray(8192)
                    var remaining = limit + 1
                    while (remaining > 0) {
                        val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                        if (count < 0) return destination.also { folder = null }
                        output.write(buffer, 0, count)
                        remaining -= count
                    }
                }
            }
        } catch (_: IOException) {
            // The document may have been moved or revoked since the picker displayed it.
        } catch (_: SecurityException) {
            // A document provider may revoke access to the selected URI.
        } finally {
            folder?.deleteRecursively()
        }
        return null
    }

    private fun previewSound(sound: TdApi.NotificationSound) {
        val account = client
        preview {
            val accountId = application.accountsRepository.idFor(account) ?: return@preview null
            application.telegramNotifications.sounds.previewSource(accountId, sound, account)
        }
    }

    private fun preview(resolve: suspend () -> HapticAudioSource?) {
        stopPreview()
        previewJob = owner.lifecycleScope.launch {
            try {
                val source = withContext(Dispatchers.IO) { resolve() } ?: throw IOException("Sound is unavailable")
                val haptics = application.audioHaptics
                val effect = haptics.previewEffect(source) { toast(string(R.string.haptics_preparing)) }
                if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch

                val player = MediaPlayer()
                previewPlayer = player
                player.setOnPreparedListener {
                    if (previewPlayer === it) {
                        try {
                            it.start()
                            effect?.let { haptics.vibrator.playNotificationPreview(player, it) }
                        } catch (_: IllegalStateException) {
                            previewFailed()
                        }
                    }
                }
                player.setOnCompletionListener { if (previewPlayer === it) releasePlayer() }
                player.setOnErrorListener { failedPlayer, _, _ ->
                    if (previewPlayer === failedPlayer) previewFailed()
                    true
                }
                player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                player.setDataSource(application, source.uri)
                player.prepareAsync()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                previewFailed()
            } catch (_: SecurityException) {
                previewFailed()
            } catch (_: IllegalArgumentException) {
                previewFailed()
            } catch (_: IllegalStateException) {
                previewFailed()
            }
        }
    }

    private fun previewFailed() {
        releasePlayer()
        toast(string(R.string.notifications_sound_invalid))
    }

    private fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        releasePlayer()
    }

    private fun releasePlayer() {
        val player = previewPlayer ?: return
        previewPlayer = null
        application.audioHaptics.vibrator.stop(player)
        player.release()
    }

    private suspend fun soundLimits(): Pair<Long, Long> {
        val duration = client.send(TdApi.GetOption("notification_sound_duration_max")).getOrNull()
        val size = client.send(TdApi.GetOption("notification_sound_size_max")).getOrNull()
        return ((duration as? TdApi.OptionValueInteger)?.value ?: 5L) to
            ((size as? TdApi.OptionValueInteger)?.value ?: 307_200L)
    }

    private suspend fun savedSounds() =
        client.send(TdApi.GetSavedNotificationSounds()).getOrNull()?.notificationSounds?.toList().orEmpty()

    private fun TdApi.NotificationSound.label(): String = title.ifBlank {
        data.ifBlank { string(R.string.notifications_sound_unknown, id) }
    }

    private fun soundName(value: Long?, sounds: List<TdApi.NotificationSound>): String = when (value) {
        null -> string(R.string.notifications_default)
        -1L -> string(R.string.notifications_sound_default)
        0L -> string(R.string.notifications_sound_none)
        else -> sounds.firstOrNull { it.id == value }?.label()
            ?: string(R.string.notifications_sound_unknown, value)
    }

    private fun soundChoice(
        group: PreferenceGroup,
        @StringRes title: Int,
        current: Long?,
        sounds: List<TdApi.NotificationSound>,
        inherit: Boolean = false,
        change: (Long?) -> Unit,
    ) {
        val ids = buildList {
            if (inherit) add(null)
            add(-1L)
            add(0L)
            addAll(sounds.map { it.id })
        }
        choice(group, title, current, ids.map { it to soundName(it, sounds) }, soundName(current, sounds)) { value ->
            change(value)
            if (value != null && sounds.any { it.id == value }) {
                val account = client
                val saving = writeJob
                application.backgroundScope.launch(Dispatchers.IO) {
                    saving?.join()
                    val accountId = application.accountsRepository.idFor(account) ?: return@launch
                    application.telegramNotifications.prepareSound(accountId, value, account)
                }
            }
        }
    }

    private fun muteOptions(current: Int?, inherit: Boolean = false): List<Pair<Int?, String>> = buildList {
        if (inherit) add(null to string(R.string.notifications_default))
        add(0 to string(R.string.notifications_on))
        add(1_800 to string(R.string.notifications_mute_minute))
        add(3_600 to string(R.string.notifications_mute_hour))
        add(28_800 to string(R.string.notifications_mute_eight_hours))
        add(86_400 to string(R.string.notifications_mute_day))
        add(604_800 to string(R.string.notifications_mute_week))
        add(Int.MAX_VALUE to string(R.string.notifications_forever))
    }.let { options ->
        if (current == null || options.any { it.first == current }) options
        else if (current >= 366 * 86_400) options.dropLast(1) + (current to muteSummary(current))
        else options + (current to muteSummary(current))
    }

    private fun muteSummary(value: Int?): String = when {
        value == null -> string(R.string.notifications_default)
        value == 0 -> string(R.string.notifications_on)
        value >= 366 * 86_400 -> string(R.string.notifications_forever)
        else -> string(R.string.notifications_muted_for, DateUtils.formatElapsedTime(value.toLong()))
    }

    private fun category(@StringRes title: Int) = PreferenceCategory(activity).apply {
        setTitle(title)
        screen.addPreference(this)
    }

    private fun row(group: PreferenceGroup, @StringRes title: Int, summary: String? = null, click: () -> Unit) =
        action(group, string(title), summary, click)

    private fun action(group: PreferenceGroup, label: CharSequence, detail: String? = null, click: () -> Unit) {
        group.addPreference(SettingsActionPreference(activity).apply {
            title = label
            summary = detail
            isPersistent = false
            widgetLayoutResource = R.layout.settings_section_arrow
            setOnPreferenceClickListener { click(); true }
        })
    }

    private fun info(group: PreferenceGroup, @StringRes title: Int) {
        group.addPreference(SettingsActionPreference(activity).apply {
            setTitle(title)
            isSelectable = false
            isPersistent = false
        })
    }

    private fun toggle(
        group: PreferenceGroup,
        @StringRes title: Int,
        checked: Boolean,
        detail: Int? = null,
        change: (Boolean) -> Unit,
    ) {
        group.addPreference(SettingsSwitchPreference(activity).apply {
            setTitle(title)
            detail?.let(::setSummary)
            isPersistent = false
            isChecked = checked
            setOnPreferenceChangeListener { _, value -> change(value as Boolean); false }
        })
    }

    private fun <T> choice(
        group: PreferenceGroup,
        @StringRes title: Int,
        value: T,
        options: List<Pair<T, String>>,
        detail: String? = options.firstOrNull { it.first == value }?.second,
        change: (T) -> Unit,
    ) {
        row(group, title, detail) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setSingleChoiceItems(options.map { it.second }.toTypedArray(), options.indexOfFirst { it.first == value }) { dialog, index ->
                    dialog.dismiss()
                    if (value != options[index].first) change(options[index].first)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun confirm(@StringRes title: Int, message: String, confirmed: () -> Unit) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> confirmed() }
            .show()
    }

    private fun write(block: suspend () -> Unit) {
        if (writeJob?.isActive == true) return
        writeJob = owner.lifecycleScope.launch {
            content.isEnabled = false
            try {
                block()
            } catch (error: LoadFailed) {
                toast(string(R.string.notifications_save_failed, error.message.orEmpty()))
            } finally {
                content.isEnabled = true
                refreshes.trySend(Unit)
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()

    private fun string(@StringRes id: Int, vararg arguments: Any): String = activity.getString(id, *arguments)

    private suspend fun <T : TdApi.Object> send(function: TdApi.Function<T>): T =
        when (val result = withTimeoutOrNull(15_000) { client.send(function) }) {
            is Result.Success -> result.data
            is Result.Failure -> throw LoadFailed(result.error.message)
            null -> throw LoadFailed()
        }

    private class LoadFailed(message: String? = null) : Exception(message)

    private enum class StoryDelivery { FREQUENT, ON, OFF }

    private data class BooleanOption(val name: String, val title: Int, val inverted: Boolean = false, val summary: Int? = null)
    private data class IntegerOption(val name: String, val title: Int, val minimum: Long, val default: Long)

    private fun TdApi.NotificationSettingsScope.title(): Int = when (this) {
        is TdApi.NotificationSettingsScopePrivateChats -> R.string.notifications_private
        is TdApi.NotificationSettingsScopeGroupChats -> R.string.notifications_groups
        else -> R.string.notifications_channels
    }

    private companion object {
        const val STORIES = "notifications_stories"
        const val REACTIONS = "notifications_reactions"
        const val EXCEPTIONS = "notifications_exceptions"
        const val STORY_EXCEPTIONS = "notifications_story_exceptions"
        const val CHAT = "notifications_chat"
        const val STORY_CHAT = "notifications_story_chat"
        const val TOPICS = "notifications_topics"
        const val TOPIC = "notifications_topic"
        const val COMMUNITIES = "notifications_communities"
        const val COMMUNITY = "notifications_community"
        const val SOUNDS = "notifications_sounds"
        const val OTHER = "notifications_other"

        val CHAT_PAGES = setOf(CHAT, STORY_CHAT)
        val SCOPE_PAGES = mapOf(
            "notifications_private" to TdApi.NotificationSettingsScopePrivateChats(),
            "notifications_groups" to TdApi.NotificationSettingsScopeGroupChats(),
            "notifications_channels" to TdApi.NotificationSettingsScopeChannelChats(),
        )
        val BOOLEAN_OPTIONS = listOf(
            BooleanOption("disable_contact_registered_notifications", R.string.notifications_contacts_joined, inverted = true),
            BooleanOption("disable_sent_scheduled_message_notifications", R.string.notifications_scheduled_sent, inverted = true),
            BooleanOption("process_pinned_messages_as_mentions", R.string.notifications_pinned_as_mentions,
                summary = R.string.notifications_pinned_as_mentions_summary),
            BooleanOption("ignore_default_disable_notification", R.string.notifications_ignore_silent_default),
        )
        val INTEGER_OPTIONS = listOf(
            IntegerOption(TelegramClient.NOTIFICATION_GROUP_COUNT, R.string.notifications_group_limit, 0, 20),
            IntegerOption("notification_group_size_max", R.string.notifications_group_size, 1, 10),
        )
        val OPTION_NAMES = (BOOLEAN_OPTIONS.map { it.name } + INTEGER_OPTIONS.map { it.name }).toSet()
    }
}
