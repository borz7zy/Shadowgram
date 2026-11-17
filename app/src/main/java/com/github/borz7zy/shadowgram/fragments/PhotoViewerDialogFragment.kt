/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.fragments

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.ContentValues
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentDialog
import androidx.activity.addCallback
import androidx.annotation.OptIn
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil3.load
import coil3.request.allowHardware
import coil3.request.placeholder
import coil3.size.Precision
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.ui.finishOverlayReturn
import com.github.borz7zy.shadowgram.ui.mediaTransitionViewport
import com.github.borz7zy.shadowgram.ext.getViewProperty
import com.github.borz7zy.shadowgram.playback.TdFileDataSource
import com.github.borz7zy.shadowgram.ui.viewer.ZoomableImageView
import com.github.borz7zy.shadowgram.viewmodels.PhotoViewerViewModel
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.motion.MotionUtils
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * The photos and videos of a message or a profile, VK style: the tapped one flies from its
 * picture to the screen, the others are a swipe away. Pinch or double tap to zoom; pull it up or
 * down to close, it flies back to where its picture is now; tap to hide and show the bars.
 *
 * A video plays over its still where the still is drawn, so it zooms and pulls away the same;
 * its controls are under the caption, like Google Photos. It streams while it downloads.
 */
@OptIn(UnstableApi::class)
class PhotoViewerDialogFragment : DialogFragment(), ZoomableImageView.Listener {
    // View models
    private val viewModel by viewModels<PhotoViewerViewModel>()
    private val anchor by activityViewModels<PhotoViewerViewModel.Anchor>()

    // Views
    private val backgroundView by getViewProperty<View>(R.id.backgroundView)
    private val bottomLinearLayout by getViewProperty<LinearLayout>(R.id.bottomLinearLayout)
    private val captionTextView by getViewProperty<TextView>(R.id.captionTextView)
    private val durationTextView by getViewProperty<TextView>(R.id.durationTextView)
    private val playPauseMaterialButton by getViewProperty<MaterialButton>(R.id.playPauseMaterialButton)
    private val positionTextView by getViewProperty<TextView>(R.id.positionTextView)
    private val rootFrameLayout by getViewProperty<FrameLayout>(R.id.rootFrameLayout)
    private val seekSlider by getViewProperty<Slider>(R.id.seekSlider)
    private val toolbar by getViewProperty<MaterialToolbar>(R.id.toolbar)
    private val transitionImageView by getViewProperty<ShapeableImageView>(R.id.transitionImageView)
    private val transitionClipLayout by getViewProperty<FrameLayout>(R.id.transitionClipLayout)
    private val videoControlsLinearLayout by getViewProperty<LinearLayout>(R.id.videoControlsLinearLayout)
    private val viewPager2 by getViewProperty<ViewPager2>(R.id.viewPager2)

    private val adapter = PagesAdapter()

    private var isUiShown = true
    private var isClosing = false
    private var transitionAnimator: ValueAnimator? = null
    private var cancelReturnHandoff: (() -> Unit)? = null
    private var isPositioned = false

    /** Done flying in: a video may start. */
    private var isOpened = false

    // Video
    private var player: ExoPlayer? = null

    /** The message whose video the player has, 0 for none. */
    private var playerMessageId = 0L
    private var hasFrame = false
    private var isSeeking = false

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = bindPlayPause()

        override fun onPlaybackStateChanged(playbackState: Int) {
            bindPlayPause()
            currentHolder()?.setBuffering(playbackState == Player.STATE_BUFFERING)
        }

        override fun onRenderedFirstFrame() {
            hasFrame = true
        }
    }

    override fun getTheme() = R.style.Theme_Shadowgram_PhotoViewer

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        (super.onCreateDialog(savedInstanceState) as ComponentDialog).apply {
            window?.let {
                // A protected chat keeps its photos out of screenshots too
                if (requireActivity().window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) {
                    it.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
                WindowCompat.setDecorFitsSystemWindows(it, false)
                it.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                // Full screen for the photo, a swipe from the edge brings the status bar back
                WindowCompat.getInsetsController(it, it.decorView).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.statusBars())
                }
            }
            onBackPressedDispatcher.addCallback(this@PhotoViewerDialogFragment) { close() }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_photo_viewer, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Insets: the bars stay off the system bars, the photos go under them
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            toolbar.updatePadding(left = insets.left, top = insets.top, right = insets.right)
            bottomLinearLayout.updatePadding(
                left = insets.left,
                right = insets.right,
                bottom = insets.bottom + view.resources.getDimensionPixelSize(R.dimen.photo_viewer_caption_padding),
            )
            windowInsets
        }

        toolbar.setNavigationOnClickListener { close() }
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.save -> save()
                R.id.more -> showGifMenu()
            }
            true
        }

        playPauseMaterialButton.setOnClickListener {
            val player = player ?: return@setOnClickListener
            when {
                player.isPlaying -> player.pause()
                else -> {
                    if (player.playbackState == Player.STATE_ENDED) {
                        player.seekTo(0)
                    }
                    player.play()
                }
            }
        }
        seekSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                positionTextView.text = formatTime((value * durationMs()).toLong())
            }
        }
        seekSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                isSeeking = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                isSeeking = false
                player?.seekTo((slider.value * durationMs()).toLong())
            }
        })

        viewPager2.adapter = adapter
        viewPager2.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                bindBars()
                notifyShown()
                // Its page may only be laid out next
                viewPager2.post { bindPlayer() }
            }
        })

        val frame = anchor.frame
        transitionImageView.setImageDrawable(anchor.drawable)
        // Recreated (rotated, process death): no bubble to fly from
        if (frame == null || savedInstanceState != null) {
            transitionImageView.isVisible = false
            backgroundView.alpha = 1f
            viewPager2.isVisible = true
            isOpened = true
            setUiShown(true, animate = false)
        } else {
            view.doOnPreDraw { open(frame) }
            // The chat hides its picture once this one shows it
            view.viewTreeObserver.registerFrameCommitCallback { notifyShown() }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collectLatest { state ->
                    adapter.submitList(state.photos) {
                        if (!isPositioned) {
                            val index = state.photos.indexOfFirst { it.messageId == viewModel.messageId }
                            if (index >= 0) {
                                isPositioned = true
                                viewPager2.setCurrentItem(index, false)
                            }
                        }
                        bindBars()
                    }
                }
            }
        }

        // The position, while there is a video
        viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                bindPosition()
                delay(POSITION_UPDATE_MS)
            }
        }
    }

    override fun onStop() {
        player?.pause()

        super.onStop()
    }

    override fun onDestroyView() {
        cancelReturnHandoff?.invoke()
        transitionAnimator?.cancel()
        transitionImageView.animate().cancel()
        anchor.restore?.invoke()
        player?.release()
        player = null
        playerMessageId = 0L
        viewPager2.adapter = null

        super.onDestroyView()
    }

    /** Tell the chat which photo this shows, it hides that picture of its own. */
    private fun notifyShown() {
        if (isClosing) return
        val photo = viewModel.state.value.photos.getOrNull(viewPager2.currentItem)
        setFragmentResult(REQUEST_KEY_SHOWN, bundleOf(KEY_MESSAGE_ID to (photo?.messageId ?: viewModel.messageId)))
    }

    /** The counter, who sent it and when, its caption, a video's controls. */
    private fun bindBars() {
        val state = viewModel.state.value
        val photo = state.photos.getOrNull(viewPager2.currentItem) ?: return
        toolbar.title = when {
            // One photo needs no counter
            state.total <= 1 -> null
            else -> getString(R.string.photo_viewer_counter, state.firstNumber + viewPager2.currentItem, state.total)
        }
        toolbar.subtitle = listOfNotNull(
            viewModel.senderName(photo.senderId),
            DateUtils.formatDateTime(
                requireContext(),
                photo.date * 1000L,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
            ),
        ).joinToString(", ")
        toolbar.menu.findItem(R.id.save)?.isVisible = photo.canBeSaved && !photo.isGif
        toolbar.menu.findItem(R.id.more)?.isVisible = photo.canBeSaved && photo.isGif
        captionTextView.text = photo.caption
        captionTextView.isVisible = photo.caption != null
        // GIFs and animated profile photos just loop
        val hasControls = photo.video?.isAnimation == false
        videoControlsLinearLayout.isVisible = hasControls
        bottomLinearLayout.isVisible = photo.caption != null || hasControls
    }

    /** The player on the shown page if it's a video, stopped otherwise. */
    private fun bindPlayer() {
        if (view == null || isClosing) {
            return
        }
        val photo = viewModel.state.value.photos.getOrNull(viewPager2.currentItem)
        val video = photo?.video
        val holder = currentHolder()
        if (video == null || holder == null) {
            player?.stop()
            player?.clearMediaItems()
            playerMessageId = 0L
            return
        }
        if (playerMessageId == photo.messageId) {
            return
        }

        playerMessageId = photo.messageId
        hasFrame = false
        val player = player ?: ExoPlayer.Builder(requireContext())
            .setMediaSourceFactory(DefaultMediaSourceFactory(TdFileDataSource.Factory(requireContext())))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .build()
            .also {
                it.addListener(playerListener)
                player = it
            }
        player.setVideoTextureView(holder.textureView)
        player.repeatMode = if (video.isAnimation) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.volume = if (video.isAnimation) 0f else 1f
        player.setMediaItem(MediaItem.fromUri(TdFileDataSource.uriOf(video.fileId)))
        player.prepare()
        // Played once it flew in
        player.playWhenReady = isOpened
        durationTextView.text = formatTime(video.duration * 1000L)
        seekSlider.value = 0f
        positionTextView.text = formatTime(0)
        bindPlayPause()
    }

    private fun bindPlayPause() {
        val isPlaying = player?.isPlaying == true
                || (player?.playWhenReady == true && player?.playbackState == Player.STATE_BUFFERING)
        playPauseMaterialButton.setIconResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow)
    }

    private fun bindPosition() {
        val player = player ?: return
        if (playerMessageId == 0L || isSeeking) {
            return
        }
        val duration = durationMs()
        durationTextView.text = formatTime(duration)
        positionTextView.text = formatTime(player.currentPosition)
        seekSlider.value = (player.currentPosition.toFloat() / duration.coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    /** As the player knows it, as Telegram said meanwhile. */
    private fun durationMs() = player?.duration?.takeIf { it != C.TIME_UNSET && it > 0 }
        ?: ((viewModel.state.value.photos.firstOrNull { it.messageId == playerMessageId }?.video?.duration ?: 0) * 1000L)

    private fun formatTime(ms: Long) = DateUtils.formatElapsedTime(ms / 1000)

    /** From the bubble to the screen, fitted. */
    private fun open(frame: PhotoViewerViewModel.Frame) {
        if (isClosing) return
        val source = frame.bounds.inWindow()
        val target = fitted(anchor.drawable?.intrinsicWidth ?: 1, anchor.drawable?.intrinsicHeight ?: 1)
        animateFrame(RectF(source), frame.corners, target, NO_CORNERS) {
            transitionImageView.isVisible = false
            viewPager2.isVisible = true
            setUiShown(true, animate = true)
            isOpened = true
            player?.play()
        }
        backgroundView.animate().alpha(1f).setDuration(duration()).start()
    }

    /**
     * Back to the photo's bubble where it is now, or away down when it's off screen, from
     * where the photo is drawn (dragged, zoomed). A video goes back with the frame it's at.
     */
    private fun close() {
        if (isClosing) {
            return
        }
        isClosing = true
        transitionAnimator?.cancel()

        val finish = {
            cancelReturnHandoff = finishOverlayReturn(anchor.restore?.invoke()) {
                setFragmentResult(REQUEST_KEY, Bundle())
                dismissAllowingStateLoss()
            }
        }
        if (view == null) {
            finish()
            return
        }
        val photo = viewModel.state.value.photos.getOrNull(viewPager2.currentItem)
        val holder = currentHolder()
        val page = holder?.image
        val drawable = if (isOpened) holder?.frame() ?: page?.drawable else transitionImageView.drawable
        if (drawable == null) {
            finish()
            return
        }

        player?.pause()
        val from = if (isOpened && page != null) page.displayRect() else RectF(
            transitionImageView.left.toFloat(), transitionImageView.top.toFloat(),
            transitionImageView.right.toFloat(), transitionImageView.bottom.toFloat(),
        )
        val size = RectF(0f, 0f, transitionImageView.width.toFloat(), transitionImageView.height.toFloat())
        val shape = transitionImageView.shapeAppearanceModel
        val fromCorners = if (isOpened) NO_CORNERS else floatArrayOf(
            shape.topLeftCornerSize.getCornerSize(size), shape.topRightCornerSize.getCornerSize(size),
            shape.bottomRightCornerSize.getCornerSize(size), shape.bottomLeftCornerSize.getCornerSize(size),
        )
        transitionImageView.setImageDrawable(drawable)
        transitionImageView.isVisible = true
        viewPager2.isVisible = false
        setUiShown(false, animate = true)
        backgroundView.animate().alpha(0f).setDuration(duration()).start()

        val target = anchor.locate?.invoke(photo?.messageId ?: viewModel.messageId)
        transitionClipLayout.clipBounds = target?.viewport?.inWindow()
        when (val destination = target?.takeIf { it.viewport == null || Rect.intersects(it.bounds, it.viewport) }) {
            null -> {
                layoutTransition(from, fromCorners)
                transitionImageView.animate()
                    .translationY(if (from.centerY() < rootFrameLayout.height / 2) -from.bottom else rootFrameLayout.height - from.top)
                    .alpha(0f)
                    .setDuration(duration())
                    .withEndAction { finish() }
                    .start()
            }

            else -> animateFrame(from, fromCorners, RectF(destination.bounds.inWindow()), destination.corners) { finish() }
        }
    }

    /**
     * Fly [transitionImageView] from [from] to [to], cropped like the bubble on the way, its
     * corners rounding from [fromCorners] to [toCorners].
     */
    private fun animateFrame(
        from: RectF,
        fromCorners: FloatArray,
        to: RectF,
        toCorners: FloatArray,
        onEnd: () -> Unit,
    ) {
        transitionAnimator?.cancel()
        layoutTransition(from, fromCorners)
        transitionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = duration()
            interpolator = MotionUtils.resolveThemeInterpolator(
                requireContext(),
                com.google.android.material.R.attr.motionEasingEmphasizedInterpolator,
                android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f),
            )
            addUpdateListener {
                val t = it.animatedValue as Float
                layoutTransition(
                    RectF(
                        from.left + (to.left - from.left) * t,
                        from.top + (to.top - from.top) * t,
                        from.right + (to.right - from.right) * t,
                        from.bottom + (to.bottom - from.bottom) * t,
                    ),
                    FloatArray(4) { i -> fromCorners[i] + (toCorners[i] - fromCorners[i]) * t },
                )
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!cancelled) onEnd()
                }
            })
            start()
        }
    }

    /**
     * Put [transitionImageView] at [rect]. Its layout params hold it there through the layout
     * passes that come meanwhile (the bars, the pager shown and hidden), laying it out at once
     * puts it there in this very frame.
     */
    private fun layoutTransition(rect: RectF, corners: FloatArray) {
        val left = rect.left.toInt()
        val top = rect.top.toInt()
        val right = rect.right.toInt()
        val bottom = rect.bottom.toInt()
        transitionImageView.updateLayoutParams<FrameLayout.LayoutParams> {
            width = right - left
            height = bottom - top
            leftMargin = left
            topMargin = top
        }
        transitionImageView.layout(left, top, right, bottom)
        transitionImageView.shapeAppearanceModel = ShapeAppearanceModel.builder()
            .setTopLeftCornerSize(corners[0])
            .setTopRightCornerSize(corners[1])
            .setBottomRightCornerSize(corners[2])
            .setBottomLeftCornerSize(corners[3])
            .build()
    }

    /** A picture this big fitted in the window, centered. */
    private fun fitted(width: Int, height: Int): RectF {
        val w = rootFrameLayout.width.toFloat()
        val h = rootFrameLayout.height.toFloat()
        val scale = minOf(w / width.coerceAtLeast(1), h / height.coerceAtLeast(1))
        val fw = width * scale
        val fh = height * scale
        return RectF((w - fw) / 2, (h - fh) / 2, (w + fw) / 2, (h + fh) / 2)
    }

    private fun Rect.inWindow(): Rect {
        val location = IntArray(2).also(rootFrameLayout::getLocationOnScreen)
        return Rect(this).apply { offset(-location[0], -location[1]) }
    }

    private fun currentHolder() = (viewPager2.getChildAt(0) as? RecyclerView)
        ?.findViewHolderForAdapterPosition(viewPager2.currentItem) as? PageHolder

    private fun setUiShown(isShown: Boolean, animate: Boolean) {
        isUiShown = isShown
        val alpha = if (isShown) 1f else 0f
        listOf(toolbar, bottomLinearLayout).forEach {
            it.animate().cancel()
            when (animate) {
                true -> it.animate().alpha(alpha).setDuration(duration() / 2).start()
                false -> it.alpha = alpha
            }
        }
    }

    override fun onTap() = setUiShown(!isUiShown, animate = true)

    override fun onDismissProgress(fraction: Float) {
        // The chat shows through as the photo is pulled away
        backgroundView.alpha = (1f - fraction * 2).coerceIn(0f, 1f)
        if (isUiShown) {
            val alpha = if (fraction > 0f) 0f else 1f
            toolbar.alpha = alpha
            bottomLinearLayout.alpha = alpha
        }
    }

    override fun onDismiss() = close()

    /** Into the gallery's Pictures or Movies, no permission needed for a file this app adds. */
    /** A GIF's "more": to the downloads, or to the account's GIFs. */
    private fun showGifMenu() {
        val photo = viewModel.state.value.photos.getOrNull(viewPager2.currentItem) ?: return
        val video = photo.video ?: return
        val anchor = toolbar.findViewById<View>(R.id.more) ?: toolbar
        androidx.appcompat.widget.PopupMenu(requireContext(), anchor).apply {
            menu.add(0, MENU_SAVE_DOWNLOADS, 0, R.string.photo_viewer_save_downloads)
            menu.add(0, MENU_SAVE_GIFS, 1, R.string.photo_viewer_save_gifs)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_SAVE_DOWNLOADS -> save(toDownloads = true)
                    MENU_SAVE_GIFS -> {
                        val context = requireContext().applicationContext
                        viewLifecycleOwner.lifecycleScope.launch {
                            Toast.makeText(
                                context,
                                when (viewModel.saveGif(video.fileId)) {
                                    true -> R.string.photo_viewer_saved_gif
                                    false -> R.string.photo_viewer_save_gif_failed
                                },
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
                true
            }
            show()
        }
    }

    /** To the gallery, a video to Movies; to the downloads when [toDownloads]. */
    private fun save(toDownloads: Boolean = false) {
        val photo = viewModel.state.value.photos.getOrNull(viewPager2.currentItem)
            ?.takeIf { it.canBeSaved } ?: return
        val context = requireContext().applicationContext
        val video = photo.video
        viewLifecycleOwner.lifecycleScope.launch {
            val path = when (video) {
                null -> viewModel.download(photo)
                else -> viewModel.download(video.fileId)
            }
            val isSaved = path != null && withContext(Dispatchers.IO) {
                runCatching {
                    val values = ContentValues().apply {
                        when {
                            toDownloads -> {
                                put(MediaStore.Downloads.DISPLAY_NAME, "shadowgram_${photo.messageId}.mp4")
                                put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Shadowgram")
                            }

                            video == null -> {
                                put(MediaStore.Images.Media.DISPLAY_NAME, "shadowgram_${photo.messageId}.jpg")
                                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Shadowgram")
                            }

                            else -> {
                                put(MediaStore.Video.Media.DISPLAY_NAME, "shadowgram_${photo.messageId}.mp4")
                                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Shadowgram")
                            }
                        }
                    }
                    val collection: Uri = when {
                        toDownloads -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
                        video == null -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        else -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    }
                    val uri = context.contentResolver.insert(collection, values)
                        ?: return@runCatching false
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        File(path).inputStream().use { it.copyTo(out) }
                    }
                    true
                }.getOrDefault(false)
            }
            Toast.makeText(
                context,
                when {
                    !isSaved -> R.string.photo_viewer_save_failed
                    toDownloads -> R.string.photo_viewer_saved_downloads
                    video != null -> R.string.photo_viewer_saved_video
                    else -> R.string.photo_viewer_saved
                },
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun duration() = MotionUtils.resolveThemeDuration(
        requireContext(), com.google.android.material.R.attr.motionDurationMedium4, 400
    ).toLong()

    /** The photos, one page each: blurry or the bubble's picture first, the full size after. */
    private inner class PagesAdapter : ListAdapter<PhotoViewerViewModel.Photo, PageHolder>(
        object : DiffUtil.ItemCallback<PhotoViewerViewModel.Photo>() {
            override fun areItemsTheSame(oldItem: PhotoViewerViewModel.Photo, newItem: PhotoViewerViewModel.Photo) =
                oldItem.messageId == newItem.messageId

            override fun areContentsTheSame(oldItem: PhotoViewerViewModel.Photo, newItem: PhotoViewerViewModel.Photo) =
                oldItem.messageId == newItem.messageId
        }
    ) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = PageHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.photo_viewer_page, parent, false)
        )

        override fun onBindViewHolder(holder: PageHolder, position: Int) = holder.bind(getItem(position))

        override fun onViewRecycled(holder: PageHolder) = holder.recycle()
    }

    private inner class PageHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ZoomableImageView = view.findViewById(R.id.zoomableImageView)
        val textureView: TextureView = view.findViewById(R.id.videoTextureView)
        private val progress = view.findViewById<CircularProgressIndicator>(R.id.progressIndicator)
        private var job: Job? = null
        private var messageId = 0L
        private var video: PhotoViewerViewModel.Video? = null
        private var videoWidth = 0
        private var videoHeight = 0

        init {
            image.listener = this@PhotoViewerDialogFragment
            // The video is drawn where its still is
            image.onDisplayRectChanged = ::placeVideo
            textureView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeVideo() }
        }

        fun bind(photo: PhotoViewerViewModel.Photo) {
            job?.cancel()
            messageId = photo.messageId
            video = photo.video
            videoWidth = photo.width
            videoHeight = photo.height
            textureView.isVisible = photo.video != null
            // The opened one starts from what the bubble showed
            when {
                photo.messageId == viewModel.messageId && anchor.drawable != null ->
                    image.setImageDrawable(anchor.drawable?.constantState?.newDrawable() ?: anchor.drawable)

                photo.minithumbnail != null -> image.load(photo.minithumbnail)
                // A video without a still: black of its size, so it's placed all the same
                photo.video != null -> image.setImageDrawable(GradientDrawable().apply {
                    setColor(android.graphics.Color.BLACK)
                    setSize(photo.width.coerceAtLeast(1), photo.height.coerceAtLeast(1))
                })

                else -> image.setImageDrawable(null)
            }
            progress.isVisible = photo.video == null && photo.path == null
            job = viewLifecycleOwner.lifecycleScope.launch {
                val path = viewModel.download(photo)
                if (photo.video == null) {
                    progress.isVisible = false
                }
                path ?: return@launch
                image.load(File(path)) {
                    // Zoomed in, it stays sharp
                    size(MAX_SIZE, MAX_SIZE)
                    precision(Precision.INEXACT)
                    allowHardware(false)
                    // The picture shown meanwhile stays until the sharp one is in
                    placeholder(image.drawable)
                }
            }
            if (bindingAdapterPosition == viewPager2.currentItem) {
                viewPager2.post { bindPlayer() }
            }
        }

        fun setBuffering(isBuffering: Boolean) {
            if (video != null) {
                progress.isVisible = isBuffering
            }
        }

        /** The frame the video is at, null if it hasn't one. */
        fun frame(): Drawable? {
            if (video == null || !hasFrame || !textureView.isAvailable) {
                return null
            }
            // The surface's picture at the video's own shape, the view's transform aside
            val scale = minOf(1f, FRAME_MAX_SIZE.toFloat() / maxOf(videoWidth, videoHeight, 1))
            val bitmap = textureView.getBitmap(
                (videoWidth * scale).toInt().coerceAtLeast(1),
                (videoHeight * scale).toInt().coerceAtLeast(1),
            ) ?: return null
            return BitmapDrawable(resources, bitmap)
        }

        /** The video's picture where the still is drawn: stretched to its rect. */
        private fun placeVideo() {
            if (video == null || textureView.width == 0 || textureView.height == 0) {
                return
            }
            val rect = image.displayRect()
            if (abs(rect.width()) < 1f || abs(rect.height()) < 1f) {
                return
            }
            textureView.setTransform(Matrix().apply {
                setScale(rect.width() / textureView.width, rect.height() / textureView.height)
                postTranslate(rect.left, rect.top)
            })
        }

        fun recycle() {
            job?.cancel()
            // Its video stops with it, and starts over if it's shown again
            if (messageId == playerMessageId) {
                player?.stop()
                player?.clearVideoTextureView(textureView)
                playerMessageId = 0L
            }
        }
    }

    companion object {
        const val REQUEST_KEY = "photo_viewer"

        /** The viewer shows the photo, the chat can hide its own. */
        const val REQUEST_KEY_SHOWN = "photo_viewer_shown"
        const val KEY_MESSAGE_ID = "message_id"

        private const val MAX_SIZE = 4096

        private const val MENU_SAVE_DOWNLOADS = 1
        private const val MENU_SAVE_GIFS = 2

        /** The frame a closing video flies back with, its longest side. */
        private const val FRAME_MAX_SIZE = 1280
        private const val POSITION_UPDATE_MS = 200L

        /** Full screen, square. */
        private val NO_CORNERS = FloatArray(4)

        /**
         * Create a [Bundle] to use as the arguments for this fragment.
         *
         * @param messageId The photo or video opened
         * @param messageIds The photos and videos to page through, in this order: an album's or a profile's
         */
        fun createBundle(chatId: Long, messageId: Long, messageIds: LongArray) = bundleOf(
            PhotoViewerViewModel.ARG_CHAT_ID to chatId,
            PhotoViewerViewModel.ARG_MESSAGE_ID to messageId,
            PhotoViewerViewModel.ARG_MESSAGE_IDS to messageIds,
        )

        /**
         * Create a [Bundle] to use as the arguments for this fragment, for a chat's profile
         * photos: the current one first, [PhotoViewerViewModel.CURRENT_PROFILE_PHOTO_ID].
         */
        fun createProfileBundle(chatId: Long) = bundleOf(
            PhotoViewerViewModel.ARG_CHAT_ID to chatId,
            PhotoViewerViewModel.ARG_MESSAGE_ID to PhotoViewerViewModel.CURRENT_PROFILE_PHOTO_ID,
            PhotoViewerViewModel.ARG_MESSAGE_IDS to longArrayOf(PhotoViewerViewModel.CURRENT_PROFILE_PHOTO_ID),
            PhotoViewerViewModel.ARG_PROFILE to true,
        )

        /**
         * Where [view], a picture the viewer opens from or closes to, is on screen: rounded
         * where it meets the corners of its bubble, or like itself when it's shaped.
         */
        fun frameOf(view: View): PhotoViewerViewModel.Frame {
            val bounds = view.boundsOnScreen()
            val viewport = view.mediaTransitionViewport()
            val corners = FloatArray(4)
            if (view is ShapeableImageView) {
                val size = RectF(0f, 0f, bounds.width().toFloat(), bounds.height().toFloat())
                val shape = view.shapeAppearanceModel
                corners[0] = shape.topLeftCornerSize.getCornerSize(size)
                corners[1] = shape.topRightCornerSize.getCornerSize(size)
                corners[2] = shape.bottomRightCornerSize.getCornerSize(size)
                corners[3] = shape.bottomLeftCornerSize.getCornerSize(size)
                return PhotoViewerViewModel.Frame(bounds, corners, viewport)
            }

            var card = view.parent as? View
            while (card != null && card !is MaterialCardView) {
                card = card.parent as? View
            }
            if (card is MaterialCardView) {
                val cardBounds = card.boundsOnScreen()
                val shape = card.shapeAppearanceModel
                val size = RectF(0f, 0f, cardBounds.width().toFloat(), cardBounds.height().toFloat())
                fun meets(a: Int, b: Int) = abs(a - b) <= 1
                val left = meets(bounds.left, cardBounds.left)
                val top = meets(bounds.top, cardBounds.top)
                val right = meets(bounds.right, cardBounds.right)
                val bottom = meets(bounds.bottom, cardBounds.bottom)
                if (left && top) corners[0] = shape.topLeftCornerSize.getCornerSize(size)
                if (right && top) corners[1] = shape.topRightCornerSize.getCornerSize(size)
                if (right && bottom) corners[2] = shape.bottomRightCornerSize.getCornerSize(size)
                if (left && bottom) corners[3] = shape.bottomLeftCornerSize.getCornerSize(size)
            }
            return PhotoViewerViewModel.Frame(bounds, corners, viewport)
        }

        private fun View.boundsOnScreen(): Rect {
            val location = IntArray(2).also(::getLocationOnScreen)
            return Rect(location[0], location[1], location[0] + width, location[1] + height)
        }
    }
}
