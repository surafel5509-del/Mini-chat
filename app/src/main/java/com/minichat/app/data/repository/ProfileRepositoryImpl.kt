package com.minichat.app.data.repository

import com.minichat.app.core.util.AppResult
import com.minichat.app.core.util.safeCall
import com.minichat.app.data.dto.BlockInsertDto
import com.minichat.app.data.dto.ProfileDto
import com.minichat.app.data.dto.ReportInsertDto
import com.minichat.app.data.dto.UserBlockDto
import com.minichat.app.domain.model.BlockedUser
import com.minichat.app.domain.model.Profile
import com.minichat.app.domain.model.StartedConversation
import com.minichat.app.domain.repository.ProfileRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepositoryImpl @Inject constructor(
    private val client: SupabaseClient,
) : ProfileRepository {

    private fun me(): String? = client.auth.currentUserOrNull()?.id

    override suspend fun currentUserId(): String? = me()

    override suspend fun myProfile(): AppResult<Profile> = safeCall {
        val userId = me() ?: error("You must be signed in.")
        client.from(TABLE_PROFILES)
            .select(columns = Columns.list(*PROFILE_COLUMNS)) {
                filter { eq("id", userId) }
            }
            .decodeSingle<ProfileDto>()
            .toDomain()
    }

    override suspend fun profile(userId: String): AppResult<Profile> = safeCall {
        client.from(TABLE_PROFILES)
            .select(columns = Columns.list(*PROFILE_COLUMNS)) {
                filter { eq("id", userId) }
            }
            .decodeSingle<ProfileDto>()
            .toDomain()
    }

    override suspend fun updateProfile(
        displayName: String,
        username: String,
        bio: String?,
    ): AppResult<Profile> = safeCall {
        val userId = me() ?: error("You must be signed in.")
        client.from(TABLE_PROFILES).update(
            {
                set("display_name", displayName.trim())
                set("username", username.trim().lowercase())
                set("bio", bio?.trim()?.takeIf { it.isNotEmpty() })
            },
        ) {
            filter { eq("id", userId) }
            select(Columns.list(*PROFILE_COLUMNS))
        }.decodeSingle<ProfileDto>().toDomain()
    }

    override suspend fun uploadAvatar(bytes: ByteArray, fileExtension: String): AppResult<String> = safeCall {
        val userId = me() ?: error("You must be signed in.")
        val extension = fileExtension.lowercase().removePrefix(".")
            .takeIf { it in setOf("jpg", "jpeg", "png", "webp") } ?: "jpg"
        val path = "$userId/avatar_${System.currentTimeMillis()}.$extension"

        // The bucket caps objects at 5 MB. Check here so the user gets a sentence
        // instead of a 413 with a JSON body.
        if (bytes.size > MAX_AVATAR_BYTES) {
            error("That photo is too large. Pick one under 5 MB.")
        }
        if (bytes.isEmpty()) error("That photo was empty. Try another one.")

        val bucket = client.storage.from(BUCKET_AVATARS)
        bucket.upload(path, bytes) { upsert = true }

        val publicUrl = bucket.publicUrl(path)

        // Keep the profile row in step with storage. If this failed silently the
        // new photo would upload and then never appear, which reads as "upload
        // is broken" to the user.
        client.from(TABLE_PROFILES).update(
            { set("avatar_url", publicUrl) },
        ) {
            filter { eq("id", userId) }
            select(columns = Columns.list("id", "avatar_url"))
        }

        publicUrl
    }

    override suspend fun searchUsers(query: String): AppResult<List<Profile>> = safeCall {
        val userId = me().orEmpty()
        val term = query.trim()
        if (term.length < 2) return@safeCall emptyList()
        val pattern = "%$term%"

        val byDisplayName = client.from(TABLE_PROFILES)
            .select(columns = Columns.list(*PROFILE_COLUMNS)) {
                filter { ilike("display_name", pattern) }
                limit(20)
            }
            .decodeList<ProfileDto>()

        val byUsername = client.from(TABLE_PROFILES)
            .select(columns = Columns.list(*PROFILE_COLUMNS)) {
                filter { ilike("username", pattern) }
                limit(20)
            }
            .decodeList<ProfileDto>()

        val blockedIds = blockedUserIds()

        (byDisplayName + byUsername)
            .distinctBy { it.id }
            .filter { it.id != userId && it.id !in blockedIds }
            .map { it.toDomain() }
            .sortedBy { it.displayName.lowercase() }
            .take(30)
    }

    override suspend fun recentPeers(): AppResult<List<Profile>> = safeCall {
        val userId = me().orEmpty()
        client.from("conversation_members")
            .select(columns = Columns.list("conversation_id", "user_id")) {
                filter { neq("user_id", userId) }
                limit(100)
            }
            .decodeList<Map<String, String>>()
            .mapNotNull { it["user_id"] }
            .distinct()
            .take(40)
            .mapNotNull { peerId ->
                runCatching {
                    client.from(TABLE_PROFILES)
                        .select(columns = Columns.list(*PROFILE_COLUMNS)) {
                            filter { eq("id", peerId) }
                        }
                        .decodeSingleOrNull<ProfileDto>()
                        ?.toDomain()
                }.getOrNull()
            }
            .sortedBy { it.displayName.lowercase() }
    }

    private suspend fun blockedUserIds(): Set<String> = runCatching {
        client.from(TABLE_BLOCKS)
            .select(columns = Columns.list("blocked_id")) {
                filter { eq("blocker_id", me().orEmpty()) }
            }
            .decodeList<UserBlockDto>()
            .map { it.blockedId }
            .toSet()
    }.getOrDefault(emptySet())

    override suspend fun blockUser(userId: String): AppResult<Unit> = safeCall {
        val me = me() ?: error("You must be signed in.")
        client.from(TABLE_BLOCKS).insert(
            BlockInsertDto(blockerId = me, blockedId = userId),
        )
    }

    override suspend fun unblockUser(userId: String): AppResult<Unit> = safeCall {
        val me = me() ?: error("You must be signed in.")
        client.from(TABLE_BLOCKS).delete {
            filter {
                eq("blocker_id", me)
                eq("blocked_id", userId)
            }
        }
    }

    override suspend fun blockedUsers(): AppResult<List<BlockedUser>> = safeCall {
        val me = me() ?: error("You must be signed in.")
        client.from(TABLE_BLOCKS)
            .select(
                columns = Columns.raw(
                    "id,blocker_id,blocked_id,created_at," +
                        "profiles:profiles!user_blocks_blocked_id_fkey(${PROFILE_COLUMNS.joinToString(",")})",
                ),
            ) {
                filter { eq("blocker_id", me) }
                order("created_at", Order.DESCENDING)
            }
            .decodeList<UserBlockDto>()
            .mapNotNull { it.toDomain(fallbackProfile = null) }
    }

    override suspend fun reportUser(userId: String, reason: String): AppResult<Unit> = safeCall {
        val me = me() ?: error("You must be signed in.")
        client.from(TABLE_REPORTS).insert(
            ReportInsertDto(reporterId = me, reportedUserId = userId, reason = reason.trim()),
        )
    }

    override suspend fun startConversation(userId: String): AppResult<StartedConversation> = safeCall {
        if (userId == me()) error("You can't start a conversation with yourself.")

        val raw = client.postgrest.rpc(
            function = "get_or_create_direct_conversation",
            parameters = buildJsonObject { put("p_other_user", userId) },
        ).data
        val conversationId = raw.trim().trim('"')
        if (conversationId.isBlank() || conversationId == "null") {
            error("We couldn't open that conversation. Please try again.")
        }

        val peer = runCatching {
            client.from(TABLE_PROFILES)
                .select(columns = Columns.list(*PROFILE_COLUMNS)) {
                    filter { eq("id", userId) }
                }
                .decodeSingleOrNull<ProfileDto>()
                ?.toDomain()
        }.getOrNull()

        StartedConversation(conversationId = conversationId, peer = peer)
    }

    override suspend fun heartbeat(): AppResult<Unit> = safeCall {
        client.postgrest.rpc(function = "heartbeat", parameters = buildJsonObject { })
    }

    override suspend fun registerDeviceToken(token: String): AppResult<Unit> = safeCall {
        client.postgrest.rpc(
            function = "register_device_token",
            parameters = buildJsonObject {
                put("p_token", token)
                put("p_platform", "android")
            },
        )
    }

    private companion object {
        /** Matches the bucket's own object size limit. */
        const val MAX_AVATAR_BYTES = 5 * 1024 * 1024

        const val TABLE_PROFILES = "profiles"
        const val TABLE_BLOCKS = "user_blocks"
        const val TABLE_REPORTS = "user_reports"
        const val BUCKET_AVATARS = "avatars"

        val PROFILE_COLUMNS = arrayOf(
            "id",
            "username",
            "display_name",
            "avatar_url",
            "bio",
            "last_seen_at",
            "created_at",
        )
    }
}
