/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package com.github.borz7zy.shadowgram.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.w3c.dom.Element

/** Resource compilation cannot catch an accidental Activity destination in the pane graph. */
class ChatPaneNavigationTest {
    private val android = "http://schemas.android.com/apk/res/android"
    private val app = "http://schemas.android.com/apk/res-auto"
    private val graph by lazy {
        val project = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "app/src/main/res/navigation/activity_chat.xml").exists() }
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File(project, "app/src/main/res/navigation/activity_chat.xml"))
    }

    private fun destination(id: String): Element {
        val nodes = graph.getElementsByTagName("fragment")
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .first { it.getAttributeNS(android, "id") == "@+id/$id" }
    }

    private fun actionTarget(destination: Element, action: String): String {
        val nodes = destination.getElementsByTagName("action")
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .first { it.getAttributeNS(android, "id") == "@+id/$action" }
            .getAttributeNS(app, "destination")
    }

    @Test fun `chat and scheduled headers open a fragment in the same pane`() {
        listOf("chatFragment", "scheduledChatFragment").forEach {
            assertEquals("@id/profileFragment",
                actionTarget(destination(it), "action_chatFragment_to_profileFragment"))
        }
        assertEquals("com.github.borz7zy.shadowgram.fragments.ProfileFragment",
            destination("profileFragment").getAttributeNS(android, "name"))
    }

    @Test fun `member profiles also stay inside the pane`() {
        assertEquals("@id/profileFragment",
            actionTarget(destination("profileFragment"), "action_profileFragment_to_profileActivity"))
    }

    @Test fun `chat pane never launches a separate profile activity`() {
        val activities = graph.getElementsByTagName("activity")
        assertFalse((0 until activities.length).any {
            (activities.item(it) as Element).getAttributeNS(android, "name").endsWith(".ProfileActivity")
        })
    }
}
