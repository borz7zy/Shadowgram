/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui.recyclerview

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import coil3.load
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.models.ProfileItem
import com.github.borz7zy.shadowgram.telegram.ProfilePage
import com.github.borz7zy.shadowgram.ui.recyclerview.RecyclerListView.DiffAdapter
import com.github.borz7zy.shadowgram.ui.views.AnimatedStickerPlayback
import com.github.borz7zy.shadowgram.ui.views.AvatarView
import com.github.borz7zy.shadowgram.ui.views.StickerView
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.shape.RelativeCornerSize
import java.io.File

/**
 * Entries of a profile tab, grid cells or rows depending on [kind]. Rebinding only touches the
 * views whose content changed, so a finished download doesn't make the others flicker.
 */
class ProfileItemAdapter(
    private val onLongClick: (ProfileItem) -> Boolean,
    /** Tapped, with its picture to open a media viewer from. */
    private val onClick: (ProfileItem, ImageView) -> Unit,
) : DiffAdapter<ProfileItem, ProfileItemAdapter.ViewHolder>(diffCallback) {
    /** What the items are, set before submitting another kind. */
    var kind = ProfilePage.Kind.MEDIA
    /** An asset keeps its playback position even if its cell is recycled or shown in another collection. */
    private val giftTimelines = HashMap<String, AnimatedStickerPlayback.Timeline>()

    override fun getItemViewType(position: Int) = when (kind.isGrid) {
        true -> R.layout.profile_grid_item
        false -> R.layout.profile_list_item
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        LayoutInflater.from(parent.context).inflate(viewType, parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position), kind)

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val imageView = view.findViewById<ShapeableImageView>(R.id.imageView)
        private val stickerView = view.findViewById<StickerView?>(R.id.stickerView)
        private val pinnedBadge = view.findViewById<View?>(R.id.profilePinnedBadge)
        private val badgeTextView = view.findViewById<TextView?>(R.id.badgeTextView)
        private val iconImageView = view.findViewById<ImageView?>(R.id.iconImageView)
        private val avatarView = view.findViewById<AvatarView?>(R.id.avatarView)
        private val titleTextView = view.findViewById<TextView?>(R.id.titleTextView)
        private val subtitleTextView = view.findViewById<TextView?>(R.id.subtitleTextView)
        private val downloadProgressIndicator =
            view.findViewById<CircularProgressIndicator?>(R.id.downloadProgressIndicator)

        private val cellShape = imageView.shapeAppearanceModel

        private var item: ProfileItem? = null
        private var kind: ProfilePage.Kind? = null

        init {
            view.setOnClickListener { item?.let { onClick(it, imageView) } }
            view.setOnLongClickListener { item?.let(onLongClick) ?: false }
        }

        fun bind(item: ProfileItem, kind: ProfilePage.Kind) {
            val old = this.item?.takeIf { it.key == item.key && this.kind == kind }
            this.item = item
            itemView.isLongClickable = item.profileAction != null
            pinnedBadge?.isVisible = item.isPinned

            if (this.kind != kind) {
                this.kind = kind
                iconImageView?.setImageResource(kind.iconResId)
                // Video messages are round
                imageView.shapeAppearanceModel = when (kind) {
                    ProfilePage.Kind.VIDEO_NOTES -> cellShape.withCornerSize(RelativeCornerSize(0.5f))
                    else -> cellShape
                }
            }

            if (old == null || old.title != item.title) {
                titleTextView?.text = item.title
            }
            if (old == null || old.subtitle != item.subtitle) {
                subtitleTextView?.text = item.subtitle
                subtitleTextView?.isVisible = item.subtitle != null
            }
            if (old == null || old.badge != item.badge) {
                badgeTextView?.text = item.badge
                badgeTextView?.isVisible = item.badge != null
            }

            if (old == null || old.avatar != item.avatar) {
                avatarView?.isVisible = item.avatar != null
                item.avatar?.let { avatarView?.setChat(it) }
            }

            if (old == null || old.imagePath != item.imagePath
                || old.minithumbnail !== item.minithumbnail
            ) {
                when (val picture = item.imagePath?.let(::File) ?: item.minithumbnail) {
                    null -> {
                        imageView.dispose()
                        imageView.setImageDrawable(null)
                    }

                    else -> imageView.load(picture)
                }
                // Rows without a picture show what they are instead
                val hasPicture = item.imagePath != null || item.minithumbnail != null
                if (iconImageView != null) {
                    imageView.isVisible = hasPicture
                }
                iconImageView?.isVisible = !hasPicture && item.avatar == null
            }

            // A file's row: its download around the picture, what a tap does in place of the icon
            if (old == null || old.document != item.document) {
                val document = item.document
                downloadProgressIndicator?.isVisible = document?.isDownloading == true
                if (document?.isDownloading == true && document.size > 0) {
                    downloadProgressIndicator?.setProgressCompat(
                        (document.downloadedSize * 100 / document.size).toInt(), true
                    )
                }
                iconImageView?.setImageResource(
                    when {
                        document == null -> kind.iconResId
                        document.path != null -> R.drawable.ic_draft
                        document.isDownloading -> R.drawable.ic_close
                        else -> R.drawable.ic_download
                    }
                )
            }

            if (old == null || old.stickerPath != item.stickerPath) {
                val path = item.stickerPath
                val timeline = if (path != null && kind == ProfilePage.Kind.GIFTS) {
                    giftTimelines.getOrPut(path) { AnimatedStickerPlayback.Timeline() }
                } else null
                stickerView?.isVisible = path != null
                stickerView?.setSticker(
                    path?.let(::File),
                    itemView.resources.getDimensionPixelSize(R.dimen.profile_sticker_size),
                    timeline,
                )
            }
        }
    }

    private val ProfilePage.Kind.iconResId
        get() = when (this) {
            ProfilePage.Kind.FILES -> R.drawable.ic_attach_file
            ProfilePage.Kind.LINKS -> R.drawable.ic_link
            ProfilePage.Kind.MUSIC -> R.drawable.ic_music_note
            ProfilePage.Kind.VOICE_NOTES -> R.drawable.ic_graphic_eq
            ProfilePage.Kind.POLLS -> R.drawable.ic_poll
            ProfilePage.Kind.SAVED -> R.drawable.ic_chat
            else -> R.drawable.ic_person
        }

    companion object {
        private val diffCallback = object : DiffUtil.ItemCallback<ProfileItem>() {
            override fun areItemsTheSame(oldItem: ProfileItem, newItem: ProfileItem) =
                oldItem.areItemsTheSame(newItem)

            override fun areContentsTheSame(oldItem: ProfileItem, newItem: ProfileItem) =
                oldItem.areContentsTheSame(newItem)

            // A payload makes RecyclerView rebind the same view instead of cross-fading a new
            // one, the view holder then only touches the views that changed
            override fun getChangePayload(oldItem: ProfileItem, newItem: ProfileItem) = newItem
        }
    }
}
