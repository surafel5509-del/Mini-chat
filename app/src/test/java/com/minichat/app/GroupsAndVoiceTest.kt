package com.minichat.app

import com.minichat.app.domain.model.GroupRole
import com.minichat.app.domain.model.Message
import com.minichat.app.domain.model.MessageType
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Group roles, message types and voice-note formatting.
 *
 * These are the rules that decide what a bubble renders and who gets admin
 * affordances, so they are worth pinning independently of Compose.
 */
class GroupsAndVoiceTest {

    // ---- roles ---------------------------------------------------------

    @Test
    fun `role parsing tolerates case and unknown values`() {
        assertEquals(GroupRole.Owner, GroupRole.from("owner"))
        assertEquals(GroupRole.Admin, GroupRole.from("ADMIN"))
        assertEquals(GroupRole.Member, GroupRole.from("member"))
        // Anything unexpected must degrade to the least privileged role, never
        // accidentally grant admin.
        assertEquals(GroupRole.Member, GroupRole.from(null))
        assertEquals(GroupRole.Member, GroupRole.from("superuser"))
        assertEquals(GroupRole.Member, GroupRole.from(""))
    }

    @Test
    fun `only owner and admin are administrative`() {
        assertTrue(GroupRole.Owner.isAdmin)
        assertTrue(GroupRole.Admin.isAdmin)
        assertFalse(GroupRole.Member.isAdmin)
    }

    // ---- message types -------------------------------------------------

    @Test
    fun `message type parsing defaults to text`() {
        assertEquals(MessageType.Voice, MessageType.from("voice"))
        assertEquals(MessageType.Image, MessageType.from("IMAGE"))
        assertEquals(MessageType.System, MessageType.from("system"))
        assertEquals(MessageType.Text, MessageType.from("text"))
        assertEquals(MessageType.Text, MessageType.from(null))
        assertEquals(MessageType.Text, MessageType.from("video"))
    }

    // ---- voice length --------------------------------------------------

    @Test
    fun `duration label formats minutes and seconds`() {
        assertEquals("0:07", message(durationMs = 7_000).durationLabel)
        assertEquals("0:59", message(durationMs = 59_400).durationLabel)
        assertEquals("1:00", message(durationMs = 60_000).durationLabel)
        assertEquals("1:05", message(durationMs = 65_000).durationLabel)
        assertEquals("0:00", message(durationMs = null).durationLabel)
        assertEquals("0:00", message(durationMs = -500).durationLabel)
    }

    @Test
    fun `voice message reports a readable fallback instead of blank content`() {
        val voice = message(durationMs = 7_000, type = MessageType.Voice)
        assertEquals("Voice message", voice.displayText)
    }

    @Test
    fun `deleted voice message shows the deleted label before the type`() {
        val deleted = message(durationMs = 7_000, type = MessageType.Voice).copy(isDeleted = true)
        assertEquals("This message was deleted", deleted.displayText)
    }

    @Test
    fun `expired voice note is flagged and reads as expired`() {
        val expired = message(durationMs = 3_000, type = MessageType.Voice)
            .copy(expiresAt = Instant.now().minusSeconds(60))

        assertTrue(expired.isExpired)
        assertEquals("Voice message expired", expired.displayText)
    }

    @Test
    fun `a voice note inside its window is not expired`() {
        val fresh = message(durationMs = 3_000, type = MessageType.Voice)
            .copy(expiresAt = Instant.now().plusSeconds(3_600))

        assertFalse(fresh.isExpired)
    }

    @Test
    fun `text messages never expire`() {
        assertFalse(message(type = MessageType.Text).copy(expiresAt = null).isExpired)
    }

    @Test
    fun `seven second cap is what the recorder enforces`() {
        // The composer stops recording at this value; keep it explicit so a future
        // change to the limit has to update this expectation on purpose.
        assertEquals(7_000, com.minichat.app.core.audio.VoiceRecorder.MAX_DURATION_MS)
    }

    private fun message(
        durationMs: Int? = null,
        type: MessageType = MessageType.Text,
    ) = Message(
        id = "m1",
        conversationId = "c1",
        senderId = "u1",
        content = "",
        createdAt = Instant.now(),
        messageType = type,
        durationMs = durationMs,
        senderName = "Alice",
    )
}
