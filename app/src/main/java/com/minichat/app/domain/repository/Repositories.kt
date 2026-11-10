package com.minichat.app.domain.repository

import com.minichat.app.core.util.AppResult
import com.minichat.app.domain.model.BlockedUser
import com.minichat.app.domain.model.ConversationSummary
import com.minichat.app.domain.model.Message
import com.minichat.app.domain.model.ConversationMember
import com.minichat.app.domain.model.FriendNote
import com.minichat.app.domain.model.GroupRole
import com.minichat.app.domain.model.Note
import com.minichat.app.domain.model.Profile
import com.minichat.app.domain.model.StartedConversation
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import com.minichat.app.domain.model.Reaction

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

    // ---- 6-digit email verification ---------------------------------------
    /**
     * Emails a fresh 6-digit code. Works for both purposes: confirming a new
     * account and starting a password reset.
     */
    suspend fun requestOtp(email: String): AppResult<OtpSendInfo>

    /** Checks a code. `SIGNUP` confirms an account, `RECOVERY` unlocks a reset. */
    suspend fun verifyOtp(email: String, code: String, purpose: OtpPurpose): AppResult<Unit>

    /** True when Supabase already has a session for this address (code confirmed). */
    fun hasSession(): Boolean

    /**
     * Completes a Supabase recovery deep link (`minichat://auth-callback#access_token=…`).
     * The recovery link carries a short lived session which is imported so the user can set a
     * new password without signing in again.
     */
    suspend fun completeRecoveryLink(accessToken: String, refreshToken: String): AppResult<Unit>
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

    // ---- Voice notes -------------------------------------------------------
    /** Uploads a recorded clip and posts it as a voice message. */
    suspend fun sendVoiceMessage(
        conversationId: String,
        audio: ByteArray,
        durationMs: Int,
        replyToId: String? = null,
    ): AppResult<Message>

    /** A short lived signed URL for a private chat-media object. */
    suspend fun signedMediaUrl(path: String, expiresInSeconds: Int = 3600): AppResult<String>
}

/** Group creation and administration. Every call is authorised in Postgres. */
interface GroupRepository {
    suspend fun createGroup(
        title: String,
        description: String?,
        memberIds: List<String>,
    ): AppResult<String>

    suspend fun members(conversationId: String): AppResult<List<ConversationMember>>

    /** Group metadata straight from `conversation_overview` (title, photo, role). */
    suspend fun groupOverview(conversationId: String): AppResult<GroupInfo>

    suspend fun addMembers(conversationId: String, userIds: List<String>): AppResult<Int>
    suspend fun removeMember(conversationId: String, userId: String): AppResult<Unit>
    suspend fun setRole(conversationId: String, userId: String, role: GroupRole): AppResult<Unit>

    suspend fun updateGroup(conversationId: String, title: String?, description: String?): AppResult<Unit>

    suspend fun leave(conversationId: String): AppResult<Unit>
    suspend fun deleteGroup(conversationId: String): AppResult<Unit>

    /** Uploads a group photo and stores it on the conversation row. */
    suspend fun uploadGroupAvatar(conversationId: String, bytes: ByteArray, extension: String): AppResult<String>
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
    /** Subscribes to the ephemeral typing topic of a conversation (idempotent). */
    suspend fun watchConversation(conversationId: String)

    /** Releases the typing subscription of a conversation. */
    suspend fun unwatchConversation(conversationId: String)

    /** User ids currently typing in a conversation. */
    fun typingUserIds(conversationId: String): Flow<Set<String>>
    suspend fun setTyping(conversationId: String, typing: Boolean)
    suspend fun releaseTyping(conversationId: String)

    /** Emits whenever a note this user may see is created, replaced or removed. */
    fun noteSignals(): Flow<Unit>
}

/**
 * Ephemeral status notes — the Instagram-style "share note" surface.
 *
 * A note is visible to the author and to people they already have a conversation
 * with. Every read is filtered by expiry, and the write path is validated and
 * rate limited in Postgres.
 */
interface NoteRepository {
    /** My current note, if one is still live. */
    suspend fun myNote(): AppResult<Note?>

    /** Live notes from friends (people I share a conversation with), newest first. */
    suspend fun friendNotes(limit: Int = 24): AppResult<List<FriendNote>>

    /** One person's live note — used on their profile. */
    suspend fun noteFor(userId: String): AppResult<Note?>

    /** Create or replace my note. [minutes] is clamped to 15…1440. */
    suspend fun setNote(body: String, minutes: Int = 1440): AppResult<Note>

    /** Remove my note immediately. */
    suspend fun deleteNote(): AppResult<Unit>
}

/** Headline facts about a group, as the info screen needs them. */
data class GroupInfo(
    val conversationId: String,
    val title: String?,
    val description: String?,
    val avatarUrl: String?,
    val myRole: GroupRole,
    val memberCount: Int,
)

/** Why a code was sent. Both paths use the same six digit mechanism. */
enum class OtpPurpose { Signup, Recovery }

/** What the user should be told after a code is emailed. */
data class OtpSendInfo(
    val codeLength: Int = 6,
    val expiresInMinutes: Int = 10,
    val message: String = "",
)
