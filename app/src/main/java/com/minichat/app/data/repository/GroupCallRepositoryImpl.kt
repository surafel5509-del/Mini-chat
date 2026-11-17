package com.minichat.app.data.repository

import android.util.Log
import com.minichat.app.core.di.ApplicationScope
import com.minichat.app.core.util.AppResult
import com.minichat.app.core.util.safeCall
import com.minichat.app.data.dto.EdgeFunctionErrorDto
import com.minichat.app.data.dto.GroupCallDto
import com.minichat.app.data.dto.GroupCallHistoryDto
import com.minichat.app.data.dto.GroupCallParticipantDto
import com.minichat.app.data.dto.LiveKitTokenDto
import com.minichat.app.domain.model.CallCredentials
import com.minichat.app.domain.model.CallStatus
import com.minichat.app.domain.model.CallType
import com.minichat.app.domain.model.GroupCall
import com.minichat.app.domain.model.GroupCallHistoryItem
import com.minichat.app.domain.model.GroupCallParticipant
import com.minichat.app.domain.repository.GroupCallRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GroupCallRepositoryImpl @Inject constructor(
    private val client: SupabaseClient,
    private val json: Json,
    @ApplicationScope private val appScope: CoroutineScope,
) : GroupCallRepository {

    private val _updates = MutableSharedFlow<GroupCall>(extraBufferCapacity = 64)
    private val _incoming = MutableSharedFlow<GroupCall>(extraBufferCapacity = 16)

    @Volatile
    private var channel: RealtimeChannel? = null

    @Volatile
    private var streamUserId: String? = null

    // ---------------------------------------------------------------------
    // Mutations
    // ---------------------------------------------------------------------

    override suspend fun start(
        title: String,
        type: CallType,
        inviteeIds: List<String>,
    ): AppResult<GroupCall> = safeCall {
        val raw = client.postgrest.rpc(
            function = "start_group_call",
            parameters = buildJsonObject {
                put("p_title", title)
                put("p_call_type", type.wire)
                put(
                    "p_invitee_ids",
                    buildJsonArray { inviteeIds.forEach { add(it) } },
                )
            },
        ).data
        json.decodeFromString<GroupCallDto>(raw).toDomain()
    }

    override suspend fun join(callId: String): AppResult<GroupCall> = rpcCall("join_group_call", callId)

    override suspend fun decline(callId: String): AppResult<GroupCall> = rpcCall("decline_group_call", callId)

    override suspend fun leave(callId: String): AppResult<GroupCall> = rpcCall("leave_group_call", callId)

    override suspend fun end(callId: String): AppResult<GroupCall> = rpcCall("end_group_call", callId)

    private suspend fun rpcCall(function: String, callId: String): AppResult<GroupCall> = safeCall {
        val raw = client.postgrest.rpc(
            function = function,
            parameters = buildJsonObject { put("p_call_id", callId) },
        ).data
        json.decodeFromString<GroupCallDto>(raw).toDomain()
    }

    override suspend fun call(callId: String): AppResult<GroupCall?> = safeCall {
        client.postgrest.from("group_calls")
            .select(columns = Columns.list("*")) {
                filter { eq("id", callId) }
            }
            .decodeSingleOrNull<GroupCallDto>()
            ?.toDomain()
    }

    override suspend fun active(): AppResult<GroupCall?> = safeCall {
        val raw = client.postgrest.rpc("active_group_call").data.trim()
        if (raw.isEmpty() || raw == "null") null else json.decodeFromString<GroupCallDto>(raw).toDomain()
    }

    override suspend fun participants(callId: String): AppResult<List<GroupCallParticipant>> = safeCall {
        val raw = client.postgrest.rpc(
            function = "group_call_participants",
            parameters = buildJsonObject { put("p_call_id", callId) },
        ).data
        json.decodeFromString<List<GroupCallParticipantDto>>(raw).map { it.toDomain() }
    }

    override suspend fun history(limit: Int, offset: Int): AppResult<List<GroupCallHistoryItem>> = safeCall {
        val raw = client.postgrest.rpc(
            function = "group_call_history",
            parameters = buildJsonObject {
                put("p_limit", limit)
                put("p_offset", offset)
            },
        ).data
        json.decodeFromString<List<GroupCallHistoryDto>>(raw).map { it.toDomain() }
    }

    override suspend fun credentials(callId: String): AppResult<CallCredentials> = safeCall {
        val response = client.functions.invoke(
            function = LIVEKIT_FUNCTION,
            body = buildJsonObject { put("group_call_id", callId) },
        )
        val payload = response.bodyAsText()
        if (response.status.value !in 200..299) {
            val message = runCatching {
                json.decodeFromString<EdgeFunctionErrorDto>(payload).message
            }.getOrNull()
            error(
                when {
                    payload.contains("calling_not_configured") ->
                        "Calling isn't configured yet. Add the LiveKit secrets in Supabase to enable it."

                    payload.contains("call_finished") -> "This call has already ended."
                    payload.contains("forbidden") -> "You are not part of this call."
                    else -> message ?: "We couldn't join the call. Please try again."
                },
            )
        }
        val dto = json.decodeFromString<LiveKitTokenDto>(payload)
        if (dto.token.isBlank() || dto.url.isBlank()) {
            error("We couldn't join the call. Please try again.")
        }
        CallCredentials(url = dto.url, token = dto.token, roomName = dto.room)
    }

    // ---------------------------------------------------------------------
    // Realtime
    // ---------------------------------------------------------------------

    override fun callUpdates(userId: String): Flow<GroupCall> = flow {
        ensureChannel(userId)
        emitAll(_updates.asSharedFlow())
    }

    override fun incomingCalls(userId: String): Flow<GroupCall> = flow {
        ensureChannel(userId)
        emitAll(_incoming.asSharedFlow())
    }

    private suspend fun ensureChannel(userId: String) {
        if (channel != null && streamUserId == userId) return

        synchronized(this) {
            if (channel != null && streamUserId == userId) return
            streamUserId = userId
        }

        val active = client.channel("minichat:group-calls:$userId")
        channel = active

        active.postgresChangeFlow<PostgresAction.Insert>(schema = "public") { table = "group_calls" }
            .onEach { action -> pump(action.record, userId) }
            .launchIn(appScope)

        active.postgresChangeFlow<PostgresAction.Update>(schema = "public") { table = "group_calls" }
            .onEach { action -> pump(action.record, userId) }
            .launchIn(appScope)

        runCatching { active.subscribe(blockUntilSubscribed = false) }
            .onFailure { Log.w(TAG, "Could not subscribe to the group call stream", it) }
    }

    private fun pump(record: JsonObject, userId: String) {
        val call = runCatching {
            json.decodeFromJsonElement(GroupCallDto.serializer(), record).toDomain()
        }.getOrNull() ?: return

        _updates.tryEmit(call)
        if (call.status == CallStatus.Active && call.hostId != userId) {
            _incoming.tryEmit(call)
        }
    }

    private companion object {
        const val TAG = "GroupCallRepository"
        const val LIVEKIT_FUNCTION = "livekit-token"
    }
}
