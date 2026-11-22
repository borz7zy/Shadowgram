/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.widget.ImageView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreference
import com.github.borz7zy.shadowgram.R
import com.google.android.material.color.MaterialColors
import androidx.appcompat.R as AppCompatR
import com.google.android.material.R as MaterialR

class SettingsActionPreference(context: Context, attrs: AttributeSet? = null) : Preference(context, attrs) {
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        bindSettingsRow(holder)
    }
}

class SettingsSwitchPreference(context: Context, attrs: AttributeSet? = null) : SwitchPreference(context, attrs) {
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        bindSettingsRow(holder)
    }
}

private fun Preference.bindSettingsRow(holder: PreferenceViewHolder) {
    // The adapter can bind a removed preference before it processes the hierarchy change.
    val group = parent ?: return
    val row = holder.findViewById(R.id.settings_preference_surface) ?: return
    val visibleRows = (0 until group.preferenceCount).map(group::getPreference).filter(Preference::isVisible)
    val density = row.resources.displayMetrics.density
    val top = (if (visibleRows.firstOrNull() == this) 20f else 4f) * density
    val bottom = (if (visibleRows.lastOrNull() == this) 20f else 4f) * density
    val surface = GradientDrawable().apply {
        setColor(MaterialColors.getColor(row, MaterialR.attr.colorSurfaceContainerLowest))
        cornerRadii = floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
    }
    val ripple = MaterialColors.getColor(row, AppCompatR.attr.colorControlHighlight)
    row.background = RippleDrawable(ColorStateList.valueOf(ripple), surface, null)

    val colorGroup = if (group.key == "settings_home") key else group.key
    val (containerAttr, foregroundAttr) = when (colorGroup) {
        "settings_interface", "settings_screen", "settings_experimental" ->
            MaterialR.attr.colorSecondaryContainer to MaterialR.attr.colorOnSecondaryContainer
        "settings_audio", "settings_playback" ->
            MaterialR.attr.colorTertiaryContainer to MaterialR.attr.colorOnTertiaryContainer
        else -> MaterialR.attr.colorPrimaryContainer to MaterialR.attr.colorOnPrimaryContainer
    }
    val icon = holder.findViewById(android.R.id.icon) as? ImageView ?: return
    icon.imageTintList = ColorStateList.valueOf(MaterialColors.getColor(icon, foregroundAttr))
    icon.background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(MaterialColors.getColor(icon, containerAttr))
    }
}
