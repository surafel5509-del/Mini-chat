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
    // Partial embeds (a name inside a message row, for example) omit the id.
    val id: String = "",
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
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("peer_id") val peerId: String? = null,
    @SerialName("peer_display_name") val peerDisplayName: String? = null,
    @SerialName("peer_username") val peerUsername: String? = null,
    @SerialName("peer_avatar_url") val peerAvatarUrl: String? = null,
    @SerialName("peer_last_seen_at") val peerLastSeenAt: String? = null,
    @SerialName("last_message_id") val lastMessageId: String? = null,
    @SerialName("last_message_content") val lastMessageContent: String? = null,
    @SerialName("last_message_sender_id") val lastMessageSenderId: String? = null,
    @SerialName("last_message_at") val lastMessageAt: String? = null,
    @SerialName("last_message_is_deleted") val lastMessageIsDeleted: Boolean? = null,
    @SerialName("unread_count") val unreadCount: Long? = null,
    @SerialName("is_muted") val isMuted: Boolean? = null,
    @SerialName("is_pinned") val isPinned: Boolean? = null,
    @SerialName("last_read_at") val lastReadAt: String? = null,
    @SerialName("my_id") val myId: String? = null,
    @SerialName("conversation_updated_at") val conversationUpdatedAt: String? = null,
    @SerialName("conversation_type") val conversationType: String? = null,
    @SerialName("conversation_title") val conversationTitle: String? = null,
    @SerialName("conversation_avatar_url") val conversationAvatarUrl: String? = null,
    @SerialName("member_count") val memberCount: Long? = null,
    @SerialName("my_role") val myRole: String? = null,
) {
    /** Returns null for a row the view could not resolve (should not happen, but never crash). */
    fun toDomainOrNull(currentUserId: String): ConversationSummary? {
        val id = conversationId?.takeIf { it.isNotBlank() } ?: return null
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
                isDeleted = lastMessageIsDeleted == true,
                isMine = lastMessageSenderId == currentUserId,
            )
        }

        val type = com.minichat.app.domain.model.ConversationType.from(conversationType)

        return ConversationSummary(
            id = id,
            peer = peer,
            type = type,
            title = conversationTitle,
            avatarUrl = conversationAvatarUrl,
            memberCount = (memberCount ?: 0L).toInt(),
            myRole = myRole,
            lastMessage = preview,
            lastActivityAt = TimeFormat.parse(lastMessageAt)
                ?: TimeFormat.parse(conversationUpdatedAt),
            unreadCount = (unreadCount ?: 0L).coerceAtLeast(0).toInt(),
            isPinned = isPinned == true,
            isMuted = isMuted == true,
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
    fun toDomain(
        currentUserId: String,
        replyPreviews: Map<String, com.minichat.app.domain.model.ReplyPreview> = emptyMap(),
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

/** Message row with its reactions and the replied-to preview embedded in one request. */
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
    @SerialName("message_reactions") val reactions: List<ReactionDto> = emptyList(),
    @SerialName("reply_to") val replyTo: ReplyTargetDto? = null,
    val profiles: ProfileDto? = null,
) {
    fun toDomain(
        currentUserId: String,
        replyPreviews: Map<String, com.minichat.app.domain.model.ReplyPreview> = emptyMap(),
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
        replyPreview = replyToId?.let { replyPreviews[it] } ?: replyTo?.let { target ->
            com.minichat.app.domain.model.ReplyPreview(
                messageId = target.id,
                senderName = target.profiles?.displayName?.ifBlank { target.profiles.username }
                    ?: if (target.senderId == currentUserId) "You" else "Message",
                snippet = if (target.isDeleted) "Deleted message" else target.content.take(120),
            )
        },
        reactions = reactions.map { it.toDomain() },
        status = if (senderId == currentUserId) MessageStatus.Sent else MessageStatus.Delivered,
        senderName = profiles?.displayName.orEmpty(),
        isMine = senderId == currentUserId,
    )
}

/** Row of `public.notes` — a user's status note. */
@Serializable
data class NoteDto(
    val id: String? = null,
    @SerialName("author_id") val authorId: String = "",
    val body: String = "",
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
) {
    fun toDomain(): com.minichat.app.domain.model.UserNote =
        com.minichat.app.domain.model.UserNote(
            userId = authorId,
            body = body,
            expiresAt = TimeFormat.parse(expiresAt),
            createdAt = TimeFormat.parse(createdAt),
        )
}

/** Row of the `conversation_theme` RPC. */
@Serializable
data class ConversationThemeDto(
    val title: String? = null,
    val description: String? = null,
    @SerialName("conversation_type") val conversationType: String? = null,
    val wallpaper: String? = null,
    @SerialName("bubble_color") val bubbleColor: String? = null,
    @SerialName("accent_color") val accentColor: String? = null,
    @SerialName("member_count") val memberCount: Int? = null,
    @SerialName("my_role") val myRole: String? = null,
) {
    fun toDomain(): com.minichat.app.domain.model.ConversationTheme =
        com.minichat.app.domain.model.ConversationTheme(
            title = title,
            description = description,
            type = com.minichat.app.domain.model.ConversationType.from(conversationType),
            wallpaper = wallpaper,
            bubbleColor = bubbleColor,
            accentColor = accentColor,
            memberCount = memberCount ?: 0,
            myRole = myRole,
        )
}

/** Row of the `conversation_receipts` RPC. */
@Serializable
data class SeenReceiptDto(
    @SerialName("user_id") val userId: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val username: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("last_read_at") val lastReadAt: String? = null,
    @SerialName("member_role") val memberRole: String? = null,
) {
    fun toDomain(): com.minichat.app.domain.model.SeenReceipt? {
        val id = userId ?: return null
        return com.minichat.app.domain.model.SeenReceipt(
            userId = id,
            displayName = displayName.orEmpty(),
            username = username.orEmpty(),
            avatarUrl = avatarUrl,
            lastReadAt = TimeFormat.parse(lastReadAt),
            role = memberRole,
        )
    }
}

/** Row of the `get_reply_previews` RPC: the quoted message of a reply, with its author. */
@Serializable
data class ReplyPreviewRowDto(
    val id: String? = null,
    @SerialName("sender_id") val senderId: String? = null,
    val content: String? = null,
    @SerialName("is_deleted") val isDeleted: Boolean? = null,
    @SerialName("display_name") val displayName: String? = null,
    val username: String? = null,
)

@Serializable
data class ReplyTargetDto(
    val id: String,
    @SerialName("sender_id") val senderId: String = "",
    val content: String = "",
    @SerialName("is_deleted") val isDeleted: Boolean = false,
    val profiles: ProfileDto? = null,
)

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
