/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram

import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.borz7zy.shadowgram.testing.MainSectionTestActivity
import com.github.borz7zy.shadowgram.ui.viewer.ZoomableImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewerZoomTest {
    @Test fun videoKeepsItsGeometryAndZoomWhenThePreviewArrives() {
        ActivityScenario.launch(MainSectionTestActivity::class.java).use { scenario ->
            lateinit var image: ZoomableImageView
            scenario.onActivity { activity ->
                image = ZoomableImageView(activity)
                activity.setContentView(FrameLayout(activity).apply {
                    addView(image, FrameLayout.LayoutParams(400, 640))
                })
                image.layout(0, 0, 400, 640)
            }

            runBlocking(Dispatchers.Main) {
                image.setVideoSize(1920, 1080)
                val fitted = image.displayRect()
                assertEquals(400f, fitted.width(), 0.1f)
                assertEquals(225f, fitted.height(), 0.1f)

                image.zoomable.scale(image.zoomable.transformState.value.scaleX * 2f)
                val zoomed = image.displayRect()
                assertTrue(image.isZoomed)
                assertTrue(zoomed.width() > fitted.width())

                image.setImageDrawable(GradientDrawable().apply { setSize(64, 64) })
                assertEquals(zoomed, image.displayRect())

                image.resetMedia()
                assertFalse(image.isZoomed)
                assertEquals(fitted, image.displayRect())
            }
        }
    }
}
