/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.borz7zy.shadowgram.fragments.PhotoViewerDialogFragment
import com.github.borz7zy.shadowgram.testing.MainSectionTestActivity
import com.github.borz7zy.shadowgram.ui.finishOverlayReturn
import com.github.borz7zy.shadowgram.ui.visibleBoundsOnScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class OverlayReturnTest {
    @Test fun returningMessageAndMediaNeverDrawOverHeaderOrComposer() {
        ActivityScenario.launch(MainSectionTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                for ((layout, imageId) in listOf(
                    R.layout.fragment_message_menu to R.id.bubbleImageView,
                    R.layout.fragment_photo_viewer to R.id.transitionImageView,
                )) {
                    val root = LayoutInflater.from(activity).inflate(layout, null) as FrameLayout
                    root.visibility = View.VISIBLE
                    val clip = root.findViewById<FrameLayout>(R.id.transitionClipLayout)
                    // Draw just the returning item; toolbar/menu controls are separate layers.
                    for (i in root.childCount - 1 downTo 0) {
                        if (root.getChildAt(i) !== clip) root.removeViewAt(i)
                    }
                    clip.clipBounds = Rect(20, 80, 220, 320)
                    val image = root.findViewById<ImageView>(imageId)
                    image.setBackgroundColor(Color.RED)
                    for (top in listOf(-100, 0, 160)) {
                        image.layoutParams = FrameLayout.LayoutParams(200, 500).apply {
                            leftMargin = 20
                            topMargin = top
                        }
                        root.measure(exact(240), exact(400))
                        root.layout(0, 0, 240, 400)
                        val bitmap = Bitmap.createBitmap(240, 400, Bitmap.Config.ARGB_8888)
                        root.draw(Canvas(bitmap))
                        assertEquals("header, layout=$layout top=$top", Color.TRANSPARENT, bitmap.getPixel(60, 40))
                        assertEquals("composer, layout=$layout top=$top", Color.TRANSPARENT, bitmap.getPixel(60, 360))
                        assertEquals("visible part", Color.RED, bitmap.getPixel(60, 200))
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    @Test fun hiddenPartOfThumbnailDoesNotChangeItsReturnGeometry() {
        ActivityScenario.launch(MainSectionTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = FrameLayout(activity)
                val list = RecyclerView(activity).apply {
                    layoutManager = LinearLayoutManager(activity)
                    adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                        override fun getItemCount() = 2
                        override fun onCreateViewHolder(parent: ViewGroup, type: Int) =
                            object : RecyclerView.ViewHolder(ImageView(activity).apply {
                                layoutParams = RecyclerView.LayoutParams(200, 400)
                            }) {}
                        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
                    }
                }
                root.addView(list, FrameLayout.LayoutParams(200, 200).apply {
                    topMargin = 100
                    leftMargin = 30
                })
                activity.setContentView(root)
                root.measure(exact(300), exact(500))
                root.layout(0, 0, 300, 500)
                list.scrollBy(0, 80)
                val image = list.findViewHolderForAdapterPosition(0)!!.itemView
                image.alpha = 0f // The source remains hidden throughout the viewer's return.
                val frame = PhotoViewerDialogFragment.frameOf(image)
                val viewport = list.visibleBoundsOnScreen()
                assertEquals(400, frame.bounds.height())
                assertEquals(viewport, frame.viewport)
                assertTrue(frame.bounds.top < viewport.top)
                assertTrue(frame.bounds.bottom > viewport.bottom)
            }
        }
    }

    @Test fun overlayWaitsForRestoredSourceFrame() {
        val committed = CountDownLatch(1)
        ActivityScenario.launch(MainSectionTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val source = activity.panes
                source.alpha = 1f
                finishOverlayReturn(source) { committed.countDown() }
                assertEquals("must retain overlay before source draws", 1L, committed.count)
            }
            assertTrue("source frame was committed", committed.await(5, TimeUnit.SECONDS))
        }
    }

    @Test fun destroyingOverlayCancelsPendingHandoff() {
        var dismissed = false
        ActivityScenario.launch(MainSectionTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val cancel = finishOverlayReturn(activity.panes) { dismissed = true }
                cancel()
                (activity.panes.parent as ViewGroup).removeView(activity.panes)
                assertFalse(dismissed)
            }
        }
    }

    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
}
