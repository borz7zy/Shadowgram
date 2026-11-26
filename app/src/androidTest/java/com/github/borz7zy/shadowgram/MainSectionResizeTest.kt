/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Button
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.borz7zy.shadowgram.testing.MainSectionTestActivity
import com.github.borz7zy.shadowgram.models.ChatFolder
import com.github.borz7zy.shadowgram.models.ChatListKey
import com.github.borz7zy.shadowgram.ui.views.ChatFolderRailView
import com.github.borz7zy.shadowgram.ext.setCurrentItemIfChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.navigationrail.NavigationRailView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainSectionResizeTest {
    private val preferences get() = InstrumentationRegistry.getInstrumentation().targetContext
        .getSharedPreferences("chat_panes", Context.MODE_PRIVATE)
    private var previousWidth: Float? = null
    private var previousRailHidden: Boolean? = null

    @Before fun rememberWidth() {
        previousWidth = if (preferences.contains("list_width_dp")) preferences.getFloat("list_width_dp", 0f) else null
        previousRailHidden = if (preferences.contains("navigation_rail_hidden"))
            preferences.getBoolean("navigation_rail_hidden", false) else null
    }

    @After fun restoreWidth() {
        preferences.edit().apply {
            previousWidth?.let { putFloat("list_width_dp", it) } ?: remove("list_width_dp")
            previousRailHidden?.let { putBoolean("navigation_rail_hidden", it) } ?: remove("navigation_rail_hidden")
        }.commit()
    }

    @Test fun switchingTabsDuringResizeNeverLeavesTwoPagesVisible() {
        ActivityScenario.launch(MainSectionTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.resize(840)
                val chats = activity.supportFragmentManager.findFragmentByTag("probe_chats")!!.requireView() as ScrollView
                chats.scrollTo(0, 200)
                val rightPane = activity.panes.getChildAt(2)
                repeat(12) { step ->
                    val section = listOf("contacts", "profile", "chats")[step % 3]
                    activity.select(section)
                    activity.resize(listOf(673, 1280, 840)[step % 3])
                    assertOnlySelectedPage(activity, section)
                    assertSame(rightPane, activity.panes.getChildAt(2))
                    assertEquals(View.VISIBLE, rightPane.visibility)
                }
                assertSame(chats, activity.supportFragmentManager.findFragmentByTag("probe_chats")!!.requireView())
                assertEquals(200, chats.scrollY)
            }
        }
    }

    @Test fun recreationRestoresExactlyOneSelectedPage() {
        ActivityScenario.launch(MainSectionTestActivity::class.java).use { scenario ->
            scenario.onActivity { it.select("contacts"); it.resize(840) }
            scenario.recreate()
            scenario.onActivity {
                it.resize(673)
                assertEquals("contacts", it.selectedSection)
                assertOnlySelectedPage(it, "contacts")
            }
        }
    }

    @Test fun profileToolbarAndSystemBackReturnToChatBeforeClosingThePane() {
        ActivityScenario.launch(MainSectionTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val host = activity.openProbeChat()
                val controller = host.navController
                controller.navigate(R.id.profileFragment)
                host.childFragmentManager.executePendingTransactions()
                host.childFragmentManager.primaryNavigationFragment!!.requireView()
                    .findViewById<Button>(android.R.id.button1).performClick()
                assertEquals(R.id.chatFragment, controller.currentDestination!!.id)
                assertEquals(0, activity.closedPaneCount)
                controller.navigate(R.id.profileFragment)
                host.childFragmentManager.executePendingTransactions()
                activity.onBackPressedDispatcher.onBackPressed()
                assertEquals(R.id.chatFragment, controller.currentDestination!!.id)
                assertEquals(0, activity.closedPaneCount)
                activity.onBackPressedDispatcher.onBackPressed()
                assertEquals(1, activity.closedPaneCount)
                assertEquals(R.id.chatFragment, controller.currentDestination!!.id)
            }
        }
    }

    @Test fun phoneChatDoesNotResizeOrCollapseThePreservedHomeScreen() {
        ActivityScenario.launch(MainSectionTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.resize(393)
                val width = activity.sections.width
                val chats = activity.supportFragmentManager.findFragmentByTag("probe_chats")!!.requireView() as ScrollView
                chats.scrollTo(0, 200)
                repeat(4) {
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                        activity.sections.importantForAccessibility)
                    assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, activity.sections.descendantFocusability)
                    assertEquals(width, chats.width)
                    assertTrue(!activity.panes.isAvatarOnly)
                    activity.panes.chatOpen = false
                    activity.resize(393)
                    assertEquals(width, activity.sections.width)
                    assertEquals(200, chats.scrollY)
                    assertEquals(View.GONE, activity.panes.getChildAt(2).visibility)
                    assertTrue(activity.sections.importantForAccessibility != View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
                    activity.panes.chatOpen = true
                    activity.resize(393)
                }
            }
        }
    }

    @Test fun selectedFolderFeedbackDoesNotInterruptASmoothPhoneSwipe() {
        ActivityScenario.launch(MainSectionTestActivity::class.java).use { scenario ->
            var pager: ViewPager2? = null
            scenario.onActivity { activity ->
                activity.resize(393)
                pager = ViewPager2(activity).apply {
                    adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                        override fun getItemCount() = 3
                        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
                            object : RecyclerView.ViewHolder(FrameLayout(parent.context).apply {
                                layoutParams = ViewGroup.LayoutParams(-1, -1)
                            }) {}
                        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
                    }
                }
                (activity.panes.getChildAt(2) as ViewGroup).addView(pager, ViewGroup.LayoutParams(-1, -1))
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                val viewPager = pager!!
                var callbackReached = false
                viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageSelected(position: Int) {
                        if (position != 1) return
                        callbackReached = true
                        assertEquals(ViewPager2.SCROLL_STATE_SETTLING, viewPager.scrollState)
                        viewPager.setCurrentItemIfChanged(position)
                        assertEquals(ViewPager2.SCROLL_STATE_SETTLING, viewPager.scrollState)
                    }
                })
                viewPager.setCurrentItem(1, true)
                assertTrue(callbackReached)
            }
        }
    }

    @Test fun foldersInTheRailSelectTheSameStableFolderIds() {
        ActivityScenario.launch(MainSectionTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val folders = listOf(ChatFolder(ChatListKey.Main, null),
                    ChatFolder(ChatListKey.Folder(7), "Work"), ChatFolder(ChatListKey.Folder(9), "Friends"))
                val rail = ChatFolderRailView(activity)
                var selected: ChatListKey = ChatListKey.Main
                rail.onFolderSelected = { selected = it }
                rail.submit(folders, selected.stableId, unread = emptyMap())
                assertEquals(3, rail.childCount)
                (rail.getChildAt(1) as MaterialButton).performClick()
                assertEquals(ChatListKey.Folder(7), selected)
                assertTrue((rail.getChildAt(1) as MaterialButton).isChecked)
                assertEquals(false, (rail.getChildAt(0) as MaterialButton).isChecked)
                rail.submit(folders, selected.stableId, unread = emptyMap())
                assertTrue((rail.getChildAt(1) as MaterialButton).isChecked)
            }
        }
    }

    @Test fun actualCompactSidebarLeavesVisibleSpaceForFolders() {
        ActivityScenario.launch(MainSectionTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                listOf(1f, 1.6f).forEach { scale ->
                    val configuration = Configuration(activity.resources.configuration).apply { fontScale = scale }
                    val context = ContextThemeWrapper(activity.createConfigurationContext(configuration), R.style.Theme_Shadowgram)
                    listOf(480, 800).forEach { heightDp ->
                        val root = LayoutInflater.from(context).inflate(R.layout.fragment_main_rail, null)
                        val column = root.findViewById<View>(R.id.navigationRailContainer)
                        val foldersScroll = root.findViewById<View>(R.id.foldersRailScrollView)
                        val divider = root.findViewById<View>(R.id.foldersRailDivider)
                        val navigation = root.findViewById<NavigationRailView>(R.id.navigationBarView)
                        root.findViewById<View>(R.id.hideNavigationRailButton).visibility = View.VISIBLE
                        foldersScroll.visibility = View.VISIBLE
                        divider.visibility = View.VISIBLE
                        navigation.layoutParams = (navigation.layoutParams as LinearLayout.LayoutParams).apply {
                            height = ViewGroup.LayoutParams.WRAP_CONTENT
                            weight = 0f
                        }
                        val folders = root.findViewById<ChatFolderRailView>(R.id.chatFolderRailView)
                        folders.submit(listOf(ChatFolder(ChatListKey.Main, null),
                            ChatFolder(ChatListKey.Folder(7), "Work"),
                            ChatFolder(ChatListKey.Folder(9), "Friends")), ChatListKey.Main.stableId,
                            unread = emptyMap())
                        val density = context.resources.displayMetrics.density
                        val width = (168 * density).toInt()
                        val height = (heightDp * density).toInt()
                        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                        root.layout(0, 0, width, height)
                        assertTrue("Folders received no height at font scale $scale / ${heightDp}dp",
                            foldersScroll.height > 0)
                        assertTrue(folders.getChildAt(0).height >= (48 * density).toInt())
                        assertTrue(foldersScroll.top < foldersScroll.bottom)
                        assertTrue(divider.top >= foldersScroll.bottom)
                        assertTrue(navigation.top >= divider.bottom)
                        assertTrue(navigation.bottom <= column.height)
                    }
                }
            }
        }
    }

    @Test fun hidingAndShowingTheRailPreservesTheAvatarColumnAndOpenChat() {
        ActivityScenario.launch(MainSectionTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.select("contacts")
                activity.select("chats")
                activity.panes.navigationRailHidden = true
                activity.resize(840)
                val density = activity.resources.displayMetrics.density
                assertTrue(kotlin.math.abs(activity.sections.width - 88 * density) <= 1f)
                assertEquals(View.VISIBLE, activity.panes.getChildAt(2).visibility)
                activity.panes.navigationRailHidden = false
                activity.resize(840)
                assertEquals((168 * density).toInt(), activity.sections.width)
            }
        }
    }

    private fun assertOnlySelectedPage(activity: MainSectionTestActivity, selected: String) {
        val container = activity.sections
        val visible = (0 until container.childCount).map(container::getChildAt)
            .filter { it.visibility == View.VISIBLE }
        assertEquals(1, visible.size)
        val active = activity.supportFragmentManager.findFragmentByTag("probe_$selected")!!
        assertSame(active.requireView(), visible.single())
        assertEquals(0, active.requireView().left)
        assertEquals(0f, active.requireView().translationX, 0f)
        assertEquals(container.width, active.requireView().width)
        activity.supportFragmentManager.fragments.filter { it != active }.forEach {
            assertTrue(it.isHidden)
            assertEquals(View.GONE, it.requireView().visibility)
        }
    }
}
