package com.minichat.app.domain.repository

import com.minichat.app.core.util.AppResult
import com.minichat.app.domain.model.BlockedUser
import com.minichat.app.domain.model.ConversationSummary
import com.minichat.app.domain.model.Message
import com.minichat.app.domain.model.Profile
import com.minichat.app.domain.model.StartedConversation
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** Session state as the UI needs it — never the raw SDK types. */
sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val userId: String, val email: String?, val emailVerified: Boolean) : AuthState
}

sealed interface SignUpOutcome {
    /** A session was created immediately; move straight to the chat list. */
    data object SignedIn : SignUpOutcome

    /** The project requires email confirmation before the first sign in. */
    data object VerificationRequired : SignUpOutcome
}

interface AuthRepository {
    val authState: Flow<AuthState>
    fun currentUserId(): String?
    fun currentEmail(): String?
    suspend fun awaitReady()

    suspend fun signIn(email: String, password: String): AppResult<Unit>
    suspend fun signUp(email: String, password: String, displayName: String): AppResult<SignUpOutcome>
    suspend fun signOut(): AppResult<Unit>
    suspend fun sendPasswordReset(email: String): AppResult<Unit>
    suspend fun resendVerificationEmail(email: String): AppResult<Unit>
    suspend fun updatePassword(newPassword: String): AppResult<Unit>
    suspend fun refreshSession(): AppResult<Unit>
}

/** Realtime signals for the conversation list. */
sealed interface ConversationSignal {
    data object MessagesChanged : ConversationSignal
    data object MembershipChanged : ConversationSignal
    data class ReadStateChanged(val conversationId: String, val userId: String, val readAt: Instant?) :
        ConversationSignal
}

/** Realtime signals for an open conversation. */
sealed interface MessageEvent {
    data class Inserted(val message: Message) : MessageEvent
    data class Updated(val message: Message) : MessageEvent
    data class Deleted(val messageId: String) : MessageEvent
    data class ReactionAdded(val reaction: Reaction) : MessageEvent
    data class ReactionRemoved(val reaction: Reaction) : MessageEvent
}

interface ChatRepository {
    fun conversationSignals(): Flow<ConversationSignal>

    suspend fun loadConversations(): AppResult<List<ConversationSummary>>
    suspend fun loadConversation(conversationId: String): AppResult<ConversationSummary?>

    fun messageEvents(conversationId: String): Flow<MessageEvent>

    suspend fun loadMessages(
        conversationId: String,
        limit: Int = 40,
        before: Instant? = null,
    ): AppResult<List<Message>>

    suspend fun sendMessage(
        conversationId: String,
        content: String,
        replyToId: String? = null,
    ): AppResult<Message>

    suspend fun editMessage(messageId: String, content: String): AppResult<Unit>
    suspend fun deleteMessage(messageId: String): AppResult<Unit>
    suspend fun addReaction(messageId: String, emoji: String): AppResult<Unit>
    suspend fun removeReaction(messageId: String, emoji: String): AppResult<Unit>

    suspend fun markConversationRead(conversationId: String): AppResult<Unit>
    suspend fun setPinned(conversationId: String, pinned: Boolean): AppResult<Unit>
    suspend fun setMuted(conversationId: String, muted: Boolean): AppResult<Unit>
    suspend fun peerReadAt(conversationId: String): AppResult<Instant?>
}

interface ProfileRepository {
    suspend fun currentUserId(): String?
    suspend fun myProfile(): AppResult<Profile>
    suspend fun profile(userId: String): AppResult<Profile>

    suspend fun updateProfile(
        displayName: String,
        username: String,
        bio: String?,
    ): AppResult<Profile>

    suspend fun uploadAvatar(bytes: ByteArray, fileExtension: String): AppResult<String>

    suspend fun searchUsers(query: String): AppResult<List<Profile>>
    suspend fun recentPeers(): AppResult<List<Profile>>

    suspend fun blockUser(userId: String): AppResult<Unit>
    suspend fun unblockUser(userId: String): AppResult<Unit>
    suspend fun blockedUsers(): AppResult<List<BlockedUser>>
    suspend fun reportUser(userId: String, reason: String): AppResult<Unit>

    suspend fun startConversation(userId: String): AppResult<StartedConversation>

    suspend fun heartbeat(): AppResult<Unit>
    suspend fun registerDeviceToken(token: String): AppResult<Unit>
}

/** Ephemeral, best-effort signals: typing indicators and connection health. */
interface RealtimeRepository {
    /** User ids currently typing in a conversation. */
    fun typingUserIds(conversationId: String): Flow<Set<String>>
    suspend fun setTyping(conversationId: String, typing: Boolean)
    suspend fun releaseTyping(conversationId: String)
}
