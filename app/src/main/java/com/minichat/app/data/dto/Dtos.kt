package com.minichat.app.data.dto

import com.minichat.app.core.util.TimeFormat
import com.minichat.app.domain.model.BlockedUser
import com.minichat.app.domain.model.ConversationSummary
import com.minichat.app.domain.model.Message
import com.minichat.app.domain.model.MessagePreview
import com.minichat.app.domain.model.MessageStatus
import com.minichat.app.domain.model.Profile
import com.minichat.app.domain.model.Reaction
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Row of `public.profiles`. */
@Serializable
data class ProfileDto(
    val id: String,
    val username: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val bio: String? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
) {
    fun toDomain(): Profile = Profile(
        id = id,
        username = username,
        displayName = displayName.ifBlank { username },
        avatarUrl = avatarUrl,
        bio = bio,
        lastSeenAt = TimeFormat.parse(lastSeenAt),
        createdAt = TimeFormat.parse(createdAt),
    )
}

/** Row of the `conversation_overview` view. */
@Serializable
data class ConversationOverviewDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("peer_id") val peerId: String? = null,
    @SerialName("peer_display_name") val peerDisplayName: String? = null,
    @SerialName("peer_username") val peerUsername: String? = null,
    @SerialName("peer_avatar_url") val peerAvatarUrl: String? = null,
    @SerialName("peer_last_seen_at") val peerLastSeenAt: String? = null,
    @SerialName("last_message_id") val lastMessageId: String? = null,
    @SerialName("last_message_content") val lastMessageContent: String? = null,
    @SerialName("last_message_sender_id") val lastMessageSenderId: String? = null,
    @SerialName("last_message_at") val lastMessageAt: String? = null,
    @SerialName("last_message_is_deleted") val lastMessageIsDeleted: Boolean = false,
    @SerialName("unread_count") val unreadCount: Long = 0,
    @SerialName("is_muted") val isMuted: Boolean = false,
    @SerialName("is_pinned") val isPinned: Boolean = false,
    @SerialName("last_read_at") val lastReadAt: String? = null,
    @SerialName("my_id") val myId: String? = null,
    @SerialName("conversation_updated_at") val conversationUpdatedAt: String? = null,
) {
    fun toDomain(currentUserId: String): ConversationSummary {
        val peer = if (peerId != null) {
            Profile(
                id = peerId,
                username = peerUsername.orEmpty(),
                displayName = peerDisplayName.orEmpty().ifBlank { peerUsername.orEmpty() },
                avatarUrl = peerAvatarUrl,
                lastSeenAt = TimeFormat.parse(peerLastSeenAt),
            )
        } else {
            null
        }

        val preview = lastMessageId?.let { id ->
            MessagePreview(
                messageId = id,
                text = lastMessageContent.orEmpty(),
                senderId = lastMessageSenderId,
                sentAt = TimeFormat.parse(lastMessageAt),
                isDeleted = lastMessageIsDeleted,
                isMine = lastMessageSenderId == currentUserId,
            )
        }

        return ConversationSummary(
            id = conversationId,
            peer = peer,
            lastMessage = preview,
            lastActivityAt = TimeFormat.parse(lastMessageAt)
                ?: TimeFormat.parse(conversationUpdatedAt),
            unreadCount = unreadCount.coerceAtLeast(0).toInt(),
            isPinned = isPinned,
            isMuted = isMuted,
        )
    }
}

/** Row of `public.messages`. */
@Serializable
data class MessageDto(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("sender_id") val senderId: String,
    val content: String = "",
    @SerialName("reply_to_id") val replyToId: String? = null,
    @SerialName("is_edited") val isEdited: Boolean = false,
    @SerialName("is_deleted") val isDeleted: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    fun toDomain(currentUserId: String): Message = Message(
        id = id,
        conversationId = conversationId,
        senderId = senderId,
        content = content,
        createdAt = TimeFormat.parse(createdAt),
        updatedAt = TimeFormat.parse(updatedAt),
        isEdited = isEdited,
        isDeleted = isDeleted,
        replyToId = replyToId,
        status = if (senderId == currentUserId) MessageStatus.Sent else MessageStatus.Delivered,
        isMine = senderId == currentUserId,
    )
}

/** Payload for inserting a message. Server sets timestamps, flags and validates membership. */
@Serializable
data class MessageInsertDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("sender_id") val senderId: String,
    val content: String,
    @SerialName("reply_to_id") val replyToId: String? = null,
)

/** Row of `public.message_reactions`. */
@Serializable
data class ReactionDto(
    val id: String,
    @SerialName("message_id") val messageId: String,
    @SerialName("user_id") val userId: String,
    val emoji: String,
) {
    fun toDomain(): Reaction = Reaction(id = id, messageId = messageId, userId = userId, emoji = emoji)
}

@Serializable
data class ReactionInsertDto(
    @SerialName("message_id") val messageId: String,
    @SerialName("user_id") val userId: String,
    val emoji: String,
)

/** Row of `public.user_blocks` joined with the blocked profile. */
@Serializable
data class UserBlockDto(
    val id: String,
    @SerialName("blocker_id") val blockerId: String = "",
    @SerialName("blocked_id") val blockedId: String,
    @SerialName("created_at") val createdAt: String? = null,
    val profiles: ProfileDto? = null,
) {
    fun toDomain(fallbackProfile: Profile?): BlockedUser? {
        val profile = profiles?.toDomain() ?: fallbackProfile ?: return null
        return BlockedUser(blockId = id, profile = profile, blockedAt = TimeFormat.parse(createdAt))
    }
}

@Serializable
data class BlockInsertDto(
    @SerialName("blocker_id") val blockerId: String,
    @SerialName("blocked_id") val blockedId: String,
)

@Serializable
data class ReportInsertDto(
    @SerialName("reporter_id") val reporterId: String,
    @SerialName("reported_user_id") val reportedUserId: String,
    val reason: String,
)

@Serializable
data class DeviceTokenDto(
    @SerialName("fcm_token") val token: String,
)

/** Minimal row used to read the peer's read cursor. */
@Serializable
data class ConversationMemberDto(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("last_read_at") val lastReadAt: String? = null,
    @SerialName("is_muted") val isMuted: Boolean = false,
    @SerialName("is_pinned") val isPinned: Boolean = false,
)

/** Message row as read from `public.messages` — plain columns only, never an embed. */
@Serializable
data class MessageRowDto(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("sender_id") val senderId: String,
    val content: String = "",
    @SerialName("reply_to_id") val replyToId: String? = null,
    @SerialName("is_edited") val isEdited: Boolean = false,
    @SerialName("is_deleted") val isDeleted: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    /**
     * Reaction and reply context are supplied by the repository (loaded in bulk
     * after the page arrives) rather than by a PostgREST embed. Keeping the DTO
     * embed-free is what makes message reads and sends immune to the
     * "could not find a relationship between 'messages' and 'messages'" failure.
     */
    fun toDomain(
        currentUserId: String,
        reactions: List<Reaction> = emptyList(),
        replyPreview: com.minichat.app.domain.model.ReplyPreview? = null,
    ): Message = Message(
        id = id,
        conversationId = conversationId,
        senderId = senderId,
        content = content,
        createdAt = TimeFormat.parse(createdAt),
        updatedAt = TimeFormat.parse(updatedAt),
        isEdited = isEdited,
        isDeleted = isDeleted,
        replyToId = replyToId,
        replyPreview = replyPreview,
        reactions = reactions,
        status = if (senderId == currentUserId) MessageStatus.Sent else MessageStatus.Delivered,
        isMine = senderId == currentUserId,
    )
}

/** Minimal row for the joined profile when the app needs to render a sender name. */
@Serializable
data class MessageWithProfileDto(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("sender_id") val senderId: String,
    val content: String = "",
    @SerialName("reply_to_id") val replyToId: String? = null,
    @SerialName("is_edited") val isEdited: Boolean = false,
    @SerialName("is_deleted") val isDeleted: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val profiles: ProfileDto? = null,
)

// -----------------------------------------------------------------------------
// Notes
// -----------------------------------------------------------------------------

/** Row of `public.notes` with the author's public profile embedded. */
@Serializable
data class NoteDto(
    @SerialName("id") val id: String = "",
    @SerialName("author_id") val authorId: String,
    val body: String = "",
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("profiles") val author: ProfileDto? = null,
) {
    fun toDomain(): com.minichat.app.domain.model.Note? {
        val expiry = TimeFormat.parse(expiresAt) ?: return null
        return com.minichat.app.domain.model.Note(
            authorId = authorId,
            body = body,
            expiresAt = expiry,
            author = author?.toDomain(),
        )
    }
}

/** Payload for the `set_my_note` RPC. */
@Serializable
data class SetNoteParams(
    @SerialName("p_body") val body: String,
    @SerialName("p_minutes") val minutes: Int,
)

/** One row returned by `get_reply_previews(uuid[])`. */
@Serializable
data class ReplyPreviewRowDto(
    val id: String,
    @SerialName("sender_id") val senderId: String = "",
    val content: String = "",
    @SerialName("is_deleted") val isDeleted: Boolean = false,
    @SerialName("display_name") val displayName: String? = null,
    val username: String? = null,
)
