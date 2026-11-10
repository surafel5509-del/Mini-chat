package com.minichat.app.data.realtime

import android.util.Log
import com.minichat.app.core.di.ApplicationScope
import com.minichat.app.data.dto.MessageDto
import com.minichat.app.data.dto.ReactionDto
import com.minichat.app.domain.repository.ConversationSignal
import com.minichat.app.domain.repository.MessageEvent
import com.minichat.app.domain.repository.RealtimeRepository
import com.minichat.app.core.util.TimeFormat
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.broadcast.BroadcastPayload
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton
import com.minichat.app.core.util.onFailure
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Owns every Realtime subscription in the app.
 *
 *  * `postgres_changes` (RLS enforced by Postgres) drives messages, reactions, read receipts and
 *    conversation list refreshes — the client can never see rows the user is not a member of.
 *  * `broadcast` on a per-conversation topic carries typing indicators, which are ephemeral and
 *    therefore not worth a database round trip.
 *
 * Channels are created lazily, reused while a screen is open and torn down deterministically so
 * nothing leaks between conversations.
 */
@Singleton
class RealtimeService @Inject constructor(
    private val client: SupabaseClient,
    private val json: Json,
    @ApplicationScope private val appScope: CoroutineScope,
) : RealtimeRepository {


    private val _messageEvents = MutableSharedFlow<MessageEvent>(extraBufferCapacity = 128)
    val messageEvents: SharedFlow<MessageEvent> = _messageEvents.asSharedFlow()

    /**
     * Every message event, across every conversation.
     *
     * The chat screen filters this down to its own conversation; the notification
     * service needs the unfiltered stream because it has to hear about messages
     * the user is not currently looking at.
     */
    override fun allMessageEvents(): Flow<MessageEvent> = messageEvents

    private val _conversationSignals = MutableSharedFlow<ConversationSignal>(extraBufferCapacity = 128)
    val conversationSignals: SharedFlow<ConversationSignal> = _conversationSignals.asSharedFlow()

    /**
     * Fires whenever a note is created, replaced or deleted by somebody whose
     * notes this user is allowed to see. The payload is intentionally empty:
     * notes are tiny, so screens simply re-read the (RLS filtered, unexpired)
     * list instead of trying to merge rows locally.
     */
    private val _noteSignals = MutableSharedFlow<Unit>(extraBufferCapacity = 32)
    override fun noteSignals(): SharedFlow<Unit> = _noteSignals.asSharedFlow()

    @Volatile
    private var chatChannel: RealtimeChannel? = null

    private val typingChannels = mutableMapOf<String, RealtimeChannel>()
    private val typingStates = mutableMapOf<String, MutableStateFlow<Map<String, Long>>>()

    private fun currentUserId(): String? = client.auth.currentUserOrNull()?.id

    // ---------------------------------------------------------------------
    // Postgres change streams
    // ---------------------------------------------------------------------

    suspend fun startChatStream() {
        if (chatChannel != null) return
        val channel = client.channel(CHAT_TOPIC)
        chatChannel = channel

        channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") { table = "messages" }
            .onEach { action -> decodeMessage(action.record)?.let { _messageEvents.emit(MessageEvent.Inserted(it)) } }
            .launchIn(appScope)

        channel.postgresChangeFlow<PostgresAction.Update>(schema = "public") { table = "messages" }
            .onEach { action ->
                decodeMessage(action.record)?.let { _messageEvents.emit(MessageEvent.Updated(it)) }
                _conversationSignals.emit(ConversationSignal.MessagesChanged)
            }
            .launchIn(appScope)

        channel.postgresChangeFlow<PostgresAction.Delete>(schema = "public") { table = "messages" }
            .onEach { action ->
                action.oldRecord["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let {
                    _messageEvents.emit(MessageEvent.Deleted(it))
                    _conversationSignals.emit(ConversationSignal.MessagesChanged)
                }
            }
            .launchIn(appScope)

        channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") { table = "message_reactions" }
            .onEach { action -> decodeReaction(action.record)?.let { _messageEvents.emit(MessageEvent.ReactionAdded(it)) } }
            .launchIn(appScope)

        channel.postgresChangeFlow<PostgresAction.Delete>(schema = "public") { table = "message_reactions" }
            .onEach { action -> decodeReaction(action.oldRecord)?.let { _messageEvents.emit(MessageEvent.ReactionRemoved(it)) } }
            .launchIn(appScope)

        channel.postgresChangeFlow<PostgresAction.Update>(schema = "public") { table = "conversation_members" }
            .onEach { action ->
                val conversationId = action.record["conversation_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                val userId = action.record["user_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                val readAt = action.record["last_read_at"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it != "null" }
                if (conversationId != null && userId != null) {
                    _conversationSignals.emit(
                        ConversationSignal.ReadStateChanged(
                            conversationId = conversationId,
                            userId = userId,
                            readAt = TimeFormat.parse(readAt),
                        ),
                    )
                }
                _conversationSignals.emit(ConversationSignal.MembershipChanged)
            }
            .launchIn(appScope)

        channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") { table = "conversation_members" }
            .onEach { _conversationSignals.emit(ConversationSignal.MembershipChanged) }
            .launchIn(appScope)

        channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") { table = "notes" }
            .onEach { _noteSignals.emit(Unit) }
            .launchIn(appScope)

        channel.postgresChangeFlow<PostgresAction.Update>(schema = "public") { table = "notes" }
            .onEach { _noteSignals.emit(Unit) }
            .launchIn(appScope)

        channel.postgresChangeFlow<PostgresAction.Delete>(schema = "public") { table = "notes" }
            .onEach { _noteSignals.emit(Unit) }
            .launchIn(appScope)

        runCatching { channel.subscribe(blockUntilSubscribed = false) }
            .onFailure { Log.w(TAG, "Could not subscribe to chat stream", it) }
    }

    suspend fun stopChatStream() {
        chatChannel?.let { channel ->
            runCatching { client.realtime.removeChannel(channel) }
        }
        chatChannel = null
    }

    private fun decodeMessage(record: JsonObject): com.minichat.app.domain.model.Message? = runCatching {
        val dto = json.decodeFromJsonElement(MessageDto.serializer(), record)
        val me = currentUserId().orEmpty()
        dto.toDomain(me)
    }.getOrNull()

    private fun decodeReaction(record: JsonObject) = runCatching {
        json.decodeFromJsonElement(ReactionDto.serializer(), record).toDomain()
    }.getOrNull()

    // ---------------------------------------------------------------------
    // Typing indicators (broadcast, per conversation)
    // ---------------------------------------------------------------------

    suspend fun openConversation(conversationId: String) {
        if (typingChannels.containsKey(conversationId)) return
        val state = typingStates.getOrPut(conversationId) { MutableStateFlow(emptyMap()) }
        val channel = client.channel("$TYPING_TOPIC_PREFIX$conversationId") {
            broadcast { receiveOwnBroadcasts = false }
        }
        typingChannels[conversationId] = channel

        channel.broadcastFlow<TypingPayload>(TYPING_EVENT)
            .onEach { payload ->
                val me = currentUserId()
                if (payload.userId != me) {
                    state.update { current ->
                        if (payload.isTyping) {
                            current + (payload.userId to System.currentTimeMillis())
                        } else {
                            current - payload.userId
                        }
                    }
                }
            }
            .launchIn(appScope)

        runCatching { channel.subscribe(blockUntilSubscribed = true) }
            .onFailure { Log.w(TAG, "Typing channel subscribe failed", it) }
    }

    suspend fun closeConversation(conversationId: String) {
        typingChannels.remove(conversationId)?.let { channel ->
            runCatching { client.realtime.removeChannel(channel) }
        }
        typingStates.remove(conversationId)
    }

    override suspend fun watchConversation(conversationId: String) = openConversation(conversationId)

    override suspend fun unwatchConversation(conversationId: String) = closeConversation(conversationId)

    override fun typingUserIds(conversationId: String): Flow<Set<String>> = channelFlow {
        val state = typingStates.getOrPut(conversationId) { MutableStateFlow(emptyMap()) }
        while (true) {
            val now = System.currentTimeMillis()
            val active = state.value.filterValues { now - it < TYPING_TTL_MS }.keys
            send(active)
            delay(900)
        }
    }

    override suspend fun setTyping(conversationId: String, typing: Boolean) {
        val channel = typingChannels[conversationId] ?: return
        val me = currentUserId() ?: return
        runCatching {
            channel.broadcast(
                TYPING_EVENT,
                BroadcastPayload.Json(
                    buildJsonObject {
                        put("user_id", me)
                        put("is_typing", typing)
                    },
                ),
            )
        }
    }

    override suspend fun releaseTyping(conversationId: String) {
        setTyping(conversationId, false)
    }

    /** Keeps the websocket warm while the app is in the foreground. */
    fun ensureRealtimeConnected() {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { client.realtime.connect() }
        }
    }

    private fun MutableStateFlow<Map<String, Long>>.update(block: (Map<String, Long>) -> Map<String, Long>) {
        value = block(value)
    }

    @Serializable
    data class TypingPayload(
        @SerialName("user_id") val userId: String,
        @SerialName("is_typing") val isTyping: Boolean,
    )

    private companion object {
        const val TAG = "RealtimeService"
        const val CHAT_TOPIC = "minichat:chat"
        const val TYPING_TOPIC_PREFIX = "minichat:typing:"
        const val TYPING_EVENT = "typing"
        const val TYPING_TTL_MS = 6_000L
    }
}
