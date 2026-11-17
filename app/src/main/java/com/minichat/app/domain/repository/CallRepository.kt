package com.minichat.app.domain.repository

import com.minichat.app.core.util.AppResult
import com.minichat.app.domain.model.Call
import com.minichat.app.domain.model.CallCredentials
import com.minichat.app.domain.model.CallHistoryItem
import com.minichat.app.domain.model.CallType
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Call signalling. Media never travels through here — Postgres holds the *state* of a call
 * (ringing → active → ended) while LiveKit carries the audio/video. Keeping those two concerns
 * apart is what makes the feature resilient: a dropped websocket never loses a call record.
 */
interface CallRepository {

    suspend fun startCall(conversationId: String, type: CallType): AppResult<Call>

    suspend fun answerCall(callId: String): AppResult<Call>

    suspend fun declineCall(callId: String, reason: String = "declined"): AppResult<Call>

    suspend fun endCall(callId: String): AppResult<Call>

    suspend fun call(callId: String): AppResult<Call?>

    suspend fun activeCall(): AppResult<Call?>

    suspend fun history(limit: Int = 60, offset: Int = 0): AppResult<List<CallHistoryItem>>

    /** Marks ringing calls older than the ring window as missed. Safe to call often. */
    suspend fun expireStaleCalls(): AppResult<Unit>

    /** Short lived LiveKit token + url, minted by the `livekit-token` Edge Function. */
    suspend fun credentials(callId: String): AppResult<CallCredentials>

    /** True when the backend has LiveKit credentials configured. */
    suspend fun callingConfigured(): AppResult<Boolean>

    /** Every insert/update on a call the signed-in user is part of. */
    fun callUpdates(userId: String): Flow<Call>

    /** Calls currently ringing *for me*. */
    fun incomingCalls(userId: String): Flow<Call>
}

/** Timestamp of the peer's last read receipt usage is shared with chat; kept here for clarity. */
typealias CallTimestamp = Instant
