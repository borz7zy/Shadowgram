/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.composer

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.edit
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import coil3.dispose
import coil3.load
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.emoji.Emoji
import com.github.borz7zy.shadowgram.telegram.StickersStore
import com.github.borz7zy.shadowgram.ui.UiPreferences
import com.github.borz7zy.shadowgram.ui.views.AnimationView
import com.github.borz7zy.shadowgram.ui.views.StickerView
import com.google.android.material.textfield.TextInputEditText
import java.io.File

/** A page of the sticker keyboard, see [StickerKeyboardView]. */
sealed interface KeyboardPage {
    val id: Long

    /**
     * @param headers The category jump targets in [items]
     */
    data class Emoji(val items: List<GridItem>, val headers: Map<Int, Int>) : KeyboardPage {
        override val id = EMOJI_ID
    }

    /** Recent and favorite stickers, or one installed set. */
    data class Stickers(override val id: Long, val items: List<GridItem>) : KeyboardPage

    /** Saved GIFs or the results for the current search. */
    data class Gifs(val items: List<GridItem>) : KeyboardPage {
        override val id = GIFS_ID
    }

    companion object {
        const val EMOJI_ID = -1L
        const val RECENT_ID = -2L
        const val GIFS_ID = -3L
    }
}

/** A cell of a keyboard page. */
sealed interface GridItem {
    val key: Any

    data class Header(val title: String) : GridItem {
        override val key get() = "header:$title"
    }

    data class Sticker(val sticker: StickersStore.Sticker, val section: Int = 0) : GridItem {
        override val key get() = "sticker:$section:${sticker.fileId}"
    }

    data class EmojiCell(val code: String) : GridItem {
        override val key get() = code
    }

    data class Gif(val gif: StickersStore.Gif) : GridItem {
        override val key get() = gif.fileId
    }
}

/** What the keyboard reports, the chat acts on it. */
interface KeyboardListener {
    fun onEmoji(code: String)

    /** A custom emoji of the emoji page, [StickersStore.Sticker.customEmojiId] set. */
    fun onCustomEmoji(sticker: StickersStore.Sticker)
    fun onBackspace()
    fun onSticker(sticker: StickersStore.Sticker)
    fun onGif(gif: StickersStore.Gif)
    fun onDownload(fileId: Int)
    fun onGifQuery(query: String)
}

/** Square cells, whatever the width the grid gives them. */
class SquareFrameLayout(context: Context) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
}

/** Cells as tall as their content's aspect ratio makes them at the width the grid gives. */
class AspectFrameLayout(context: Context) : FrameLayout(context) {
    /** Height over width. */
    var ratio = 1f
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec((width * ratio).toInt(), MeasureSpec.EXACTLY),
        )
    }
}

private object GridItemDiff : DiffUtil.ItemCallback<GridItem>() {
    override fun areItemsTheSame(oldItem: GridItem, newItem: GridItem) = oldItem.key == newItem.key
    override fun areContentsTheSame(oldItem: GridItem, newItem: GridItem) = oldItem == newItem

    // A downloaded file only changes the cell's content, no crossfade
    override fun getChangePayload(oldItem: GridItem, newItem: GridItem) = Unit
}

/**
 * The cells of every page: headers, stickers, emoji and GIFs.
 */
class GridAdapter(private val listener: KeyboardListener) :
    ListAdapter<GridItem, RecyclerView.ViewHolder>(GridItemDiff) {

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is GridItem.Header -> TYPE_HEADER
        is GridItem.Sticker -> TYPE_STICKER
        is GridItem.EmojiCell -> TYPE_EMOJI
        is GridItem.Gif -> TYPE_GIF
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val context = parent.context
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(TextView(context).apply {
                TextViewCompat.setTextAppearance(
                    this, com.google.android.material.R.style.TextAppearance_Material3_LabelLarge
                )
                isAllCaps = true
                val padding = dp(context, 12)
                setPadding(padding, padding, padding, padding / 2)
            })

            TYPE_STICKER -> StickerHolder(SquareFrameLayout(context))
            TYPE_EMOJI -> EmojiHolder(SquareFrameLayout(context))
            else -> GifHolder(AspectFrameLayout(context))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is GridItem.Header -> (holder as HeaderHolder).view.text = item.title
            is GridItem.Sticker -> (holder as StickerHolder).bind(item.sticker)
            is GridItem.EmojiCell -> (holder as EmojiHolder).bind(item.code)
            is GridItem.Gif -> (holder as GifHolder).bind(item.gif)
        }
    }

    /** The grid is scrolling: GIFs scrolling in wait for it to settle before playing. */
    var isScrolling = false
        private set

    fun setScrolling(recyclerView: RecyclerView, isScrolling: Boolean) {
        this.isScrolling = isScrolling
        recyclerView.children.forEach {
            (recyclerView.getChildViewHolder(it) as? GifHolder)?.setDeferred(isScrolling)
        }
    }

    /** The sticker shown by [view], for the preview. */
    fun stickerOf(view: View) = (view.tag as? StickersStore.Sticker)

    private class HeaderHolder(val view: TextView) : RecyclerView.ViewHolder(view)

    private inner class StickerHolder(view: FrameLayout) : RecyclerView.ViewHolder(view) {
        private val padding = dp(view.context, 6)
        private val image = ImageView(view.context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(padding, padding, padding, padding)
        }
        private val sticker = StickerView(view.context).apply {
            setPadding(padding, padding, padding, padding)
        }
        private val emoji = TextView(view.context).apply {
            gravity = Gravity.CENTER
            textSize = 28f
        }
        private val cross = ImageView(view.context).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(Color.RED)
        }

        init {
            view.addView(emoji, FrameLayout.LayoutParams(-1, -1))
            view.addView(image, FrameLayout.LayoutParams(-1, -1))
            view.addView(sticker, FrameLayout.LayoutParams(-1, -1))
            view.addView(cross, FrameLayout.LayoutParams(dp(view.context, 20), dp(view.context, 20), Gravity.TOP or Gravity.END))
            view.setBackgroundResource(android.R.drawable.list_selector_background)
            view.setOnClickListener {
                val sticker = view.tag as? StickersStore.Sticker ?: return@setOnClickListener
                when (sticker.customEmojiId) {
                    0L -> listener.onSticker(sticker)
                    else -> listener.onCustomEmoji(sticker)
                }
            }
        }

        fun bind(item: StickersStore.Sticker) {
            itemView.tag = item
            val file = item.path?.let(::File)
            if (file == null && item.format != StickersStore.Format.UNSUPPORTED) {
                listener.onDownload(item.fileId)
            }

            // The emoji stands in until the file is there, and for what can't be shown
            emoji.text = item.emoji
            emoji.isVisible = file == null || item.format == StickersStore.Format.UNSUPPORTED
            cross.isVisible = item.format == StickersStore.Format.UNSUPPORTED

            val isAnimated = item.format == StickersStore.Format.ANIMATED && file != null
            sticker.isVisible = isAnimated
            sticker.setSticker(file.takeIf { isAnimated }, dp(itemView.context, STICKER_DP))

            val isWebp = item.format == StickersStore.Format.WEBP && file != null
            image.isVisible = isWebp
            when {
                isWebp -> image.load(file) {
                    size(dp(itemView.context, STICKER_DP), dp(itemView.context, STICKER_DP))
                }

                else -> {
                    image.dispose()
                    image.setImageDrawable(null)
                }
            }
        }
    }

    private inner class EmojiHolder(view: FrameLayout) : RecyclerView.ViewHolder(view) {
        private val image = ImageView(view.context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            val padding = dp(view.context, 6)
            setPadding(padding, padding, padding, padding)
        }
        private var code = ""

        init {
            view.addView(image, FrameLayout.LayoutParams(-1, -1))
            view.setBackgroundResource(android.R.drawable.list_selector_background)
            view.setOnClickListener { listener.onEmoji(code) }
        }

        fun bind(code: String) {
            this.code = code
            image.setImageDrawable(Emoji.getEmojiBigDrawable(code))
        }
    }

    inner class GifHolder(view: AspectFrameLayout) : RecyclerView.ViewHolder(view) {
        private val margin = dp(view.context, 2)
        private val still = ImageView(view.context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val animation = AnimationView(view.context)
        private var gif: StickersStore.Gif? = null

        init {
            val params = FrameLayout.LayoutParams(-1, -1).apply { setMargins(margin, margin, margin, margin) }
            view.addView(still, params)
            view.addView(animation, FrameLayout.LayoutParams(params))
            view.setOnClickListener { gif?.let(listener::onGif) }
        }

        fun setDeferred(isDeferred: Boolean) {
            animation.isPlaybackDeferred = isDeferred
        }

        fun bind(item: StickersStore.Gif) {
            gif = item
            // As shaped as the GIF, within reason so that one can't fill the whole page
            (itemView as AspectFrameLayout).ratio = when {
                item.width > 0 && item.height > 0 ->
                    (item.height.toFloat() / item.width).coerceIn(MIN_GIF_RATIO, MAX_GIF_RATIO)

                else -> 1f
            }
            if (item.path == null) {
                listener.onDownload(item.fileId)
            }
            if (item.thumbnailPath == null) {
                item.thumbnailFileId?.let(listener::onDownload)
            }
            animation.isPlaybackDeferred = isScrolling
            animation.setVideo(item.path?.let(::File))
            when (val data = item.thumbnailPath?.let(::File) ?: item.minithumbnail) {
                null -> {
                    still.dispose()
                    still.setImageDrawable(null)
                }

                else -> still.load(data)
            }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_STICKER = 1
        private const val TYPE_EMOJI = 2
        private const val TYPE_GIF = 3

        private const val STICKER_DP = 96
        private const val MIN_GIF_RATIO = 0.4f
        private const val MAX_GIF_RATIO = 2f

        fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    }
}

/**
 * The pages of the keyboard, one RecyclerView each. Updates reach a page's grid as a diff, the
 * page itself is kept.
 *
 * @param onScrolled A page scrolled, the tabs hide while going down and show while going up
 */
class PagesAdapter(
    private val listener: KeyboardListener,
    private val onScrolled: (dy: Int) -> Unit,
) : ListAdapter<KeyboardPage, PagesAdapter.PageHolder>(PageDiff) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int) = getItem(position).id

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is KeyboardPage.Emoji -> TYPE_EMOJI
        is KeyboardPage.Stickers -> TYPE_STICKERS
        is KeyboardPage.Gifs -> TYPE_GIFS
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = when (viewType) {
        TYPE_EMOJI -> EmojiPageHolder(parent)
        TYPE_GIFS -> GifsPageHolder(parent)
        else -> StickersPageHolder(parent)
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) = holder.bind(getItem(position))

    abstract class PageHolder(view: View) : RecyclerView.ViewHolder(view) {
        abstract fun bind(page: KeyboardPage)
    }

    private fun grid(context: Context, columns: Int, adapter: GridAdapter) =
        RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, columns).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int) =
                        if (adapter.currentList.getOrNull(position) is GridItem.Header) columns else 1
                }
            }
            this.adapter = adapter
            itemAnimator = null
            clipToPadding = false
            // Room for the tabs, which float over the bottom
            setPadding(0, 0, 0, GridAdapter.dp(context, StickerKeyboardView.TABS_HEIGHT_DP))
            layoutParams = ViewGroup.LayoutParams(-1, -1)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = onScrolled(dy)

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) =
                    adapter.setScrolling(recyclerView, newState != RecyclerView.SCROLL_STATE_IDLE)
            })
        }

    private fun columns(context: Context, cellDp: Int, min: Int): Int {
        val metrics = context.resources.displayMetrics
        return (metrics.widthPixels / metrics.density / cellDp).toInt().coerceAtLeast(min)
    }

    inner class StickersPageHolder(parent: ViewGroup) : PageHolder(FrameLayout(parent.context).apply {
        layoutParams = ViewGroup.LayoutParams(-1, -1)
    }) {
        private val adapter = GridAdapter(listener)
        private val recyclerView = grid(parent.context, columns(parent.context, STICKER_CELL_DP, 4), adapter)

        init {
            (itemView as FrameLayout).addView(recyclerView)
            StickerPreview(recyclerView, adapter::stickerOf)
        }

        override fun bind(page: KeyboardPage) {
            adapter.submitList((page as KeyboardPage.Stickers).items)
        }
    }

    inner class GifsPageHolder(parent: ViewGroup) : PageHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.keyboard_gifs_page, parent, false)
    ) {
        private val adapter = GridAdapter(listener)
        // Cells of different heights, packed column by column like Google Messages' GIFs
        private val recyclerView = grid(parent.context, 1, adapter).apply {
            layoutManager = StaggeredGridLayoutManager(
                columns(parent.context, GIF_CELL_DP, 2), StaggeredGridLayoutManager.VERTICAL
            )
        }

        init {
            itemView.findViewById<FrameLayout>(R.id.gifsFrameLayout).addView(recyclerView)
            itemView.findViewById<TextInputEditText>(R.id.gifSearchEditText)
                .doAfterTextChanged { listener.onGifQuery(it?.toString().orEmpty()) }
        }

        override fun bind(page: KeyboardPage) {
            adapter.submitList((page as KeyboardPage.Gifs).items)
        }
    }

    /**
     * Emoji by category, the categories and backspace in a bar over the grid like Telegram's.
     */
    inner class EmojiPageHolder(parent: ViewGroup) : PageHolder(LinearLayout(parent.context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(-1, -1)
    }) {
        private val context = parent.context
        private val adapter = GridAdapter(listener)
        private val recyclerView = grid(context, columns(context, EMOJI_CELL_DP, 7), adapter)
        private var headers = emptyMap<Int, Int>()

        /** Redraws the cells whose sprite just loaded; Emoji only keeps it weakly. */
        private val invalidator = Runnable {
            // The cells' images, a parent's invalidate doesn't redraw its children
            recyclerView.children.forEach { cell ->
                (cell as? ViewGroup)?.children?.forEach(View::invalidate)
            }
        }

        init {
            val bar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val icons = listOf(R.drawable.ic_schedule) + CATEGORY_ICONS
            icons.forEachIndexed { index, icon ->
                bar.addView(barButton(icon) {
                    headers[index]?.let {
                        (recyclerView.layoutManager as GridLayoutManager).scrollToPositionWithOffset(it, 0)
                    }
                }, LinearLayout.LayoutParams(0, -1, 1f))
            }
            bar.addView(barButton(R.drawable.ic_backspace) { listener.onBackspace() },
                LinearLayout.LayoutParams(0, -1, 1f))

            val root = itemView as LinearLayout
            root.addView(bar, LinearLayout.LayoutParams(-1, GridAdapter.dp(context, 44)))
            root.addView(recyclerView, LinearLayout.LayoutParams(-1, 0, 1f))
            Emoji.registerInvalidator(invalidator)
        }

        private fun barButton(@DrawableRes icon: Int, onClick: () -> Unit) = ImageView(context).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.CENTER
            imageTintList = android.content.res.ColorStateList.valueOf(
                com.google.android.material.color.MaterialColors.getColor(
                    this, com.google.android.material.R.attr.colorOnSurfaceVariant
                )
            )
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { onClick() }
        }

        override fun bind(page: KeyboardPage) {
            val emoji = page as KeyboardPage.Emoji
            adapter.submitList(emoji.items) { headers = emoji.headers }
        }
    }

    private object PageDiff : DiffUtil.ItemCallback<KeyboardPage>() {
        override fun areItemsTheSame(oldItem: KeyboardPage, newItem: KeyboardPage) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: KeyboardPage, newItem: KeyboardPage) = oldItem == newItem

        // The page stays, its grid diffs the new items
        override fun getChangePayload(oldItem: KeyboardPage, newItem: KeyboardPage) = Unit
    }

    companion object {
        private const val TYPE_EMOJI = 0
        private const val TYPE_STICKERS = 1
        private const val TYPE_GIFS = 2

        private const val STICKER_CELL_DP = 90
        private const val EMOJI_CELL_DP = 44
        private const val GIF_CELL_DP = 160

        /** Telegram's emoji categories, in EmojiData order. */
        private val CATEGORY_ICONS = listOf(
            R.drawable.ic_mood,
            R.drawable.ic_pets,
            R.drawable.ic_restaurant,
            R.drawable.ic_emoji_events,
            R.drawable.ic_travel,
            R.drawable.ic_lightbulb,
            R.drawable.ic_tag,
            R.drawable.ic_flag,
        )
    }
}

/** The emoji used last, newest first, kept per device. */
object RecentEmoji {
    private const val PREFERENCES = "emoji"
    private const val KEY = "recent"
    private const val MAX = 32

    private fun parse(value: String?): List<String> = value
        ?.split('\n')
        ?.filter { it.isNotEmpty() }
        .orEmpty()

    suspend fun get(context: Context): List<String> =
        UiPreferences.read(context, PREFERENCES) { parse(it.getString(KEY, null)) }

    fun add(context: Context, code: String) {
        UiPreferences.write(context, PREFERENCES) {
            val list = (listOf(code) + parse(it.getString(KEY, null)).filter { it != code }).take(MAX)
            it.edit(commit = true) { putString(KEY, list.joinToString("\n")) }
        }
    }
}
