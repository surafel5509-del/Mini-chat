package com.minichat.app.data.repository

import android.util.Log
import com.minichat.app.core.di.ApplicationScope
import com.minichat.app.core.util.AppResult
import com.minichat.app.core.util.safeCall
import com.minichat.app.data.dto.CallDto
import com.minichat.app.data.dto.CallHistoryDto
import com.minichat.app.data.dto.EdgeFunctionErrorDto
import com.minichat.app.data.dto.LiveKitHealthDto
import com.minichat.app.data.dto.LiveKitTokenDto
import com.minichat.app.domain.model.Call
import com.minichat.app.domain.model.CallCredentials
import com.minichat.app.domain.model.CallHistoryItem
import com.minichat.app.domain.model.CallStatus
import com.minichat.app.domain.model.CallType
import com.minichat.app.domain.repository.CallRepository
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Postgres is the call registry: every transition (ringing → active → ended) is a row update that
 * both phones observe over Realtime. LiveKit is only used for media, and its token is minted by an
 * Edge Function that re-validates the caller's right to join the room.
 */
@Singleton
class CallRepositoryImpl @Inject constructor(
    private val client: SupabaseClient,
    private val json: Json,
    @ApplicationScope private val appScope: CoroutineScope,
) : CallRepository {

    private val _updates = MutableSharedFlow<Call>(extraBufferCapacity = 64)
    private val _incoming = MutableSharedFlow<Call>(extraBufferCapacity = 16)

    @Volatile
    private var callChannel: RealtimeChannel? = null

    @Volatile
    private var streamUserId: String? = null

    // ---------------------------------------------------------------------
    // Signalling mutations
    // ---------------------------------------------------------------------

    override suspend fun startCall(conversationId: String, type: CallType): AppResult<Call> = safeCall {
        val raw = client.postgrest.rpc(
            function = "start_call",
            parameters = buildJsonObject {
                put("p_conversation_id", conversationId)
                put("p_call_type", type.wire)
            },
        ).data
        json.decodeFromString<CallDto>(raw).toDomain()
    }

    override suspend fun answerCall(callId: String): AppResult<Call> = safeCall {
        val raw = client.postgrest.rpc(
            function = "answer_call",
            parameters = buildJsonObject { put("p_call_id", callId) },
        ).data
        json.decodeFromString<CallDto>(raw).toDomain()
    }

    override suspend fun declineCall(callId: String, reason: String): AppResult<Call> = safeCall {
        val raw = client.postgrest.rpc(
            function = "decline_call",
            parameters = buildJsonObject {
                put("p_call_id", callId)
                put("p_reason", reason)
            },
        ).data
        json.decodeFromString<CallDto>(raw).toDomain()
    }

    override suspend fun endCall(callId: String): AppResult<Call> = safeCall {
        val raw = client.postgrest.rpc(
            function = "end_call",
            parameters = buildJsonObject { put("p_call_id", callId) },
        ).data
        json.decodeFromString<CallDto>(raw).toDomain()
    }

    override suspend fun call(callId: String): AppResult<Call?> = safeCall {
        client.postgrest.from("calls")
            .select(columns = Columns.list("*")) {
                filter { eq("id", callId) }
            }
            .decodeSingleOrNull<CallDto>()
            ?.toDomain()
    }

    override suspend fun activeCall(): AppResult<Call?> = safeCall {
        val raw = client.postgrest.rpc("active_call").data.trim()
        if (raw.isEmpty() || raw == "null") {
            null
        } else {
            json.decodeFromString<CallDto>(raw).toDomain()
        }
    }

    override suspend fun history(limit: Int, offset: Int): AppResult<List<CallHistoryItem>> = safeCall {
        val raw = client.postgrest.rpc(
            function = "call_history",
            parameters = buildJsonObject {
                put("p_limit", limit)
                put("p_offset", offset)
            },
        ).data
        json.decodeFromString<List<CallHistoryDto>>(raw).map { it.toDomain() }
    }

    override suspend fun expireStaleCalls(): AppResult<Unit> = safeCall {
        client.postgrest.rpc("expire_stale_calls")
        Unit
    }

    // ---------------------------------------------------------------------
    // LiveKit credentials (Edge Function)
    // ---------------------------------------------------------------------

    override suspend fun credentials(callId: String): AppResult<CallCredentials> = safeCall {
        val response = client.functions.invoke(
            function = LIVEKIT_FUNCTION,
            body = buildJsonObject { put("call_id", callId) },
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

                    else -> message ?: "We couldn't start the call. Please try again."
                },
            )
        }
        val dto = json.decodeFromString<LiveKitTokenDto>(payload)
        if (dto.token.isBlank() || dto.url.isBlank()) {
            error("We couldn't start the call. Please try again.")
        }
        CallCredentials(url = dto.url, token = dto.token, roomName = dto.room)
    }

    override suspend fun callingConfigured(): AppResult<Boolean> = safeCall {
        val response = client.functions.invoke(
            function = LIVEKIT_FUNCTION,
            body = buildJsonObject { put("action", "health") },
        )
        if (response.status.value !in 200..299) {
            false
        } else {
            json.decodeFromString<LiveKitHealthDto>(response.bodyAsText()).configured
        }
    }

    // ---------------------------------------------------------------------
    // Realtime
    // ---------------------------------------------------------------------

    override fun callUpdates(userId: String): Flow<Call> = flow {
        ensureChannel(userId)
        emitAll(_updates.asSharedFlow())
    }

    override fun incomingCalls(userId: String): Flow<Call> = flow {
        ensureChannel(userId)
        emitAll(_incoming.asSharedFlow())
    }

    private suspend fun ensureChannel(userId: String) {
        if (callChannel != null && streamUserId == userId) return

        synchronized(this) {
            if (callChannel != null && streamUserId == userId) return
            streamUserId = userId
        }

        val channel = client.channel("minichat:calls:$userId")
        callChannel = channel

        channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") { table = "calls" }
            .onEach { action -> pump(action.record, userId) }
            .launchIn(appScope)

        channel.postgresChangeFlow<PostgresAction.Update>(schema = "public") { table = "calls" }
            .onEach { action -> pump(action.record, userId) }
            .launchIn(appScope)

        runCatching { channel.subscribe(blockUntilSubscribed = false) }
            .onFailure { Log.w(TAG, "Could not subscribe to the call stream", it) }
    }

    private fun pump(record: JsonObject, userId: String) {
        val call = decode(record) ?: return
        if (!call.involves(userId)) return
        _updates.tryEmit(call)
        if (call.status == CallStatus.Ringing && call.calleeId == userId) {
            _incoming.tryEmit(call)
        }
    }

    private fun decode(record: JsonObject): Call? = runCatching {
        json.decodeFromJsonElement(CallDto.serializer(), record).toDomain()
    }.getOrNull()

    /** Drops the realtime channel (called when the user signs out). */
    suspend fun stopStream() {
        callChannel?.let { channel -> runCatching { client.realtime.removeChannel(channel) } }
        callChannel = null
        streamUserId = null
    }

    private companion object {
        const val TAG = "CallRepository"
        const val LIVEKIT_FUNCTION = "livekit-token"
    }
}
