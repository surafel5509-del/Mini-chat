package com.minichat.app.data.dto

import com.minichat.app.core.util.TimeFormat
import com.minichat.app.domain.model.Call
import com.minichat.app.domain.model.CallHistoryItem
import com.minichat.app.domain.model.CallStatus
import com.minichat.app.domain.model.CallType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CallDto(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("caller_id") val callerId: String,
    @SerialName("callee_id") val calleeId: String,
    @SerialName("call_type") val callType: String = "voice",
    val status: String = "ringing",
    @SerialName("room_name") val roomName: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("answered_at") val answeredAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
    @SerialName("ended_by") val endedBy: String? = null,
    @SerialName("failure_reason") val failureReason: String? = null,
) {
    fun toDomain(): Call = Call(
        id = id,
        conversationId = conversationId,
        callerId = callerId,
        calleeId = calleeId,
        type = CallType.from(callType),
        status = CallStatus.from(status),
        roomName = roomName,
        createdAt = TimeFormat.parse(createdAt),
        answeredAt = TimeFormat.parse(answeredAt),
        endedAt = TimeFormat.parse(endedAt),
        durationSeconds = durationSeconds,
        endedBy = endedBy,
        failureReason = failureReason,
    )
}

@Serializable
data class CallHistoryDto(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("caller_id") val callerId: String,
    @SerialName("callee_id") val calleeId: String,
    @SerialName("call_type") val callType: String = "voice",
    val status: String = "ended",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("answered_at") val answeredAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
    @SerialName("is_outgoing") val isOutgoing: Boolean = false,
    @SerialName("peer_id") val peerId: String = "",
    @SerialName("peer_display_name") val peerDisplayName: String? = null,
    @SerialName("peer_username") val peerUsername: String? = null,
    @SerialName("peer_avatar_url") val peerAvatarUrl: String? = null,
) {
    fun toDomain(): CallHistoryItem = CallHistoryItem(
        call = Call(
            id = id,
            conversationId = conversationId,
            callerId = callerId,
            calleeId = calleeId,
            type = CallType.from(callType),
            status = CallStatus.from(status),
            roomName = "",
            createdAt = TimeFormat.parse(createdAt),
            answeredAt = TimeFormat.parse(answeredAt),
            endedAt = TimeFormat.parse(endedAt),
            durationSeconds = durationSeconds,
        ),
        isOutgoing = isOutgoing,
        peerId = peerId,
        peerDisplayName = peerDisplayName?.takeIf { it.isNotBlank() }
            ?: peerUsername?.takeIf { it.isNotBlank() }
            ?: "MINI CHAT user",
        peerUsername = peerUsername.orEmpty(),
        peerAvatarUrl = peerAvatarUrl,
    )
}

@Serializable
data class LiveKitTokenDto(
    val url: String = "",
    val token: String = "",
    val room: String = "",
    val identity: String = "",
    @SerialName("expires_in") val expiresIn: Int = 0,
)

@Serializable
data class LiveKitHealthDto(
    val configured: Boolean = false,
    @SerialName("livekitUrl") val livekitUrl: String? = null,
)

@Serializable
data class EdgeFunctionErrorDto(
    val error: String = "",
    val message: String? = null,
)
