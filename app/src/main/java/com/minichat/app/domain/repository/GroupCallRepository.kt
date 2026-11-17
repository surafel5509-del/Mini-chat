package com.minichat.app.domain.repository

import com.minichat.app.core.util.AppResult
import com.minichat.app.domain.model.CallCredentials
import com.minichat.app.domain.model.CallType
import com.minichat.app.domain.model.GroupCall
import com.minichat.app.domain.model.GroupCallHistoryItem
import com.minichat.app.domain.model.GroupCallParticipant
import kotlinx.coroutines.flow.Flow

/**
 * Group call signalling. Same split as [CallRepository]: Postgres owns the roster and the room
 * lifecycle, LiveKit moves the media.
 */
interface GroupCallRepository {

    suspend fun start(title: String, type: CallType, inviteeIds: List<String>): AppResult<GroupCall>

    suspend fun join(callId: String): AppResult<GroupCall>

    suspend fun decline(callId: String): AppResult<GroupCall>

    suspend fun leave(callId: String): AppResult<GroupCall>

    suspend fun end(callId: String): AppResult<GroupCall>

    suspend fun call(callId: String): AppResult<GroupCall?>

    suspend fun active(): AppResult<GroupCall?>

    suspend fun participants(callId: String): AppResult<List<GroupCallParticipant>>

    suspend fun history(limit: Int = 50, offset: Int = 0): AppResult<List<GroupCallHistoryItem>>

    /** LiveKit token for a group room, minted by the `livekit-token` Edge Function. */
    suspend fun credentials(callId: String): AppResult<CallCredentials>

    /** Every insert/update on a group call the signed-in user is invited to. */
    fun callUpdates(userId: String): Flow<GroupCall>

    /** Group calls the user has been invited to and that are still live. */
    fun incomingCalls(userId: String): Flow<GroupCall>
}
