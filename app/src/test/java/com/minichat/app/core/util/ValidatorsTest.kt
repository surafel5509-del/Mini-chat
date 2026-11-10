package com.minichat.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidatorsTest {

    @Test
    fun `valid emails pass`() {
        assertNull(Validators.validateEmail("ada@minichat.test"))
        assertNull(Validators.validateEmail(" grace.lovelace+chat@example.co.uk "))
    }

    @Test
    fun `invalid emails fail`() {
        assertNotNull(Validators.validateEmail(""))
        assertNotNull(Validators.validateEmail("not-an-email"))
        assertNotNull(Validators.validateEmail("missing@tld"))
    }

    @Test
    fun `password rules require eight characters`() {
        assertNotNull(Validators.validatePassword("short"))
        assertNull(Validators.validatePassword("password123"))
    }

    @Test
    fun `usernames are lowercased and restricted`() {
        assertEquals("ada_lovelace", Validators.sanitizeUsername("Ada_Lovelace"))
        assertEquals("ada", Validators.sanitizeUsername("ada!!"))
        assertNull(Validators.validateUsername("ada_lovelace"))
        assertNotNull(Validators.validateUsername("ab"))
        assertNotNull(Validators.validateUsername("Ada"))
        assertNotNull(Validators.validateUsername("1ada"))
    }

    @Test
    fun `usernames must start with a letter and be lowercase`() {
        // Valid
        assertNull(Validators.validateUsername("ada_lovelace"))
        assertNull(Validators.validateUsername("ada99"))
        assertNull(Validators.validateUsername("  ada  "))

        // Invalid starts / case
        assertNotNull(Validators.validateUsername("1ada"))
        assertNotNull(Validators.validateUsername("_ada"))
        assertNotNull(Validators.validateUsername("Ada"))

        // Length boundaries
        assertNotNull(Validators.validateUsername("ab"))
        assertNull(Validators.validateUsername("a".repeat(24)))
        assertNotNull(Validators.validateUsername("a".repeat(25)))
    }

    @Test
    fun `sanitizer strips leading non letters so typing always yields a valid start`() {
        assertEquals("ada", Validators.sanitizeUsername("1ada"))
        assertEquals("ada", Validators.sanitizeUsername("__ada"))
        assertEquals("ada99", Validators.sanitizeUsername("Ada99"))
        assertEquals("", Validators.sanitizeUsername("123"))
        assertEquals(24, Validators.sanitizeUsername("a".repeat(40)).length)
    }

    @Test
    fun `display names reject blanks and long strings`() {
        assertNotNull(Validators.validateDisplayName("   "))
        assertNotNull(Validators.validateDisplayName("x".repeat(Validators.MAX_DISPLAY_NAME_LENGTH + 1)))
        assertNull(Validators.validateDisplayName("Ada Lovelace"))
    }

    @Test
    fun `bio is capped`() {
        assertNull(Validators.validateBio(""));
        assertNotNull(Validators.validateBio("x".repeat(Validators.MAX_BIO_LENGTH + 1)))
    }

    @Test
    fun `messages cannot be blank or absurdly long`() {
        assertNotNull(Validators.validateMessage("   "))
        assertNotNull(Validators.validateMessage("x".repeat(Validators.MAX_MESSAGE_LENGTH + 1)))
        assertNull(Validators.validateMessage("Hello there"))
    }

    @Test
    fun `report reasons must say something`() {
        assertNotNull(Validators.validateReportReason(""))
        assertNull(Validators.validateReportReason("Spam or scam"))
        assertTrue(Validators.validateReportReason("x".repeat(2)) != null)
    }
}
