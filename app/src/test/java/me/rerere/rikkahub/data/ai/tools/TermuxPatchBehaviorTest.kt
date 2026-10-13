package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxPatchBehaviorTest {
    @Test
    fun `start acknowledgement is ignored until command completion marker arrives`() {
        val marker = "request-marker"

        assertFalse(isTerminalResult(err = -1, exitCode = 0, stdout = "", marker = marker))
        assertFalse(isTerminalResult(err = -1, exitCode = 0, stdout = "partial output", marker = marker))
        assertTrue(isTerminalResult(err = -1, exitCode = 0, stdout = "partial output\n$marker", marker = marker))
        assertTrue(isTerminalResult(err = -1, exitCode = 7, stdout = "", marker = marker))
        assertTrue(isTerminalResult(err = 1, exitCode = 0, stdout = "", marker = marker))
    }

    @Test
    fun `termination marker is stripped without changing ordinary output`() {
        val marker = "request-marker"

        assertEquals("hello", stripTerminationMarker("hello\n$marker", marker))
        assertEquals("hello\n", stripTerminationMarker("hello\n", marker))
    }

    @Test
    fun `wrapped argv keeps command and arguments separate`() {
        val (executable, arguments) = buildMarkerWrappedArgv("/usr/bin/echo", arrayOf("two words"), "marker")

        assertTrue(executable.endsWith("/bash"))
        assertEquals("-c", arguments[0])
        assertEquals("/usr/bin/echo", arguments[2])
        assertArrayEquals(arrayOf("two words"), arguments.drop(3).toTypedArray())
        assertTrue(arguments[1].contains("marker"))
    }

    @Test
    fun `session parser only accepts Shuanyin sessions and ignores malformed rows`() {
        val sessions = parseSessions(
            "shuanyin_alpha\t1700000000\t1700000001\nother\t2\t3\nshuanyin_bad\tnope\t4\nmalformed"
        )

        assertEquals(2, sessions.size)
        assertEquals("shuanyin_alpha", sessions[0].name)
        assertEquals(1_700_000_000L, sessions[0].created)
        assertEquals(0L, sessions[1].created)
    }

    @Test
    fun `tmux text is sent as a literal argument`() {
        assertArrayEquals(
            arrayOf("send-keys", "-t", "session", "-l", "--", "echo hello; whoami"),
            TmuxOps.sendTextArgv("session", "echo hello; whoami"),
        )
    }
}
