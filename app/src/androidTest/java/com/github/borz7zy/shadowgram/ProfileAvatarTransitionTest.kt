/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.borz7zy.shadowgram.testing.MainSectionTestActivity
import com.github.borz7zy.shadowgram.ui.views.AvatarView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileAvatarTransitionTest {
    @Test fun photoAndInitialsFollowTheSharedAvatarInBothDirections() {
        ActivityScenario.launch(MainSectionTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val avatar = AvatarView(activity)
                for ((start, end) in listOf(40 to 160, 160 to 40)) {
                    val spec = View.MeasureSpec.makeMeasureSpec(end, View.MeasureSpec.EXACTLY)
                    avatar.measure(spec, spec)
                    avatar.layout(20, 30, 20 + end, 30 + end)

                    // ChangeBounds starts from the other screen's bounds and skips layout.
                    for ((index, size) in listOf(start, 100, end).withIndex()) {
                        val offset = index * 10
                        avatar.setLeftTopRightBottom(offset, offset, offset + size, offset + size)
                        assertEquals(size / 2f, avatar.radius, 0.1f)
                        for (childIndex in 0 until avatar.childCount) {
                            val child = avatar.getChildAt(childIndex)
                            assertEquals(size, child.width)
                            assertEquals(size, child.height)
                        }
                    }
                }
            }
        }
    }
}
