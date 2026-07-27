package com.stocktracker.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangelogTest {

    @Test
    fun `a single-version upgrade shows only that version's notes`() {
        val notes = Changelog.between("0.54.0", "0.55.0")
        assertEquals(Changelog.forVersion("0.55.0"), notes)
    }

    @Test
    fun `skipping a release shows every version in between`() {
        // The in-app updater only ever offers the latest build, so skipping is the normal case —
        // someone on 0.53.0 who updates to 0.55.0 must not silently lose 0.54.0's notes.
        val notes = Changelog.between("0.53.0", "0.55.0")
        assertTrue(notes.containsAll(Changelog.forVersion("0.55.0")))
        assertTrue(notes.containsAll(Changelog.forVersion("0.54.0")))
        assertTrue("0.53.0's own notes are not 'new'", !notes.containsAll(Changelog.forVersion("0.53.0")))
    }

    @Test
    fun `an unknown previous version falls back to just the new one`() {
        // A build older than anything recorded, or a local dev build.
        assertEquals(Changelog.forVersion("0.55.0"), Changelog.between("0.1.0", "0.55.0"))
    }

    @Test
    fun `no previous version means a fresh install and shows nothing extra`() {
        // The sheet itself suppresses fresh installs; this just pins the data-layer behaviour.
        assertEquals(Changelog.forVersion("0.55.0"), Changelog.between(null, "0.55.0"))
    }

    @Test
    fun `the same version produces nothing new`() {
        assertEquals(Changelog.forVersion("0.55.0"), Changelog.between("0.55.0", "0.55.0"))
    }

    @Test
    fun `an unreleased version has no notes rather than throwing`() {
        assertTrue(Changelog.forVersion("9.9.9").isEmpty())
        assertTrue(Changelog.between("0.54.0", "9.9.9").isEmpty())
    }

    @Test
    fun `a long gap is capped so it can't become a wall of text`() {
        val notes = Changelog.between("0.53.0", "0.55.0", limit = 3)
        assertEquals(3, notes.size)
    }

    @Test
    fun `every entry is short enough to read at a glance`() {
        for ((version, lines) in listOf("0.58.0", "0.55.0", "0.54.0").map { it to Changelog.forVersion(it) }) {
            assertTrue("$version has no notes", lines.isNotEmpty())
            assertTrue("$version has too many bullets: ${lines.size}", lines.size <= 5)
            for (l in lines) {
                assertTrue("too long in $version: $l", l.length <= 100)
                assertTrue("bullets shouldn't end in a period: $l", !l.endsWith("."))
            }
        }
    }

    @Test
    fun `recent returns releases newest first for the on-demand view`() {
        val recent = Changelog.recent()
        assertTrue("expected several releases", recent.size >= 3)
        assertEquals("0.58.0", recent.first().first)
        // Every listed release must actually have notes — an empty section would render as a bare
        // version heading with nothing under it.
        assertTrue(recent.all { it.second.isNotEmpty() })
    }

    @Test
    fun `recent is capped`() {
        assertEquals(2, Changelog.recent(limit = 2).size)
    }
}
