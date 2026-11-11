package com.minichat.app.data.dto

import com.minichat.app.domain.model.MessageStatus
import com.minichat.app.domain.model.ReplyPreview
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Regression suite for the fix that made sending messages work again.
 *
 * The old client asked PostgREST for a self-referencing embed
 * (`reply_to:messages!messages_reply_to_id_fkey(...)`). When the relationship
 * cache lost that hint the whole request failed with PGRST200 — which is the
 * "Could not find a relationship between 'messages' and 'messages'" screen.
 *
 * [MessageRowDto] is now embed-free: reactions and the reply preview are passed
 * in by the repository. These tests pin the new contract.
 */
class NoteAndMessageDtoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

    @Test
    fun `message row decodes from plain columns without any embed`() {
        val payload = """
            {
              "id": "0b6f1c62-0c1c-4a1a-9b3a-3c1c1c1c1c1c",
              "conversation_id": "11111111-1111-1111-1111-111111111111",
              "sender_id": "22222222-2222-2222-2222-222222222222",
              "content": "Hi",
              "reply_to_id": null,
              "is_edited": false,
              "is_deleted": false,
              "created_at": "2026-11-07T18:24:00+00:00",
              "updated_at": "2026-11-07T18:24:00+00:00"
            }
        """.trimIndent()

        val dto = json.decodeFromString<MessageRowDto>(payload)
        assertEquals("Hi", dto.content)
        assertNull(dto.replyToId)

        val message = dto.toDomain(currentUserId = "22222222-2222-2222-2222-222222222222")
        assertEquals(MessageStatus.Sent, message.status)
        assertTrue(message.isMine)
        assertNotNull(message.createdAt)
        assertTrue(message.reactions.isEmpty())
        assertNull(message.replyPreview)
    }

    @Test
    fun `incoming message is not marked as mine`() {
        val dto = MessageRowDto(
            id = "a",
            conversationId = "c",
            senderId = "other",
            content = "hello",
            createdAt = "2026-11-07T18:24:00Z",
        )
        val message = dto.toDomain(currentUserId = "me")
        assertFalse(message.isMine)
        assertEquals(MessageStatus.Delivered, message.status)
    }

    @Test
    fun `reply preview supplied by the repository is attached to the message`() {
        val dto = MessageRowDto(
            id = "reply",
            conversationId = "c",
            senderId = "me",
            content = "answering",
            replyToId = "target",
            createdAt = "2026-11-07T18:25:00Z",
        )
        val preview = ReplyPreview(messageId = "target", senderName = "You", snippet = "original")

        val message = dto.toDomain(currentUserId = "me", replyPreview = preview)
        assertEquals("target", message.replyToId)
        assertEquals("You", message.replyPreview?.senderName)
        assertEquals("original", message.replyPreview?.snippet)
    }

    @Test
    fun `reply preview rows decode from the rpc projection`() {
        val payload = """
            [{"id":"t1","sender_id":"s1","content":"original text","is_deleted":false,
              "display_name":"Alice","username":"alice"}]
        """.trimIndent()

        val rows = json.decodeFromString<List<ReplyPreviewRowDto>>(payload)
        assertEquals(1, rows.size)
        assertEquals("Alice", rows.first().displayName)
        assertEquals("original text", rows.first().content)
    }

    @Test
    fun `note dto survives a missing author and computes lifetime`() {
        val payload = """
            {
              "author_id": "11111111-1111-1111-1111-111111111111",
              "body": "Coffee then code",
              "expires_at": "2099-01-01T00:00:00+00:00",
              "created_at": "2026-11-07T10:00:00+00:00"
            }
        """.trimIndent()

        val note = json.decodeFromString<NoteDto>(payload).toDomain()
        assertNotNull(note)
        assertTrue(note!!.isLive())
        assertNull(note.author)
        assertTrue(note.hoursLeft() > 0)
    }

    @Test
    fun `note dto is rejected when the expiry cannot be parsed`() {
        val dto = NoteDto(authorId = "x", body = "hi", expiresAt = "not-a-date")
        assertNull(dto.toDomain())
    }

    @Test
    fun `expired note is never live`() {
        val note = com.minichat.app.domain.model.Note(
            authorId = "x",
            body = "old",
            expiresAt = Instant.parse("2020-01-01T00:00:00Z"),
        )
        assertFalse(note.isLive())
        assertEquals(0L, note.hoursLeft())
    }
}
