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
    val messageType: MessageType = MessageType.Text,
    val mediaUrl: String? = null,
    val durationMs: Int? = null,
    /** Voice notes expire after 24h; text messages never do. */
    val expiresAt: Instant? = null,
) {
    val isVoice: Boolean get() = messageType == MessageType.Voice
    val isSystem: Boolean get() = messageType == MessageType.System
    val isExpired: Boolean
        get() = expiresAt?.isBefore(Instant.now()) == true

    /** "0:07" style label for a voice note. */
    val durationLabel: String
        get() {
            val totalSeconds = ((durationMs ?: 0) / 1000).coerceAtLeast(0)
            return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
        }

    /** Text the bubble shows, with media / system fallbacks. */
    val displayText: String
        get() = when {
            isDeleted -> "This message was deleted"
            isExpired -> "Voice message expired"
            messageType == MessageType.Voice -> "Voice message"
            else -> content
        }

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
    val messageType: MessageType = MessageType.Text,
    val durationMs: Int? = null,
    val senderName: String? = null,
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
    val isGroup: Boolean = false,
    val title: String? = null,
    val groupAvatarUrl: String? = null,
    val description: String? = null,
    val memberCount: Int = 1,
    val myRole: GroupRole = GroupRole.Member,
    val lastMessageSenderName: String? = null,
) {
    val displayName: String get() = if (isGroup) title?.ifBlank { "Group" } ?: "Group" else peer?.displayName ?: "Unknown"
    val sortKey: Instant get() = lastActivityAt ?: Instant.EPOCH
    val avatarUrl: String? get() = if (isGroup) groupAvatarUrl else peer?.avatarUrl
    val handleLabel: String
        get() = if (isGroup) {
            when (memberCount) {
                1 -> "1 member"
                else -> "$memberCount members"
            }
        } else {
            peer?.handle.orEmpty()
        }
    val subtitleLabel: String get() = if (isGroup) subtitleLabelGroup else handleLabel
    private val subtitleLabelGroup: String get() = "$memberCount members"
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

/**
 * An ephemeral status note — the Instagram-style "share note" surface.
 *
 * A note is a short (≤80 chars) message a person pins above their profile and
 * shows to friends for up to 24 hours. Only one note is active per author; it is
 * replaced, never duplicated. Expiry is enforced by the database, so an expired
 * note can never be read by a client even if the screen is left open.
 */
data class Note(
    val authorId: String,
    val body: String,
    val expiresAt: Instant,
    val author: Profile? = null,
) {
    fun isMine(currentUserId: String?): Boolean = authorId == currentUserId

    /** Hours of life left, floored at zero — used for the countdown pill. */
    fun hoursLeft(now: Instant = Instant.now()): Long =
        java.time.Duration.between(now, expiresAt).toHours().coerceAtLeast(0)

    /** True while the note is still visible. */
    fun isLive(now: Instant = Instant.now()): Boolean = expiresAt.isAfter(now)
}

/** A friend who is showing a note right now (drives the story row). */
data class FriendNote(
    val note: Note,
    val author: Profile,
)

/** Roles inside a group conversation. Mirrors `conversation_members.role`. */
enum class GroupRole {
    Owner,
    Admin,
    Member;

    val isAdmin: Boolean get() = this == Owner || this == Admin

    companion object {
        fun from(value: String?): GroupRole = when (value?.lowercase()) {
            "owner" -> Owner
            "admin" -> Admin
            else -> Member
        }
    }
}

/** What kind of content a bubble holds. */
enum class MessageType {
    Text,
    Voice,
    Image,
    System;

    companion object {
        fun from(value: String?): MessageType = when (value?.lowercase()) {
            "voice" -> Voice
            "image" -> Image
            "system" -> System
            else -> Text
        }
    }
}

/** A member of a conversation, with the public profile that renders them. */
data class ConversationMember(
    val userId: String,
    val profile: Profile,
    val role: GroupRole = GroupRole.Member,
    val joinedAt: Instant? = null,
    val lastReadAt: Instant? = null,
)
