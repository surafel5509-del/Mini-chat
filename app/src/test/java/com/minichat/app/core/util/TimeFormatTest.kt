package com.minichat.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class TimeFormatTest {

    @Test
    fun `parse handles null, garbage and iso timestamps`() {
        assertNull(TimeFormat.parse(null))
        assertNull(TimeFormat.parse("not a date"))
        val parsed = TimeFormat.parse("2026-11-07T10:15:30Z")
        assertNotNull(parsed)
        assertEquals(Instant.parse("2026-11-07T10:15:30Z"), parsed)
    }

    @Test
    fun `day keys separate calendar days`() {
        assertEquals(
            TimeFormat.dayKey("2026-11-07T01:00:00Z"),
            TimeFormat.dayKey("2026-11-07T23:00:00Z"),
        )
        assertTrue(
            TimeFormat.dayKey("2026-11-07T23:00:00Z") !=
                TimeFormat.dayKey("2026-11-08T01:00:00Z"),
        )
    }

    @Test
    fun `online window is ninety seconds`() {
        val now = Instant.now()
        assertTrue(TimeFormat.isOnline(now.minus(10, ChronoUnit.SECONDS).toString()))
        assertFalse(TimeFormat.isOnline(now.minus(10, ChronoUnit.MINUTES).toString()))
        assertFalse(TimeFormat.isOnline(null))
    }

    @Test
    fun `last seen never returns an empty string`() {
        assertTrue(TimeFormat.lastSeen(null, isOnline = false).isNotBlank())
        assertTrue(TimeFormat.lastSeen(Instant.now().minus(1, ChronoUnit.DAYS).toString(), false).isNotBlank())
    }

    @Test
    fun `message time is formatted for short timestamps`() {
        val formatted = TimeFormat.messageTime("2026-11-07T10:15:30Z")
        assertTrue(formatted.isNotBlank())
    }

    @Test
    fun `toIso round trips`() {
        val instant = Instant.parse("2026-01-02T03:04:05Z")
        assertEquals(instant, Instant.parse(TimeFormat.toIso(instant)))
    }
}
