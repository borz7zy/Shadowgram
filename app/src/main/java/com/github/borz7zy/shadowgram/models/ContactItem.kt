/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.models

/**
 * A contact as shown in the contacts list. Immutable, so that lists can be diffed.
 *
 * @param id The user ID
 * @param name The full name
 * @param accentColorId The accent color ID, used for the avatar placeholder
 * @param photoPath The local path of the small profile photo, null if there's none or it's not
 *   downloaded yet
 * @param minithumbnail Tiny blurry JPEG shown until [photoPath] is downloaded, null if none
 * @param lastSeen When the user has last been online
 */
data class ContactItem(
    val id: Long,
    val name: String,
    val accentColorId: Int,
    val photoPath: String?,
    val minithumbnail: ByteArray?,
    val lastSeen: LastSeen,
) {
    /**
     * Ordered from the most to the least recently seen.
     *
     * @param kind The [Kind]
     * @param time When the user went offline, in Unix time. Only set for [Kind.OFFLINE]
     */
    data class LastSeen(
        val kind: Kind,
        val time: Int = 0,
    ) : Comparable<LastSeen> {
        /**
         * Users hiding the exact time come after the ones showing it, like in the official apps.
         */
        enum class Kind {
            ONLINE,
            OFFLINE,
            RECENTLY,
            LAST_WEEK,
            LAST_MONTH,
            LONG_AGO,
        }

        override fun compareTo(other: LastSeen) =
            compareValuesBy(this, other, { it.kind }, { -it.time })
    }
}
