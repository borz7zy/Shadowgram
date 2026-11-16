/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.models

import java.text.Collator

/**
 * A row of the contacts list.
 */
sealed interface ContactRow : UniqueItem<ContactRow> {
    /**
     * The place of a contact in its group, it defines the shape of its card.
     */
    enum class Segment {
        FIRST,
        MIDDLE,
        LAST,
        SINGLE,
    }

    /**
     * The title of a group of contacts.
     *
     * @param title The first letter of the names of the group
     */
    data class Header(val title: String) : ContactRow {
        override fun areItemsTheSame(other: ContactRow) = this == other
    }

    /**
     * @param contact The [ContactItem]
     * @param segment The place of the contact in its group
     * @param showLastSeen Whether the last seen status should be shown
     */
    data class Contact(
        val contact: ContactItem,
        val segment: Segment,
        val showLastSeen: Boolean,
    ) : ContactRow {
        override fun areItemsTheSame(other: ContactRow) =
            other is Contact && contact.id == other.contact.id
    }

    override fun areContentsTheSame(other: ContactRow) = this == other

    companion object {
        private const val OTHER_GROUP_TITLE = "#"

        /**
         * Sort the contacts and split them in groups.
         *
         * [SortingStrategy.LAST_SEEN] gives a single group, most recently seen first, with the
         * last seen status shown. Any other strategy sorts by name and gives a group per first
         * letter, each one with its [Header].
         */
        fun List<ContactItem>.toContactRows(
            sortingRule: SortingRule,
            collator: Collator = Collator.getInstance(),
        ): List<ContactRow> {
            val byLastSeen = sortingRule.strategy == SortingStrategy.LAST_SEEN

            val comparator = when (byLastSeen) {
                true -> compareBy<ContactItem> { it.lastSeen }.thenBy(collator) { it.name }
                false -> compareBy<ContactItem, String>(collator) { it.name }
            }.thenBy { it.id }

            val sorted = sortedWith(
                when (sortingRule.reverse) {
                    true -> comparator.reversed()
                    false -> comparator
                }
            )

            val groups = when (byLastSeen) {
                true -> listOf(null to sorted)
                // Names with the same title are not always next to each other for the collator
                // (e.g. Е and Ё), grouping keeps every title unique
                false -> sorted.groupBy { it.name.groupTitle() }.toList()
            }

            return groups.flatMap { (title, contacts) ->
                listOfNotNull(title?.let(::Header)) + contacts.mapIndexed { index, contact ->
                    Contact(
                        contact,
                        when {
                            contacts.size == 1 -> Segment.SINGLE
                            index == 0 -> Segment.FIRST
                            index == contacts.lastIndex -> Segment.LAST
                            else -> Segment.MIDDLE
                        },
                        byLastSeen,
                    )
                }
            }
        }

        private fun String.groupTitle() = firstOrNull()?.takeIf { it.isLetter() }
            ?.uppercase() ?: OTHER_GROUP_TITLE
    }
}
