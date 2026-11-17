package com.minichat.app.domain.model

import java.time.Instant

/** What kind of media a call carries. */
enum class CallType(val wire: String) {
    Voice("voice"),
    Video("video"),
    ;

    val isVideo: Boolean get() = this == Video

    companion object {
        fun from(raw: String?): CallType =
            entries.firstOrNull { it.wire.equals(raw, ignoreCase = true) } ?: Voice
    }
}

/**
 * Mirrors the `calls.status` column. The server is the source of truth: the client renders what
 * Postgres says and only ever asks for transitions through the RPCs.
 */
enum class CallStatus(val wire: String) {
    Ringing("ringing"),
    Active("active"),
    Ended("ended"),
    Declined("declined"),
    Missed("missed"),
    Cancelled("cancelled"),
    Failed("failed"),
    ;

    val isTerminal: Boolean
        get() = this == Ended || this == Declined || this == Missed || this == Cancelled || this == Failed

    val isLive: Boolean
        get() = this == Ringing || this == Active

    companion object {
        fun from(raw: String?): CallStatus =
            entries.firstOrNull { it.wire.equals(raw, ignoreCase = true) } ?: Ended
    }
}

/** One row of `public.calls`. */
data class Call(
    val id: String,
    val conversationId: String,
    val callerId: String,
    val calleeId: String,
    val type: CallType,
    val status: CallStatus,
    val roomName: String,
    val createdAt: Instant? = null,
    val answeredAt: Instant? = null,
    val endedAt: Instant? = null,
    val durationSeconds: Int? = null,
    val endedBy: String? = null,
    val failureReason: String? = null,
) {
    fun isCaller(userId: String?): Boolean = userId != null && userId == callerId

    fun involves(userId: String?): Boolean = userId != null && (userId == callerId || userId == calleeId)

    fun peerId(userId: String?): String = if (isCaller(userId)) calleeId else callerId

    /** Seconds the call has been connected for, or null when it never connected. */
    fun connectedSeconds(now: Instant = Instant.now()): Long? {
        val answered = answeredAt ?: return durationSeconds?.toLong()
        val end = endedAt ?: now
        return (end.toEpochMilli() - answered.toEpochMilli()).coerceAtLeast(0) / 1000
    }
}

/** A row of `call_history()` — the call plus the other person's display data. */
data class CallHistoryItem(
    val call: Call,
    val isOutgoing: Boolean,
    val peerId: String,
    val peerDisplayName: String,
    val peerUsername: String,
    val peerAvatarUrl: String? = null,
)

/** What the LiveKit Edge Function hands back for a single call. */
data class CallCredentials(
    val url: String,
    val token: String,
    val roomName: String,
)
