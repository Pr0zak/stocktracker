package com.stocktracker.app.ui

import com.stocktracker.app.data.remote.HttpStatusException
import com.stocktracker.app.ui.detail.Lens
import com.stocktracker.app.ui.detail.LensStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Which of the five ways a lens can be blank this one was.
 *
 * The distinction that earns its keep here is 404-versus-everything-else. The signals backend
 * returns a 404 for a name with too little history to have a 200-week line, or with no filings of a
 * given kind — that is the server answering, not failing, and no number of retries will conjure the
 * data. Getting it wrong in that direction would put a Retry button under half the small caps on
 * the list, and a control that never works is worse than no control.
 */
class LensTest {

    private data class Block(val count: Int)

    @Test
    fun `a value worth showing is ready`() {
        val lens = Lens.from(Result.success(Block(3))) { it.count > 0 }
        assertEquals(LensStatus.READY, lens.status)
        assertEquals(3, lens.value!!.count)
    }

    @Test
    fun `a value below the display threshold is empty, not ready`() {
        val lens = Lens.from(Result.success(Block(0))) { it.count > 0 }
        assertEquals(LensStatus.EMPTY, lens.status)
        assertNull("an empty lens must not hand the screen something to draw", lens.value)
    }

    @Test
    fun `a null success is empty`() {
        val lens = Lens.from(Result.success<Block?>(null)) { true }
        assertEquals(LensStatus.EMPTY, lens.status)
    }

    @Test
    fun `404 is the server answering, so it is empty and offers no retry`() {
        val lens = Lens.from(
            Result.failure<Block?>(HttpStatusException(404, "http://x/trend/ABC", null)),
        ) { true }
        assertEquals(LensStatus.EMPTY, lens.status)
        assertEquals(false, lens.isFailed)
    }

    @Test
    fun `410 and 204 are answers too`() {
        for (code in listOf(410, 204)) {
            val lens = Lens.from(
                Result.failure<Block?>(HttpStatusException(code, "http://x/y", null)),
            ) { true }
            assertEquals("HTTP $code should read as empty", LensStatus.EMPTY, lens.status)
        }
    }

    @Test
    fun `a 500, a timeout and a dead socket are failures worth retrying`() {
        val cases = listOf<Throwable>(
            HttpStatusException(500, "http://x/y", "boom"),
            HttpStatusException(502, "http://x/y", null),
            SocketTimeoutException("timeout"),
            IOException("no route to host"),
        )
        for (e in cases) {
            val lens = Lens.from(Result.failure<Block?>(e)) { true }
            assertEquals("$e should read as failed", LensStatus.FAILED, lens.status)
            assertEquals(true, lens.isFailed)
        }
    }

    @Test
    fun `the default is idle — nothing has been asked`() {
        val lens = Lens<Block>()
        assertEquals(LensStatus.IDLE, lens.status)
        assertNull(lens.value)
        assertEquals(false, lens.isFailed)
        assertEquals(false, lens.isEmpty)
        assertEquals(false, lens.isNotApplicable)
    }

    @Test
    fun `not applicable is its own state, distinct from empty`() {
        assertEquals(true, Lens.notApplicable.isNotApplicable)
        assertEquals(
            "a lens that does not exist for this instrument must never read as one we checked",
            false,
            Lens.notApplicable.isEmpty,
        )
    }
}
