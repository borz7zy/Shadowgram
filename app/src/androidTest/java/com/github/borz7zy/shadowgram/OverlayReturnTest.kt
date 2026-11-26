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
import android.widget.ScrollView
import androidx.activity.ComponentDialog
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.widget.NestedScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.borz7zy.shadowgram.fragments.PhotoViewerDialogFragment
import com.github.borz7zy.shadowgram.fragments.NowPlayingDialogFragment
import com.github.borz7zy.shadowgram.testing.MainSectionTestActivity
import com.github.borz7zy.shadowgram.ext.toPx
import com.github.borz7zy.shadowgram.ui.MessageMenuLayout
import com.github.borz7zy.shadowgram.ui.finishOverlayReturn
import com.github.borz7zy.shadowgram.ui.visibleBoundsOnScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class OverlayReturnTest {
    @Test fun fullPlayerReturnsToTheSameScrolledChatOrProfile() {
        ActivityScenario.launch(MainSectionTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val host = activity.openProbeChat()
                val manager = activity.supportFragmentManager
                for (destination in listOf(R.id.chatFragment, R.id.profileFragment)) {
                    if (host.navController.currentDestination?.id != destination) {
                        host.navController.navigate(destination)
                    }
                    host.childFragmentManager.executePendingTransactions()
                    activity.resize(400)
                    val page = host.childFragmentManager.primaryNavigationFragment!!
                    val scroll = page.requireView() as ScrollView
                    val offset = scroll.toPx(900)
                    scroll.scrollTo(0, offset)
                    assertEquals(offset, scroll.scrollY)

                    NowPlayingDialogFragment.open(activity)
                    val player = manager.fragments.filterIsInstance<NowPlayingDialogFragment>().single()
                    val playerHost = player.childFragmentManager.findFragmentById(R.id.nowPlayingNavHost) as NavHostFragment
                    assertEquals(R.id.nowPlayingFragment, playerHost.navController.currentDestination?.id)
                    assertSame(scroll, page.requireView())

                    val back = (player.requireDialog() as ComponentDialog).onBackPressedDispatcher
                    playerHost.navController.navigate(R.id.action_nowPlayingFragment_to_fragment_queue)
                    assertEquals(R.id.queueFragment, playerHost.navController.currentDestination?.id)
                    back.onBackPressed()
                    assertEquals(R.id.nowPlayingFragment, playerHost.navController.currentDestination?.id)
                    back.onBackPressed()
                    manager.executePendingTransactions()

                    assertFalse(player.isAdded)
                    assertSame(page, host.childFragmentManager.primaryNavigationFragment)
                    assertSame(scroll, page.requireView())
                    assertEquals(offset, scroll.scrollY)
                }
            }
        }
    }

    @Test fun longMessagePreviewScrollsAtOriginalSizeAndLeavesRoomForControls() {
        ActivityScenario.launch(MainSectionTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = LayoutInflater.from(activity).inflate(R.layout.fragment_message_menu, null) as MessageMenuLayout
                root.visibility = View.VISIBLE
                val width = root.toPx(340)
                val height = root.toPx(320)
                val messageHeight = root.toPx(900)
                val messageWidth = root.toPx(240)
                root.source = Rect(0, -root.toPx(100), messageWidth, messageHeight - root.toPx(100))
                val preview = root.findViewById<NestedScrollView>(R.id.bubbleScrollView)
                preview.visibility = View.VISIBLE
                val image = root.findViewById<ImageView>(R.id.bubblePreviewImageView)
                image.layoutParams = FrameLayout.LayoutParams(messageWidth, messageHeight)
                val reactions = root.findViewById<View>(R.id.reactionsCardView)
                val actions = root.findViewById<NestedScrollView>(R.id.actionsScrollView)
                assertFalse(preview.isNestedScrollingEnabled)
                assertFalse(actions.isNestedScrollingEnabled)
                fun layout() {
                    root.measure(exact(width), exact(height))
                    root.layout(0, 0, width, height)
                }
                layout()

                assertEquals(messageHeight, image.height)
                assertEquals(messageWidth, image.width)
                assertTrue(preview.height in 1 until messageHeight)
                assertTrue(reactions.bottom <= preview.top)
                assertTrue(preview.bottom <= actions.top && actions.bottom <= height)
                preview.scrollTo(0, messageHeight)
                assertEquals(messageHeight - preview.height, preview.scrollY)

                val list = root.findViewById<RecyclerView>(R.id.reactionsRecyclerView)
                val reactionRow = root.findViewById<android.widget.LinearLayout>(R.id.reactionsLinearLayout)
                list.layoutManager = GridLayoutManager(activity, 1)
                reactionRow.orientation = android.widget.LinearLayout.VERTICAL
                actions.visibility = View.GONE
                root.isExpanded = true
                layout()
                assertTrue(reactions.bottom <= preview.top && preview.bottom <= height)
                assertTrue(root.findViewById<View>(R.id.expandMaterialButton).height > 0)
                assertEquals(messageHeight, image.height)
            }
        }
    }

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
