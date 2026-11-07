package com.minichat.app.data.repository

import com.minichat.app.core.util.AppResult
import com.minichat.app.core.util.TimeFormat
import com.minichat.app.core.util.safeCall
import com.minichat.app.data.dto.ConversationMemberDto
import com.minichat.app.data.dto.ConversationOverviewDto
import com.minichat.app.data.dto.MessageInsertDto
import com.minichat.app.data.dto.MessageRowDto
import com.minichat.app.data.dto.ReactionInsertDto
import com.minichat.app.data.realtime.RealtimeService
import com.minichat.app.domain.model.ConversationSummary
import com.minichat.app.domain.model.Message
import com.minichat.app.domain.repository.ChatRepository
import com.minichat.app.domain.repository.ConversationSignal
import com.minichat.app.domain.repository.MessageEvent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val client: SupabaseClient,
    private val realtime: RealtimeService,
) : ChatRepository {

    private fun me(): String? = client.auth.currentUserOrNull()?.id

    override fun conversationSignals(): Flow<ConversationSignal> = realtime.conversationSignals

    override fun messageEvents(conversationId: String): Flow<MessageEvent> =
        realtime.messageEvents.filter { event ->
            when (event) {
                is MessageEvent.Inserted -> event.message.conversationId == conversationId
                is MessageEvent.Updated -> event.message.conversationId == conversationId
                is MessageEvent.Deleted -> true
                is MessageEvent.ReactionAdded, is MessageEvent.ReactionRemoved -> true
            }
        }

    override suspend fun loadConversations(): AppResult<List<ConversationSummary>> = safeCall {
        val userId = me().orEmpty()
        client.from(VIEW_CONVERSATIONS)
            .select(columns = Columns.list(*OVERVIEW_COLUMNS)) {
                order("conversation_updated_at", Order.DESCENDING)
            }
            .decodeList<ConversationOverviewDto>()
            .map { it.toDomain(userId) }
            .sortedWith(
                compareByDescending<ConversationSummary> { it.isPinned }
                    .thenByDescending { it.sortKey },
            )
    }

    override suspend fun loadConversation(conversationId: String): AppResult<ConversationSummary?> = safeCall {
        val userId = me().orEmpty()
        client.from(VIEW_CONVERSATIONS)
            .select(columns = Columns.list(*OVERVIEW_COLUMNS)) {
                filter { eq("conversation_id", conversationId) }
            }
            .decodeList<ConversationOverviewDto>()
            .firstOrNull()
            ?.toDomain(userId)
    }

    override suspend fun loadMessages(
        conversationId: String,
        limit: Int,
        before: Instant?,
    ): AppResult<List<Message>> = safeCall {
        val userId = me().orEmpty()
        client.from(TABLE_MESSAGES)
            .select(columns = Columns.raw(MESSAGE_SELECT)) {
                filter {
                    eq("conversation_id", conversationId)
                    before?.let { lt("created_at", TimeFormat.toIso(it)) }
                }
                order("created_at", Order.DESCENDING)
                limit(limit.toLong())
            }
            .decodeList<MessageRowDto>()
            .map { it.toDomain(userId) }
            .sortedBy { it.createdAt ?: Instant.EPOCH }
    }

    override suspend fun sendMessage(
        conversationId: String,
        content: String,
        replyToId: String?,
    ): AppResult<Message> = safeCall {
        val userId = me() ?: error("You must be signed in to send a message.")
        val trimmed = content.trim()
        if (trimmed.isEmpty()) error("Messages can't be empty.")

        val row = client.from(TABLE_MESSAGES)
            .insert(
                MessageInsertDto(
                    conversationId = conversationId,
                    senderId = userId,
                    content = trimmed,
                    replyToId = replyToId,
                ),
            ) {
                select(Columns.raw(MESSAGE_SELECT))
            }
            .decodeSingle<MessageRowDto>()
        row.toDomain(userId)
    }

    override suspend fun editMessage(messageId: String, content: String): AppResult<Unit> = safeCall {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) error("Messages can't be empty.")
        client.from(TABLE_MESSAGES).update(
            {
                set("content", trimmed)
            },
        ) {
            filter { eq("id", messageId) }
        }
    }

    override suspend fun deleteMessage(messageId: String): AppResult<Unit> = safeCall {
        // Soft delete keeps the thread readable for the other person ("message deleted").
        client.from(TABLE_MESSAGES).update(
            {
                set("is_deleted", true)
            },
        ) {
            filter { eq("id", messageId) }
        }
    }

    override suspend fun addReaction(messageId: String, emoji: String): AppResult<Unit> = safeCall {
        val userId = me() ?: error("You must be signed in.")
        client.from(TABLE_REACTIONS).insert(
            ReactionInsertDto(messageId = messageId, userId = userId, emoji = emoji),
        )
        Unit
    }

    override suspend fun removeReaction(messageId: String, emoji: String): AppResult<Unit> = safeCall {
        val userId = me() ?: error("You must be signed in.")
        client.from(TABLE_REACTIONS).delete {
            filter {
                eq("message_id", messageId)
                eq("user_id", userId)
                eq("emoji", emoji)
            }
        }
    }

    override suspend fun markConversationRead(conversationId: String): AppResult<Unit> = safeCall {
        client.postgrest.rpc(
            function = "mark_conversation_read",
            parameters = buildJsonObject { put("p_conversation_id", conversationId) },
        )
    }

    override suspend fun setPinned(conversationId: String, pinned: Boolean): AppResult<Unit> = safeCall {
        val userId = me() ?: error("You must be signed in.")
        client.from(TABLE_MEMBERS).update(
            { set("is_pinned", pinned) },
        ) {
            filter {
                eq("conversation_id", conversationId)
                eq("user_id", userId)
            }
        }
    }

    override suspend fun setMuted(conversationId: String, muted: Boolean): AppResult<Unit> = safeCall {
        val userId = me() ?: error("You must be signed in.")
        client.from(TABLE_MEMBERS).update(
            { set("is_muted", muted) },
        ) {
            filter {
                eq("conversation_id", conversationId)
                eq("user_id", userId)
            }
        }
    }

    override suspend fun peerReadAt(conversationId: String): AppResult<Instant?> = safeCall {
        val userId = me().orEmpty()
        client.from(TABLE_MEMBERS)
            .select(columns = Columns.list("id", "conversation_id", "user_id", "last_read_at", "is_muted", "is_pinned")) {
                filter {
                    eq("conversation_id", conversationId)
                    neq("user_id", userId)
                }
            }
            .decodeList<ConversationMemberDto>()
            .firstOrNull()
            ?.lastReadAt
            ?.let { TimeFormat.parse(it) }
    }

    private companion object {
        const val VIEW_CONVERSATIONS = "conversation_overview"
        const val TABLE_MESSAGES = "messages"
        const val TABLE_REACTIONS = "message_reactions"
        const val TABLE_MEMBERS = "conversation_members"

        val OVERVIEW_COLUMNS = arrayOf(
            "conversation_id",
            "conversation_type",
            "my_id",
            "last_read_at",
            "is_muted",
            "is_pinned",
            "peer_id",
            "peer_display_name",
            "peer_username",
            "peer_avatar_url",
            "peer_last_seen_at",
            "last_message_id",
            "last_message_content",
            "last_message_sender_id",
            "last_message_at",
            "last_message_is_deleted",
            "unread_count",
            "conversation_updated_at",
        )

        /**
         * One request returns the message, its reaction set and the reply target with the
         * author's display name — no N+1 queries while scrolling.
         */
        const val MESSAGE_SELECT =
            "id,conversation_id,sender_id,content,reply_to_id,is_edited,is_deleted,created_at,updated_at," +
                "message_reactions(id,message_id,user_id,emoji)," +
                "reply_to:messages!messages_reply_to_id_fkey(id,sender_id,content,is_deleted,profiles(display_name,username))"
    }
}
