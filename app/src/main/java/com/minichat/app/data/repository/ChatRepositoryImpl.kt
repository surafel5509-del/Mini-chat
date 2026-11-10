package com.minichat.app.data.repository

import com.minichat.app.core.util.AppResult
import com.minichat.app.core.util.TimeFormat
import com.minichat.app.core.util.safeCall
import com.minichat.app.data.dto.ConversationMemberDto
import com.minichat.app.data.dto.ConversationOverviewDto
import com.minichat.app.data.dto.MessageInsertDto
import com.minichat.app.data.dto.MessageRowDto
import com.minichat.app.data.dto.ReactionInsertDto
import com.minichat.app.data.dto.ReplyPreviewRowDto
import com.minichat.app.data.realtime.RealtimeService
import com.minichat.app.domain.model.ConversationSummary
import com.minichat.app.domain.model.Message
import com.minichat.app.domain.model.ReplyPreview
import com.minichat.app.domain.repository.ChatRepository
import com.minichat.app.domain.repository.ConversationSignal
import com.minichat.app.domain.repository.MessageEvent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val client: SupabaseClient,
    private val realtime: RealtimeService,
    private val json: Json,
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

        // Phase 1 — the plain message page.
        //
        // This used to embed `reply_to:messages!<fk>(...)` and the reaction table in
        // a single request. The self-referencing embed is what produced
        // "Could not find a relationship between 'messages' and 'messages'"
        // (PGRST200), which rejected the WHOLE request — so loading and even
        // sending a message appeared to be broken. Reading the base table keeps the
        // hot path dependent only on columns, never on PostgREST's relationship
        // cache.
        val rows = client.from(TABLE_MESSAGES)
            .select(columns = Columns.list(*MESSAGE_COLUMNS)) {
                filter {
                    eq("conversation_id", conversationId)
                    before?.let { lt("created_at", TimeFormat.toIso(it)) }
                }
                order("created_at", Order.DESCENDING)
                limit(limit.toLong())
            }
            .decodeList<MessageRowDto>()
            .sortedBy { TimeFormat.parse(it.createdAt) ?: Instant.EPOCH }

        if (rows.isEmpty()) return@safeCall emptyList()

        // Phase 2 — reply context, one call for the whole page. Failures here are
        // cosmetic (the bubble just loses its quoted line) so they never break the
        // thread.
        val replyIds = rows.mapNotNull { it.replyToId }.distinct()
        val replies = if (replyIds.isEmpty()) emptyMap() else loadReplyPreviews(replyIds, userId)

        // Phase 3 — reactions for the page.
        val reactionsByMessage = loadReactionsFor(rows.map { it.id })

        // Phase 4 — sender names. Only groups need them (a direct chat renders
        // two people), so direct conversations skip the extra request entirely.
        val senderNames = loadSenderNames(rows.map { it.senderId }.distinct(), userId)

        rows.map { row ->
            row.toDomain(
                currentUserId = userId,
                reactions = reactionsByMessage[row.id].orEmpty(),
                replyPreview = row.replyToId?.let { replies[it] },
            ).copy(senderName = senderNames[row.senderId].orEmpty())
        }
    }

    override suspend fun sendMessage(
        conversationId: String,
        content: String,
        replyToId: String?,
    ): AppResult<Message> = safeCall {
        val userId = me() ?: error("You must be signed in to send a message.")
        val trimmed = content.trim()
        if (trimmed.isEmpty()) error("Messages can't be empty.")

        // The insert asks only for plain columns. No embed on the return payload is
        // what makes sending immune to relationship-cache problems.
        val row = client.from(TABLE_MESSAGES)
            .insert(
                MessageInsertDto(
                    conversationId = conversationId,
                    senderId = userId,
                    content = trimmed,
                    replyToId = replyToId,
                ),
            ) {
                select(columns = Columns.list(*MESSAGE_COLUMNS))
            }
            .decodeSingle<MessageRowDto>()

        val replyPreview = replyToId?.let { loadReplyPreviews(listOf(it), userId)[it] }
        row.toDomain(currentUserId = userId, replyPreview = replyPreview)
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

    /**
     * Display names for the senders on a page of messages.
     *
     * This is a plain profile read, not an embed: group bubbles need a name per
     * sender, and reading `profiles` by id is both cheaper and immune to the
     * PostgREST relationship problems that broke embeds before.
     */
    private suspend fun loadSenderNames(senderIds: List<String>, myId: String): Map<String, String> {
        val others = senderIds.filter { it.isNotBlank() && it != myId }
        if (others.isEmpty()) return emptyMap()

        return runCatching {
            client.from(TABLE_PROFILES)
                .select(columns = Columns.list("id", "display_name")) {
                    filter { isIn("id", others) }
                }
                .decodeList<SenderNameDto>()
                .associate { it.id to it.displayName }
        }.getOrDefault(emptyMap())
    }

    // -------------------------------------------------------------------------
    // Voice notes
    // -------------------------------------------------------------------------

    /**
     * Uploads the clip to the private `chat-media` bucket, then posts a `voice`
     * message. The object path is prefixed with the conversation id because the
     * storage policy checks membership against that first path segment — an
     * outsider with a guessed path still cannot read the audio.
     *
     * If the insert fails after the upload, the object is deleted so no orphan
     * audio is left behind.
     */
    override suspend fun sendVoiceMessage(
        conversationId: String,
        audio: ByteArray,
        durationMs: Int,
        replyToId: String?,
    ): AppResult<Message> = safeCall {
        val userId = me() ?: error("You must be signed in to send a voice message.")
        if (audio.isEmpty()) error("That recording was empty. Try again.")
        if (durationMs <= 0) error("That recording was too short.")
        if (durationMs > MAX_VOICE_MS) error("Voice notes are limited to ${MAX_VOICE_MS / 1000} seconds.")

        val path = "$conversationId/voice_${System.currentTimeMillis()}.m4a"
        val bucket = client.storage.from(BUCKET_CHAT_MEDIA)
        bucket.upload(path, audio) { upsert = false }

        try {
            val row = client.from(TABLE_MESSAGES)
                .insert(
                    MessageInsertDto(
                        conversationId = conversationId,
                        senderId = userId,
                        content = "",
                        replyToId = replyToId,
                        messageType = "voice",
                        mediaUrl = path,
                        durationMs = durationMs,
                    ),
                ) {
                    select(columns = Columns.list(*MESSAGE_COLUMNS))
                }
                .decodeSingle<MessageRowDto>()

            val replyPreview = replyToId?.let { loadReplyPreviews(listOf(it), userId)[it] }
            row.toDomain(currentUserId = userId, replyPreview = replyPreview)
        } catch (failure: Throwable) {
            runCatching { bucket.delete(path) }
            throw failure
        }
    }

    override suspend fun signedMediaUrl(path: String, expiresInSeconds: Int): AppResult<String> = safeCall {
        if (me() == null) error("You must be signed in.")
        client.storage.from(BUCKET_CHAT_MEDIA)
            .createSignedUrl(path, expiresInSeconds.coerceIn(60, 86_400))
    }

    // -------------------------------------------------------------------------
    // Reply previews + reactions (replace the self embed)
    // -------------------------------------------------------------------------

    /**
     * Hydrates the replied-to messages for a page of messages in one RPC call.
     *
     * `get_reply_previews` is SECURITY INVOKER and checks conversation membership
     * row by row, so it can never be used to read a message outside the caller's
     * own conversations.
     *
     * If the RPC is unavailable (for example an older database), we fall back to a
     * targeted row read. The fallback is best-effort: a missing preview must never
     * take the conversation down with it.
     */
    private suspend fun loadReplyPreviews(
        messageIds: List<String>,
        currentUserId: String,
    ): Map<String, ReplyPreview> {
        val viaRpc = runCatching {
            val raw = client.postgrest.rpc(
                function = "get_reply_previews",
                parameters = buildJsonObject {
                    put("p_message_ids", kotlinx.serialization.json.JsonArray(
                        messageIds.map { kotlinx.serialization.json.JsonPrimitive(it) },
                    ))
                },
            ).data
            json.decodeFromString<List<ReplyPreviewRowDto>>(raw)
        }.getOrNull()

        val rows = viaRpc ?: runCatching {
            client.from(TABLE_MESSAGES)
                .select(columns = Columns.list("id", "sender_id", "content", "is_deleted")) {
                    filter { isIn("id", messageIds) }
                }
                .decodeList<ReplyPreviewRowDto>()
        }.getOrNull().orEmpty()

        return rows.associate { row ->
            row.id to ReplyPreview(
                messageId = row.id,
                senderName = when {
                    row.senderId == currentUserId -> "You"
                    !row.displayName.isNullOrBlank() -> row.displayName!!
                    !row.username.isNullOrBlank() -> row.username!!
                    else -> "Message"
                },
                snippet = if (row.isDeleted) "Deleted message" else row.content.take(140),
            )
        }
    }

    /** One request for every reaction on the visible page. */
    private suspend fun loadReactionsFor(
        messageIds: List<String>,
    ): Map<String, List<com.minichat.app.domain.model.Reaction>> = runCatching {
        client.from(TABLE_REACTIONS)
            .select(columns = Columns.list("id", "message_id", "user_id", "emoji")) {
                filter { isIn("message_id", messageIds) }
            }
            .decodeList<com.minichat.app.data.dto.ReactionDto>()
            .groupBy { it.messageId }
            .mapValues { (_, list) -> list.map { it.toDomain() } }
    }.getOrDefault(emptyMap())

    private companion object {
        const val VIEW_CONVERSATIONS = "conversation_overview"
        const val TABLE_MESSAGES = "messages"
        const val TABLE_PROFILES = "profiles"
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
         * Plain columns only — deliberately no `reply_to:...` embed. See
         * [loadMessages] for why.
         */
        val MESSAGE_COLUMNS = arrayOf(
            "id",
            "conversation_id",
            "sender_id",
            "content",
            "reply_to_id",
            "is_edited",
            "is_deleted",
            "created_at",
            "updated_at",
            "message_type",
            "media_url",
            "duration_ms",
        )

        const val BUCKET_CHAT_MEDIA = "chat-media"

        /** Hard ceiling for a voice note; the UI stops recording at 7s anyway. */
        const val MAX_VOICE_MS = 60_000
    }
}

/** Just enough of a profile to label a group bubble. */
@kotlinx.serialization.Serializable
internal data class SenderNameDto(
    val id: String,
    @kotlinx.serialization.SerialName("display_name") val displayName: String = "",
)
