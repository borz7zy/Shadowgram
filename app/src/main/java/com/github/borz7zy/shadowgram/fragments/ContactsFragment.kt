/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.fragments

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.ActivityNavigatorExtras
import androidx.navigation.fragment.findNavController
import com.github.borz7zy.shadowgram.ui.recyclerview.RecyclerListView
import com.github.borz7zy.shadowgram.R
import com.github.borz7zy.shadowgram.ext.getViewProperty
import com.github.borz7zy.shadowgram.ext.navigateSafe
import com.github.borz7zy.shadowgram.ext.setProgressCompat
import com.github.borz7zy.shadowgram.ext.toPx
import com.github.borz7zy.shadowgram.models.FlowResult
import com.github.borz7zy.shadowgram.models.SortingRule
import com.github.borz7zy.shadowgram.models.SortingStrategy
import com.github.borz7zy.shadowgram.ui.recyclerview.ContactListAdapter
import com.github.borz7zy.shadowgram.ui.views.SortingChip
import com.github.borz7zy.shadowgram.viewmodels.ContactsViewModel
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The contacts of the active account, sorted by name (grouped by first letter) or by last seen.
 */
class ContactsFragment : Fragment(R.layout.fragment_contacts) {
    // View models
    private val viewModel by viewModels<ContactsViewModel>()

    // Views
    private val linearProgressIndicator by getViewProperty<LinearProgressIndicator>(R.id.linearProgressIndicator)
    private val noElementsLinearLayout by getViewProperty<LinearLayout>(R.id.noElementsLinearLayout)
    private val recyclerView by getViewProperty<RecyclerListView>(R.id.recyclerView)
    private val sortingChip by getViewProperty<SortingChip>(R.id.sortingChip)

    // RecyclerView
    private val adapter = ContactListAdapter { row, avatarView ->
        // The private chat of a user has the user id
        findNavController().navigateSafe(
            R.id.action_mainFragment_to_profileActivity,
            ProfileFragment.createBundle(row.contact.id),
            navigatorExtras = ActivityNavigatorExtras(
                ActivityOptionsCompat.makeSceneTransitionAnimation(
                    requireActivity(), avatarView, getString(R.string.transition_profile_avatar)
                )
            ),
        )
    }

    /**
     * The [SortingRule] of the rows held by [adapter].
     */
    private var shownSortingRule: SortingRule? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Insets
        ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { v, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )

            val padding = v.toPx(16)
            v.updatePadding(
                left = insets.left + padding,
                right = insets.right + padding,
                bottom = insets.bottom + padding,
            )

            windowInsets
        }

        sortingChip.setSortingStrategies(
            sortedMapOf(
                SortingStrategy.NAME to R.string.sort_by_name,
                SortingStrategy.LAST_SEEN to R.string.sort_by_last_seen,
            )
        )
        sortingChip.setOnSortingRuleSelectedListener {
            viewModel.setSortingRule(it)
        }

        recyclerView.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                loadData()
            }
        }
    }

    override fun onDestroyView() {
        recyclerView.adapter = null

        super.onDestroyView()
    }

    private suspend fun loadData() {
        coroutineScope {
            launch {
                viewModel.contactList.collectLatest {
                    linearProgressIndicator.setProgressCompat(it)

                    when (it) {
                        is FlowResult.Loading -> {
                            // Do nothing
                        }

                        is FlowResult.Success -> {
                            if (it.data.sortingRule != shownSortingRule) {
                                // Another order is another list: show it from the top instead
                                // of animating every row to its new place
                                shownSortingRule = it.data.sortingRule
                                adapter.submitList(null)
                            }

                            adapter.submitList(it.data.rows)

                            val isEmpty = it.data.rows.isEmpty()
                            recyclerView.isVisible = !isEmpty
                            noElementsLinearLayout.isVisible = isEmpty
                        }

                        is FlowResult.Failure -> {
                            Log.e(
                                LOG_TAG,
                                "Failed to load contacts, error: ${it.error}",
                                it.throwable
                            )

                            recyclerView.isVisible = false
                            noElementsLinearLayout.isVisible = true
                        }
                    }
                }
            }

            launch {
                viewModel.sortingRule.collectLatest {
                    sortingChip.setSortingRule(it)
                }
            }
        }
    }

    companion object {
        private val LOG_TAG = ContactsFragment::class.simpleName!!
    }
}
