package com.minichat.app.domain.model

import java.time.Instant

/** A MINI CHAT person. */
data class Profile(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val bio: String? = null,
    val lastSeenAt: Instant? = null,
    val createdAt: Instant? = null,
) {
    val initialsSource: String get() = displayName.ifBlank { username }
    val handle: String get() = "@$username"
}

enum class MessageStatus {
    /** Optimistic local echo, still in flight. */
    Sending,

    /** Accepted by the server. */
    Sent,

    /** The other member's device received it (conversation membership updated). */
    Delivered,

    /** The other member opened the conversation after this message. */
    Read,

    /** Failed and can be retried by tapping. */
    Failed,
}

data class Reaction(
    val id: String,
    val messageId: String,
    val userId: String,
    val emoji: String,
)

/** Compact information about the message a reply points at. */
data class ReplyPreview(
    val messageId: String,
    val senderName: String,
    val snippet: String,
)

data class Message(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val content: String,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val isEdited: Boolean = false,
    val isDeleted: Boolean = false,
    val replyToId: String? = null,
    val replyPreview: ReplyPreview? = null,
    val reactions: List<Reaction> = emptyList(),
    val status: MessageStatus = MessageStatus.Sent,
    val senderName: String = "",
    val isMine: Boolean = false,
) {

    companion object {
        fun preview(content: String, isDeleted: Boolean): String = when {
            isDeleted -> "This message was deleted"
            content.isBlank() -> ""
            else -> content
        }
    }
}

data class MessagePreview(
    val messageId: String,
    val text: String,
    val senderId: String?,
    val sentAt: Instant?,
    val isDeleted: Boolean = false,
    val isMine: Boolean = false,
)

/** Row rendered on the home screen. */
data class ConversationSummary(
    val id: String,
    val peer: Profile?,
    val lastMessage: MessagePreview?,
    val lastActivityAt: Instant?,
    val unreadCount: Int = 0,
    val isPinned: Boolean = false,
    val isMuted: Boolean = false,
    val peerTyping: Boolean = false,
) {
    val displayName: String get() = peer?.displayName ?: "Unknown"
    val sortKey: Instant get() = lastActivityAt ?: Instant.EPOCH
}

/** Result of opening a direct conversation with somebody. */
data class StartedConversation(
    val conversationId: String,
    val peer: Profile?,
)

data class BlockedUser(
    val blockId: String,
    val profile: Profile,
    val blockedAt: Instant?,
)

/** Aggregated reaction for a bubble's chip row. */
data class ReactionSummary(
    val emoji: String,
    val count: Int,
    val reactedByMe: Boolean,
)
