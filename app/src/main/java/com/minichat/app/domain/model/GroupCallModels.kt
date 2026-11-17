package com.minichat.app.domain.model

import java.time.Instant

/**
 * A multi-party call. Unlike [Call] it is not tied to a conversation: the host invites people by
 * id and everyone joins the same LiveKit room.
 */
data class GroupCall(
    val id: String,
    val hostId: String,
    val title: String,
    val type: CallType,
    val status: CallStatus,
    val roomName: String,
    val maxParticipants: Int = 16,
    val createdAt: Instant? = null,
    val startedAt: Instant? = null,
    val endedAt: Instant? = null,
    val durationSeconds: Int? = null,
    val endedBy: String? = null,
) {
    val isLive: Boolean get() = !status.isTerminal

    fun isHost(userId: String?): Boolean = userId != null && userId == hostId

    fun connectedSeconds(now: Instant = Instant.now()): Long {
        val started = startedAt ?: return durationSeconds?.toLong() ?: 0L
        val end = endedAt ?: now
        return (end.toEpochMilli() - started.toEpochMilli()).coerceAtLeast(0) / 1000
    }
}

/** One row of `group_call_participants()` — who was invited and who is actually inside. */
data class GroupCallParticipant(
    val userId: String,
    val displayName: String,
    val username: String,
    val avatarUrl: String? = null,
    val status: ParticipationStatus,
    val joinedAt: Instant? = null,
    val isHost: Boolean = false,
) {
    enum class ParticipationStatus(val wire: String) {
        Invited("invited"),
        Joined("joined"),
        Left("left"),
        Declined("declined"),
        Missed("missed"),
        ;

        val isInCall: Boolean get() = this == Joined

        companion object {
            fun from(raw: String?): ParticipationStatus =
                entries.firstOrNull { it.wire.equals(raw, ignoreCase = true) } ?: Invited
        }
    }
}

/** A past group call as shown in the call log. */
data class GroupCallHistoryItem(
    val call: GroupCall,
    val hostName: String,
    val isHost: Boolean,
    val participantCount: Int,
)
