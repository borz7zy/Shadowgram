/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.testing

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Button
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commitNow
import androidx.navigation.NavGraph
import androidx.navigation.NavGraphNavigator
import androidx.navigation.fragment.FragmentNavigator
import androidx.navigation.fragment.NavHostFragment
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.ui.MainSectionHost
import com.github.borz7zy.shadowgram.ui.views.AdaptiveChatLayout
import com.github.borz7zy.shadowgram.ui.views.ChatPaneDividerView
import com.github.borz7zy.shadowgram.fragments.ChatPaneNavHostFragment
import com.github.borz7zy.shadowgram.ext.navigateBackInPane

/** A real fragment/layout host for device tests, without accounts, network or chat contents. */
class MainSectionTestActivity : FragmentActivity() {
    lateinit var panes: AdaptiveChatLayout
        private set
    lateinit var sections: FragmentContainerView
        private set
    private lateinit var sectionHost: MainSectionHost
    var selectedSection = "chats"
        private set
    var closedPaneCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sections = FragmentContainerView(this).apply { id = R.id.mainSectionContainer }
        panes = AdaptiveChatLayout(this).apply {
            addView(sections)
            addView(ChatPaneDividerView(this@MainSectionTestActivity))
            addView(FrameLayout(this@MainSectionTestActivity).apply { id = R.id.chatPane })
            chatOpen = true
        }
        setContentView(panes)
        sectionHost = MainSectionHost(supportFragmentManager, R.id.mainSectionContainer)
        select(savedInstanceState?.getString("section") ?: "chats")
    }

    fun select(section: String) {
        sectionHost.show("probe_$section", animate = false) {
            ProbeFragment().apply { arguments = Bundle().apply { putString("section", section) } }
        }
        selectedSection = section
        panes.isChatListSelected = section == "chats"
    }

    fun openProbeChat(): ProbeChatPane {
        val host = ProbeChatPane()
        supportFragmentManager.commitNow {
            replace(R.id.chatPane, host, "probe_chat_host")
            setPrimaryNavigationFragment(host)
        }
        return host
    }

    class ProbeChatPane : ChatPaneNavHostFragment() {
        override fun initializeGraph() {
            val provider = navController.navigatorProvider
            val navigator = provider.getNavigator(FragmentNavigator::class.java)
            navController.graph = NavGraph(provider.getNavigator(NavGraphNavigator::class.java)).apply {
                id = R.id.activity_chat
                listOf(R.id.chatFragment, R.id.profileFragment).forEach { destinationId ->
                    addDestination(navigator.createDestination().apply {
                        id = destinationId
                        setClassName(ProbeFragment::class.java.name)
                    })
                }
                setStartDestination(R.id.chatFragment)
            }
        }

        override fun closePane() {
            (requireActivity() as MainSectionTestActivity).closedPaneCount++
        }
    }

    fun resize(widthDp: Int) {
        val density = resources.displayMetrics.density
        val width = (widthDp * density).toInt()
        val height = (800 * density).toInt()
        panes.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        panes.layout(0, 0, width, height)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("section", selectedSection)
        super.onSaveInstanceState(outState)
    }

    class ProbeFragment : Fragment() {
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
            val context = requireContext()
            return ScrollView(context).apply {
                layoutParams = FrameLayout.LayoutParams(-1, -1)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    if (parentFragment is NavHostFragment) addView(Button(context).apply {
                        id = android.R.id.button1
                        text = "Back"
                        setOnClickListener { navigateBackInPane() }
                    })
                    repeat(80) { index ->
                        addView(TextView(context).apply {
                            text = "${arguments?.getString("section") ?: "pane"} $index"
                            height = (48 * resources.displayMetrics.density).toInt()
                        })
                    }
                })
            }
        }
    }
}
