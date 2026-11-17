package com.minichat.app.data.dto

import com.minichat.app.core.util.TimeFormat
import com.minichat.app.domain.model.CallStatus
import com.minichat.app.domain.model.CallType
import com.minichat.app.domain.model.GroupCall
import com.minichat.app.domain.model.GroupCallHistoryItem
import com.minichat.app.domain.model.GroupCallParticipant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GroupCallDto(
    val id: String,
    @SerialName("host_id") val hostId: String,
    val title: String = "Group call",
    @SerialName("call_type") val callType: String = "video",
    val status: String = "active",
    @SerialName("room_name") val roomName: String = "",
    @SerialName("max_participants") val maxParticipants: Int = 16,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
    @SerialName("ended_by") val endedBy: String? = null,
) {
    fun toDomain(): GroupCall = GroupCall(
        id = id,
        hostId = hostId,
        title = title,
        type = CallType.from(callType),
        status = CallStatus.from(status),
        roomName = roomName,
        maxParticipants = maxParticipants,
        createdAt = TimeFormat.parse(createdAt),
        startedAt = TimeFormat.parse(startedAt),
        endedAt = TimeFormat.parse(endedAt),
        durationSeconds = durationSeconds,
        endedBy = endedBy,
    )
}

@Serializable
data class GroupCallParticipantDto(
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String? = null,
    val username: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val status: String = "invited",
    @SerialName("joined_at") val joinedAt: String? = null,
    @SerialName("is_host") val isHost: Boolean = false,
) {
    fun toDomain(): GroupCallParticipant = GroupCallParticipant(
        userId = userId,
        displayName = displayName?.takeIf { it.isNotBlank() }
            ?: username?.takeIf { it.isNotBlank() }
            ?: "MINI CHAT user",
        username = username.orEmpty(),
        avatarUrl = avatarUrl,
        status = GroupCallParticipant.ParticipationStatus.from(status),
        joinedAt = TimeFormat.parse(joinedAt),
        isHost = isHost,
    )
}

@Serializable
data class GroupCallHistoryDto(
    val id: String,
    val title: String = "Group call",
    @SerialName("call_type") val callType: String = "video",
    val status: String = "ended",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
    @SerialName("host_id") val hostId: String,
    @SerialName("host_name") val hostName: String? = null,
    @SerialName("is_host") val isHost: Boolean = false,
    @SerialName("participant_count") val participantCount: Int = 0,
) {
    fun toDomain(): GroupCallHistoryItem = GroupCallHistoryItem(
        call = GroupCall(
            id = id,
            hostId = hostId,
            title = title,
            type = CallType.from(callType),
            status = CallStatus.from(status),
            roomName = "",
            createdAt = TimeFormat.parse(createdAt),
            startedAt = TimeFormat.parse(startedAt),
            endedAt = TimeFormat.parse(endedAt),
            durationSeconds = durationSeconds,
        ),
        hostName = hostName?.takeIf { it.isNotBlank() } ?: "MINI CHAT user",
        isHost = isHost,
        participantCount = participantCount,
    )
}
