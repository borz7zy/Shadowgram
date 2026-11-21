/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.composer

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil3.dispose
import coil3.load
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.emoji.EmojiData
import com.github.borz7zy.shadowgram.telegram.StickersStore
import com.github.borz7zy.shadowgram.ui.views.StickerView
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The emoji and sticker keyboard, laid out like VK's: one pager whose first page is the emoji,
 * then recent stickers, GIFs and every installed set; their icons sit in a strip at the bottom
 * that slides away while a page scrolls down and comes back when it scrolls up.
 */
class StickerKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    /** An icon of the strip. */
    sealed interface Tab {
        val id: Long

        data class Icon(override val id: Long, val icon: Int) : Tab
        data class Pack(override val id: Long, val cover: StickersStore.Sticker?) : Tab
    }

    var listener: KeyboardListener? = null
        set(value) {
            field = value
            pagesAdapter = value?.let { PagesAdapter(it, ::onPageScrolled) }
            pager.adapter = pagesAdapter
        }

    // Only the page on screen is attached: stickers and GIFs of the neighbours would keep
    // decoding and uploading frames nobody sees
    private val pager = ViewPager2(context)
    private val tabsHeight = (TABS_HEIGHT_DP * resources.displayMetrics.density).toInt()
    private val tabsAdapter = TabsAdapter()
    private val tabs = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        adapter = tabsAdapter
        itemAnimator = null
        setBackgroundColor(
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainer)
        )
    }
    private var pagesAdapter: PagesAdapter? = null
    private var areTabsShown = true

    private var selected = 0
        set(value) {
            val old = field
            field = value
            tabsAdapter.notifyItemChanged(old)
            tabsAdapter.notifyItemChanged(value)
            tabs.smoothScrollToPosition(value)
        }

    init {
        addView(pager, LayoutParams(-1, -1))
        addView(tabs, LayoutParams(-1, tabsHeight, Gravity.BOTTOM))

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                selected = position
                showTabs(true)
            }
        })
    }

    /**
     * @param snapshot The account's stickers, null while loading
     * @param foundGifs GIFs found for the search, null while it's empty
     */
    suspend fun setStickers(snapshot: StickersStore.Snapshot?, foundGifs: List<StickersStore.Gif>?) {
        val adapter = pagesAdapter ?: return
        val resources = resources
        val recentEmoji = RecentEmoji.get(context.applicationContext)
        val (pages, tabs) = withContext(Dispatchers.Default) {
            val emoji = buildEmojiPage(
                resources.getStringArray(R.array.emoji_categories),
                recentEmoji,
                snapshot?.emojiPacks?.takeIf { snapshot.isPremium }.orEmpty(),
            )
            val recentItems = buildList {
                snapshot?.favorite?.takeIf { it.isNotEmpty() }?.let { favorite ->
                    add(GridItem.Header(resources.getString(R.string.keyboard_favorite)))
                    favorite.mapTo(this) { GridItem.Sticker(it, 0) }
                }
                snapshot?.recent?.takeIf { it.isNotEmpty() }?.let { recent ->
                    add(GridItem.Header(resources.getString(R.string.keyboard_recent)))
                    recent.mapTo(this) { GridItem.Sticker(it, 1) }
                }
            }

            val packs = snapshot?.packs.orEmpty()
            val pages = listOf(
                emoji,
                KeyboardPage.Stickers(KeyboardPage.RECENT_ID, recentItems),
                KeyboardPage.Gifs((foundGifs ?: snapshot?.gifs.orEmpty()).map { GridItem.Gif(it) }),
            ) + packs.map { pack ->
                KeyboardPage.Stickers(
                    pack.id,
                    listOf(GridItem.Header(pack.title)) + pack.stickers.map { GridItem.Sticker(it) },
                )
            }
            val tabs = listOf(
                Tab.Icon(KeyboardPage.EMOJI_ID, R.drawable.ic_mood),
                Tab.Icon(KeyboardPage.RECENT_ID, R.drawable.ic_schedule),
                Tab.Icon(KeyboardPage.GIFS_ID, R.drawable.ic_gif),
            ) + packs.map { Tab.Pack(it.id, it.stickers.firstOrNull()) }
            pages to tabs
        }
        if (adapter !== pagesAdapter) return
        adapter.submitList(pages)
        tabsAdapter.submitList(tabs)
    }

    private fun buildEmojiPage(
        titles: Array<String>,
        recent: List<String>,
        customPacks: List<StickersStore.Pack>,
    ): KeyboardPage.Emoji {
        val headers = mutableMapOf<Int, Int>()
        val items = buildList {
            if (recent.isNotEmpty()) {
                headers[0] = size
                add(GridItem.Header(titles[0]))
                recent.mapTo(this) { GridItem.EmojiCell(it) }
            }
            EmojiData.dataColored.forEachIndexed { index, codes ->
                headers[index + 1] = size
                add(GridItem.Header(titles[index + 1]))
                codes.mapTo(this) { GridItem.EmojiCell(it) }
            }
            customPacks.forEach { pack ->
                add(GridItem.Header(pack.title))
                pack.stickers.mapTo(this) { GridItem.Sticker(it, section = 2) }
            }
        }
        return KeyboardPage.Emoji(items, headers)
    }

    private fun onPageScrolled(dy: Int) {
        when {
            dy > SCROLL_THRESHOLD -> showTabs(false)
            dy < -SCROLL_THRESHOLD -> showTabs(true)
        }
    }

    private fun showTabs(show: Boolean) {
        if (show == areTabsShown) {
            return
        }
        areTabsShown = show
        tabs.animate()
            .translationY(if (show) 0f else tabsHeight.toFloat())
            .setInterpolator(interpolator)
            .setDuration(TABS_ANIMATION_MS)
            .start()
    }

    private inner class TabsAdapter : ListAdapter<Tab, TabHolder>(TabDiff) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = TabHolder(
            FrameLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(tabsHeight + tabsHeight / 3, tabsHeight)
            }
        )

        override fun onBindViewHolder(holder: TabHolder, position: Int) =
            holder.bind(getItem(position), position == selected)
    }

    private inner class TabHolder(view: FrameLayout) : RecyclerView.ViewHolder(view) {
        private val padding = tabsHeight / 6
        private val selection = View(view.context).apply {
            background = context.getDrawable(R.drawable.bg_chat_badge)
            backgroundTintList = ColorStateList.valueOf(
                MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondaryContainer)
            )
        }
        private val icon = ImageView(view.context).apply {
            scaleType = ImageView.ScaleType.CENTER
            imageTintList = ColorStateList.valueOf(
                MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
            )
        }
        private val image = ImageView(view.context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(padding, padding, padding, padding)
        }
        private val sticker = StickerView(view.context).apply {
            setPadding(padding, padding, padding, padding)
        }
        private val cross = ImageView(view.context).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(Color.RED)
        }

        init {
            val inset = tabsHeight / 10
            view.addView(selection, LayoutParams(-1, -1).apply { setMargins(inset, inset, inset, inset) })
            view.addView(icon, LayoutParams(-1, -1))
            view.addView(image, LayoutParams(-1, -1))
            view.addView(sticker, LayoutParams(-1, -1))
            view.addView(cross, LayoutParams(tabsHeight / 3, tabsHeight / 3, Gravity.TOP or Gravity.END))
            view.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    pager.setCurrentItem(position, true)
                }
            }
        }

        fun bind(tab: Tab, isSelected: Boolean) {
            selection.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
            val cover = (tab as? Tab.Pack)?.cover
            val file = cover?.path?.let(::File)
            if (cover != null && file == null && cover.format != StickersStore.Format.UNSUPPORTED) {
                listener?.onDownload(cover.fileId)
            }

            when (tab) {
                is Tab.Icon -> icon.setImageResource(tab.icon)
                is Tab.Pack -> icon.setImageDrawable(null)
            }
            cross.visibility = if (cover?.format == StickersStore.Format.UNSUPPORTED) View.VISIBLE else View.GONE

            val isAnimated = cover?.format == StickersStore.Format.ANIMATED && file != null
            sticker.visibility = if (isAnimated) View.VISIBLE else View.GONE
            sticker.setSticker(file.takeIf { isAnimated }, tabsHeight)

            when {
                cover?.format == StickersStore.Format.WEBP && file != null ->
                    image.load(file) { size(tabsHeight, tabsHeight) }

                else -> {
                    image.dispose()
                    image.setImageDrawable(null)
                }
            }
        }
    }

    private object TabDiff : DiffUtil.ItemCallback<Tab>() {
        override fun areItemsTheSame(oldItem: Tab, newItem: Tab) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Tab, newItem: Tab) = oldItem == newItem
        override fun getChangePayload(oldItem: Tab, newItem: Tab) = Unit
    }

    companion object {
        const val TABS_HEIGHT_DP = 48

        private const val TABS_ANIMATION_MS = 200L
        private const val SCROLL_THRESHOLD = 4
        private val interpolator = AccelerateDecelerateInterpolator()
    }
}
