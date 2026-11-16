/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.github.borz7zy.shadowgram.ui.views

import android.content.Context
import android.util.AttributeSet
import androidx.annotation.AttrRes
import com.github.borz7zy.shadowgram.R

class HorizontalMediaItemView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = com.google.android.material.R.attr.materialCardViewStyle,
) : BaseMediaItemView(context, attrs, defStyleAttr, R.layout.horizontal_media_item_view)
