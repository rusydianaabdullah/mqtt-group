package id.larus.mqttgroup

import id.larus.mqttgroup.mqtt.Topics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicsTest {

    @Test
    fun `builds the documented layout`() {
        assertEquals("home/g1/msg", Topics.messages("home", "g1"))
        assertEquals("home/g1/ctl", Topics.control("home", "g1"))
        assertEquals("home/g1/att/b7", Topics.attachment("home", "g1", "b7"))
        assertEquals("home/g1/loc/u9", Topics.location("home", "g1", "u9"))
        assertEquals("home/g1/presence/u9", Topics.presence("home", "g1", "u9"))
        assertEquals("home/g1/#", Topics.everything("home", "g1"))
    }

    @Test
    fun `strays slashes are trimmed rather than producing empty levels`() {
        assertEquals("home/g1/msg", Topics.messages("/home/", "g1"))
        assertEquals("home/g1/msg", Topics.messages("  home  ", "g1"))
    }

    @Test
    fun `an empty prefix falls back to the default`() {
        assertEquals(Topics.DEFAULT_PREFIX, Topics.normalise(""))
        assertEquals(Topics.DEFAULT_PREFIX, Topics.normalise("///"))
    }

    @Test
    fun `wildcards and whitespace are rejected`() {
        // A prefix containing + or # would silently turn every published topic into something
        // the broker refuses, which is a miserable thing to debug from a phone.
        assertFalse(Topics.isValidPrefix("home/+"))
        assertFalse(Topics.isValidPrefix("home/#"))
        assertFalse(Topics.isValidPrefix("my home"))
        assertFalse(Topics.isValidPrefix(""))
        assertFalse(Topics.isValidPrefix("home//nested"))
    }

    /**
     * The test that would have caught the phase 8 ringing bug on the day it was written.
     *
     * Every topic this app publishes has to be recognised by the receive side, and for a long
     * time the two lists were independent: `Topics` built the topics and `GroupSync` kept its
     * own private set of kinds. Adding `call` to one and not the other dropped every call
     * message between two phones at the very first check — nothing logged, nothing thrown, no
     * ring, and a caller left on "Calling…" until it timed out.
     *
     * So this walks every builder rather than restating the list: a new topic added without its
     * kind fails here instead of in somebody's living room.
     */
    @Test
    fun `every topic this app publishes is recognised by the receive side`() {
        val prefix = "house/family"
        val group = "g-1"
        val user = "u-1"
        val topics = listOf(
            Topics.messages(prefix, group),
            Topics.control(prefix, group),
            Topics.settings(prefix, group),
            Topics.call(prefix, group),
            Topics.audio(prefix, group, user),
            Topics.attachment(prefix, group, "blob-1"),
            Topics.location(prefix, group, user),
            Topics.presence(prefix, group, user),
        )
        for (topic in topics) {
            assertNotNull("no kind for $topic", Topics.kindOf(topic))
            // A prefix with a slash in it is the case that makes counting from the front wrong,
            // so the group id is found relative to the kind — and both have to work together.
            assertEquals("wrong group for $topic", group, Topics.groupIdOf(topic))
        }
    }

    @Test
    fun `a topic from a newer build is ignored rather than guessed at`() {
        assertNull(Topics.kindOf("familygroup/g-1/somethingnew"))
        assertNull(Topics.groupIdOf("familygroup/g-1/somethingnew"))
    }

    @Test
    fun `ordinary prefixes are accepted`() {
        assertTrue(Topics.isValidPrefix("familygroup"))
        assertTrue(Topics.isValidPrefix("house/family"))
        assertTrue(Topics.isValidPrefix("/leading-and-trailing/"))
    }
}
