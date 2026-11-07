package com.minichat.app.core.util

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * MINI CHAT time formatting. Everything is done with java.time (minSdk 26) so there is no
 * desugaring layer and no locale surprises.
 */
object TimeFormat {

    private val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
    private val weekday = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
    private val shortDate = DateTimeFormatter.ofPattern("dd MMM", Locale.getDefault())
    private val longDate = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())
    private val dayHeader = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault())
    private val fullStamp = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.getDefault())

    /** Parses PostgREST timestamps ("2026-11-07T10:15:16.691836+00:00" or "…Z"). */
    fun parse(raw: String?): Instant? {
        if (raw.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(raw).toInstant() }
            .recoverCatching { Instant.parse(raw) }
            .recoverCatching { ZonedDateTime.parse(raw).toInstant() }
            .getOrNull()
    }

    private fun zoned(raw: String?): ZonedDateTime? =
        parse(raw)?.atZone(ZoneId.systemDefault())

    /** 14:32 */
    fun messageTime(raw: String?): String {
        val time = zoned(raw) ?: return ""
        return time.format(clock)
    }

    /** Compact timestamp for the conversation list. */
    fun conversationTime(raw: String?, now: Instant = Instant.now()): String {
        val time = zoned(raw) ?: return ""
        val today = now.atZone(ZoneId.systemDefault()).toLocalDate()
        val date = time.toLocalDate()
        return when {
            date == today -> time.format(clock)
            date == today.minusDays(1) -> "Yesterday"
            date.isAfter(today.minusDays(7)) -> time.format(weekday)
            date.year == today.year -> time.format(shortDate)
            else -> time.format(dayHeader)
        }
    }

    /** Section header inside a conversation: "Today", "Yesterday", "12 March 2026". */
    fun dayHeader(raw: String?, now: Instant = Instant.now()): String {
        val time = zoned(raw) ?: return ""
        val today = now.atZone(ZoneId.systemDefault()).toLocalDate()
        val date = time.toLocalDate()
        return when (date) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> time.format(dayHeaderLong(date))
        }
    }

    private fun dayHeaderLong(date: LocalDate): DateTimeFormatter = dayHeader

    fun dayKey(raw: String?): String {
        val time = zoned(raw) ?: return ""
        return time.toLocalDate().toString()
    }

    /** Precise stamp shown in the message info sheet. */
    fun fullTimestamp(raw: String?): String {
        val time = zoned(raw) ?: return "Just now"
        return time.format(fullStamp)
    }

    /** "Online", "Last seen 5m ago", "Last seen yesterday", "Last seen 3 Mar". */
    fun lastSeen(raw: String?, isOnline: Boolean): String {
        if (isOnline) return "Online"
        val instant = parse(raw) ?: return "Offline"
        val minutes = java.time.Duration.between(instant, Instant.now()).toMinutes()
        return when {
            minutes < 1 -> "Last seen just now"
            minutes < 60 -> "Last seen ${minutes}m ago"
            minutes < 60 * 24 -> "Last seen ${minutes / 60}h ago"
            minutes < 60 * 48 -> "Last seen yesterday"
            else -> {
                val date = instant.atZone(ZoneId.systemDefault()).toLocalDate()
                "Last seen ${date.format(shortDate)}"
            }
        }
    }

    fun isOnline(raw: String?, thresholdSeconds: Long = 90): Boolean {
        val instant = parse(raw) ?: return false
        return java.time.Duration.between(instant, Instant.now()).seconds <= thresholdSeconds
    }

    fun toIso(instant: Instant): String = DateTimeFormatter.ISO_INSTANT.format(instant)

    @Suppress("unused")
    fun longDateLabel(raw: String?): String {
        val time = zoned(raw) ?: return ""
        return time.format(longDate)
    }
}
