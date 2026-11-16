/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.github.borz7zy.shadowgram.ui.coil

import coil3.map.Mapper
import coil3.request.Options
import com.github.borz7zy.shadowgram.models.Thumbnail

object ThumbnailMapper : Mapper<Thumbnail, Any> {
    override fun map(data: Thumbnail, options: Options) = data.bitmap ?: data.uri
}
