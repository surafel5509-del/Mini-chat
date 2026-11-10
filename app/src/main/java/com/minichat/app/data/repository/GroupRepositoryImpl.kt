package com.minichat.app.data.repository

import com.minichat.app.core.util.AppResult
import com.minichat.app.core.util.TimeFormat
import com.minichat.app.core.util.safeCall
import com.minichat.app.data.dto.ConversationMemberDto
import com.minichat.app.data.dto.ProfileDto
import com.minichat.app.domain.model.ConversationMember
import com.minichat.app.domain.model.GroupRole
import com.minichat.app.domain.repository.GroupRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Groups: creation, roster, roles and lifecycle.
 *
 * All authorisation lives in Postgres — `create_group`, `add_group_members`,
 * `remove_group_member`, `set_group_member_role`, `update_group`, `leave_group`
 * and `delete_group` each verify the caller's role before acting, and RLS keeps
 * non-members from reading anything. This class only shapes requests and
 * translates results, so a tampered client gains nothing.
 */
@Singleton
class GroupRepositoryImpl @Inject constructor(
    private val client: SupabaseClient,
) : GroupRepository {

    private fun me(): String? = client.auth.currentUserOrNull()?.id

    override suspend fun createGroup(
        title: String,
        description: String?,
        memberIds: List<String>,
    ): AppResult<String> = safeCall {
        if (me() == null) error("You must be signed in.")
        val clean = title.trim()
        if (clean.isEmpty()) error("Give your group a name.")
        if (clean.length > 80) error("Group names are limited to 80 characters.")

        val raw = client.postgrest.rpc(
            function = "create_group",
            parameters = buildJsonObject {
                put("p_title", JsonPrimitive(clean))
                put("p_description", JsonPrimitive(description?.trim()?.takeIf { it.isNotEmpty() }))
                put("p_member_ids", JsonArray(memberIds.distinct().map { JsonPrimitive(it) }))
            },
        ).data

        raw.trim().trim('"').takeIf { it.isNotBlank() && it != "null" }
            ?: error("We couldn't create the group. Please try again.")
    }

    override suspend fun members(conversationId: String): AppResult<List<ConversationMember>> = safeCall {
        // Two plain queries: membership rows, then the profiles that render them.
        val rows = client.from("conversation_members")
            .select(columns = Columns.list("id", "conversation_id", "user_id", "last_read_at", "role")) {
                filter { eq("conversation_id", conversationId) }
                order("joined_at", Order.ASCENDING)
            }
            .decodeList<MemberRowDto>()

        if (rows.isEmpty()) return@safeCall emptyList()

        val profiles = client.from("profiles")
            .select(columns = Columns.list(*PROFILE_COLUMNS)) {
                filter { isIn("id", rows.map { it.userId }) }
            }
            .decodeList<ProfileDto>()
            .associate { it.id to it.toDomain() }

        rows.mapNotNull { row ->
            val profile = profiles[row.userId] ?: return@mapNotNull null
            ConversationMember(
                userId = row.userId,
                profile = profile,
                role = GroupRole.from(row.role),
                lastReadAt = TimeFormat.parse(row.lastReadAt),
            )
        }.sortedWith(
            compareByDescending<ConversationMember> { it.role.ordinal == GroupRole.Owner.ordinal }
                .thenByDescending { it.role.ordinal == GroupRole.Admin.ordinal }
                .thenBy { it.profile.displayName.lowercase() },
        )
    }

    override suspend fun addMembers(conversationId: String, userIds: List<String>): AppResult<Int> = safeCall {
        val raw = client.postgrest.rpc(
            function = "add_group_members",
            parameters = buildJsonObject {
                put("p_conversation_id", JsonPrimitive(conversationId))
                put("p_user_ids", JsonArray(userIds.distinct().map { JsonPrimitive(it) }))
            },
        ).data
        raw.trim().trim('"').toIntOrNull() ?: 0
    }

    override suspend fun removeMember(conversationId: String, userId: String): AppResult<Unit> = safeCall {
        client.postgrest.rpc(
            function = "remove_group_member",
            parameters = buildJsonObject {
                put("p_conversation_id", JsonPrimitive(conversationId))
                put("p_user_id", JsonPrimitive(userId))
            },
        )
    }

    override suspend fun setRole(conversationId: String, userId: String, role: GroupRole): AppResult<Unit> = safeCall {
        if (role == GroupRole.Owner) error("Ownership is transferred by the owner leaving the group.")
        client.postgrest.rpc(
            function = "set_group_member_role",
            parameters = buildJsonObject {
                put("p_conversation_id", JsonPrimitive(conversationId))
                put("p_user_id", JsonPrimitive(userId))
                put("p_role", JsonPrimitive(if (role == GroupRole.Admin) "admin" else "member"))
            },
        )
    }

    override suspend fun updateGroup(
        conversationId: String,
        title: String?,
        description: String?,
    ): AppResult<Unit> = safeCall {
        client.postgrest.rpc(
            function = "update_group",
            parameters = buildJsonObject {
                put("p_conversation_id", JsonPrimitive(conversationId))
                put("p_title", title?.let { JsonPrimitive(it.trim()) })
                put("p_description", description?.let { JsonPrimitive(it.trim()) })
            },
        )
    }

    override suspend fun leave(conversationId: String): AppResult<Unit> = safeCall {
        client.postgrest.rpc(
            function = "leave_group",
            parameters = buildJsonObject { put("p_conversation_id", JsonPrimitive(conversationId)) },
        )
    }

    override suspend fun deleteGroup(conversationId: String): AppResult<Unit> = safeCall {
        client.postgrest.rpc(
            function = "delete_group",
            parameters = buildJsonObject { put("p_conversation_id", JsonPrimitive(conversationId)) },
        )
    }

    override suspend fun uploadGroupAvatar(
        conversationId: String,
        bytes: ByteArray,
        extension: String,
    ): AppResult<String> = safeCall {
        requireAdmin(conversationId)

        val ext = extension.lowercase().removePrefix(".")
            .takeIf { it in setOf("jpg", "jpeg", "png", "webp") } ?: "jpg"
        val path = "$conversationId/group_${System.currentTimeMillis()}.$ext"

        val bucket = client.storage.from("avatars")
        bucket.upload(path, bytes) { upsert = true }
        val publicUrl = bucket.publicUrl(path)

        client.postgrest.rpc(
            function = "set_group_avatar",
            parameters = buildJsonObject {
                put("p_conversation_id", JsonPrimitive(conversationId))
                put("p_url", JsonPrimitive(publicUrl))
            },
        )
        publicUrl
    }

    /**
     * `conversations` is read-only for clients, so the photo goes through
     * `set_group_avatar`. Being an admin is checked here for a fast, friendly
     * failure and re-checked by the database before anything is written.
     */
    private suspend fun requireAdmin(conversationId: String) {
        val role = client.postgrest.rpc(
            function = "conversation_role",
            parameters = buildJsonObject { put("p_conversation_id", JsonPrimitive(conversationId)) },
        ).data.trim().trim('"')

        if (role != "owner" && role != "admin") {
            error("Only group admins can change the group photo.")
        }
    }

    private companion object {
        val PROFILE_COLUMNS = arrayOf(
            "id", "username", "display_name", "avatar_url", "bio", "last_seen_at", "created_at",
        )
    }
}

/** Membership row with the role attached. */
@kotlinx.serialization.Serializable
internal data class MemberRowDto(
    val id: String = "",
    @kotlinx.serialization.SerialName("conversation_id") val conversationId: String = "",
    @kotlinx.serialization.SerialName("user_id") val userId: String,
    @kotlinx.serialization.SerialName("last_read_at") val lastReadAt: String? = null,
    val role: String? = null,
)
