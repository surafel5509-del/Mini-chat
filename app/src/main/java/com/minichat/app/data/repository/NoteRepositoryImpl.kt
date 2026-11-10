package com.minichat.app.data.repository

import com.minichat.app.core.util.AppResult
import com.minichat.app.core.util.TimeFormat
import com.minichat.app.core.util.safeCall
import com.minichat.app.data.dto.NoteDto
import com.minichat.app.data.dto.ProfileDto
import com.minichat.app.data.dto.SetNoteParams
import com.minichat.app.domain.model.FriendNote
import com.minichat.app.domain.model.Note
import com.minichat.app.domain.model.Profile
import com.minichat.app.domain.repository.NoteRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notes: the Instagram-style "share a note" surface.
 *
 *  * one active note per author, replaced in place (`set_my_note` RPC);
 *  * visible to the author and to anybody they share a conversation with —
 *    enforced by row level security, not by this class;
 *  * always filtered by `expires_at > now()` so an expired note never renders.
 */
@Singleton
class NoteRepositoryImpl @Inject constructor(
    private val client: SupabaseClient,
    private val json: Json,
) : NoteRepository {

    private fun me(): String? = client.auth.currentUserOrNull()?.id

    private fun liveFilter() = TimeFormat.toIso(Instant.now())

    override suspend fun myNote(): AppResult<Note?> = safeCall {
        val userId = me() ?: return@safeCall null
        readOne(userId)
    }

    override suspend fun noteFor(userId: String): AppResult<Note?> = safeCall {
        readOne(userId)
    }

    override suspend fun friendNotes(limit: Int): AppResult<List<FriendNote>> = safeCall {
        val userId = me().orEmpty()
        val rows = client.from(TABLE_NOTES)
            .select(columns = Columns.list(*NOTE_COLUMNS)) {
                filter {
                    gt("expires_at", liveFilter())
                    neq("author_id", userId)
                }
                order("created_at", Order.DESCENDING)
                limit(limit.toLong())
            }
            .decodeList<NoteDto>()

        if (rows.isEmpty()) return@safeCall emptyList()

        // Author profiles are fetched separately, never through a PostgREST
        // embed — the same principle that fixed message loading. Two simple
        // queries can never fail on a relationship-cache miss.
        val authors = profilesByIds(rows.map { it.authorId }.distinct())

        rows.mapNotNull { dto ->
            val note = dto.toDomain() ?: return@mapNotNull null
            val author = authors[dto.authorId] ?: return@mapNotNull null
            FriendNote(note = note, author = author)
        }.sortedByDescending { it.note.expiresAt }
    }

    override suspend fun setNote(body: String, minutes: Int): AppResult<Note> = safeCall {
        if (me() == null) error("You must be signed in to share a note.")
        val trimmed = body.trim()
        if (trimmed.isEmpty()) error("Write something first.")
        if (trimmed.length > MAX_NOTE_LENGTH) error("Notes are limited to $MAX_NOTE_LENGTH characters.")

        val safeMinutes = minutes.coerceIn(15, 1440)

        // Validated and rate limited server side; the RPC returns the stored row.
        val raw = client.postgrest.rpc(
            function = "set_my_note",
            parameters = buildJsonObject {
                put("p_body", JsonPrimitive(trimmed))
                put("p_minutes", JsonPrimitive(safeMinutes))
            },
        ).data

        runCatching { json.decodeFromString<SetNoteRow>(raw.trim()) }
            .getOrNull()
            ?.toDomain()
            ?: Note(
                authorId = me().orEmpty(),
                body = trimmed,
                expiresAt = Instant.now().plusSeconds(safeMinutes * 60L),
            )
    }

    override suspend fun deleteNote(): AppResult<Unit> = safeCall {
        if (me() == null) error("You must be signed in.")
        client.postgrest.rpc(function = "delete_my_note", parameters = buildJsonObject { })
    }

    private suspend fun readOne(authorId: String): Note? {
        val dto = client.from(TABLE_NOTES)
            .select(columns = Columns.list(*NOTE_COLUMNS)) {
                filter {
                    eq("author_id", authorId)
                    gt("expires_at", liveFilter())
                }
                limit(1)
            }
            .decodeList<NoteDto>()
            .firstOrNull() ?: return null

        return dto.toDomain()?.copy(author = profilesByIds(listOf(authorId))[authorId])
    }

    /** One query for every author on screen. Best effort: a note can render without a photo. */
    private suspend fun profilesByIds(ids: List<String>): Map<String, Profile> = runCatching {
        client.from("profiles")
            .select(columns = Columns.list("id", "username", "display_name", "avatar_url", "bio", "last_seen_at", "created_at")) {
                filter { isIn("id", ids) }
            }
            .decodeList<ProfileDto>()
            .associate { it.id to it.toDomain() }
    }.getOrDefault(emptyMap())

    /** The row `set_my_note` returns (a composite `public.notes`). */
    @Serializable
    private data class SetNoteRow(
        @SerialName("author_id") val authorId: String = "",
        val body: String = "",
        @SerialName("expires_at") val expiresAt: String? = null,
    ) {
        fun toDomain(): Note = Note(
            authorId = authorId,
            body = body,
            expiresAt = TimeFormat.parse(expiresAt) ?: Instant.now().plusSeconds(3600),
        )
    }

    private companion object {
        const val TABLE_NOTES = "notes"
        const val MAX_NOTE_LENGTH = 80

        /** Plain columns only — author profiles are joined in a second query. */
        val NOTE_COLUMNS = arrayOf(
            "author_id",
            "body",
            "expires_at",
            "created_at",
            "updated_at",
        )
    }
}
