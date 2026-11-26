package dev.herdwatch.ui

import dev.herdwatch.R
import dev.herdwatch.ssh.HostKeyMismatchException
import dev.herdwatch.ssh.ShellAuthException
import dev.herdwatch.ssh.ShellChallengeUnsupportedException
import dev.herdwatch.ssh.ShellConnectException
import dev.herdwatch.ssh.UnknownHostKeyException
import dev.herdwatch.term.VtScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The error card must offer the action that can actually succeed: classify on the exception
 * type or the raw transport message, never on the translated sentence.
 */
class TerminalProblemTest {

    @Test
    fun typedFailuresMapToTheirClass() {
        assertEquals(TerminalProblem.AUTH, classifyTerminalFailure(ShellAuthException("auth failed")))
        assertEquals(TerminalProblem.KEY_FIRST, classifyTerminalFailure(UnknownHostKeyException("SHA256:a", "ED25519")))
        assertEquals(TerminalProblem.KEY_CHANGE, classifyTerminalFailure(HostKeyMismatchException("SHA256:a", "SHA256:b")))
        assertEquals(TerminalProblem.CHALLENGE, classifyTerminalFailure(ShellChallengeUnsupportedException("one-time code")))
    }

    @Test
    fun typedFailuresAreFoundThroughTheCauseChain() {
        val wrapped = ShellConnectException("localized wrapper", ShellAuthException("auth failed"))
        assertEquals(TerminalProblem.AUTH, classifyTerminalFailure(wrapped))
        val deep = RuntimeException("outer", IllegalStateException("mid", ShellChallengeUnsupportedException("otp")))
        assertEquals(TerminalProblem.CHALLENGE, classifyTerminalFailure(deep))
    }

    @Test
    fun networkFailuresAreRecognisedByTransportType() {
        assertEquals(
            TerminalProblem.NETWORK,
            classifyTerminalFailure(ShellConnectException("连接超时", java.net.SocketTimeoutException("timeout"))),
        )
        assertEquals(
            TerminalProblem.NETWORK,
            classifyTerminalFailure(ShellConnectException("无法解析主机", java.net.UnknownHostException("bad.host"))),
        )
        assertEquals(
            TerminalProblem.NETWORK,
            classifyTerminalFailure(ShellConnectException("被拒绝", java.net.ConnectException("Connection refused"))),
        )
    }

    @Test
    fun networkFailuresAreRecognisedByRawTransportMessage() {
        // JSch flattens some failures into a plain exception whose message is the raw English text.
        assertEquals(
            TerminalProblem.NETWORK,
            classifyTerminalFailure(ShellConnectException("连接失败", Exception("timeout: socket is not established"))),
        )
        assertEquals(
            TerminalProblem.NETWORK,
            classifyTerminalFailure(Exception("ENETUNREACH (Network is unreachable)")),
        )
    }

    @Test
    fun translatedSentencesNeverDriveClassification() {
        // A localized message alone must not be pattern-matched: only types and raw causes count.
        assertEquals(TerminalProblem.OTHER, classifyTerminalFailure(ShellConnectException("认证失败：检查用户名、密码或私钥")))
        assertEquals(TerminalProblem.OTHER, classifyTerminalFailure(ShellConnectException("网络不可达")))
        assertEquals(TerminalProblem.OTHER, classifyTerminalFailure(ShellConnectException("Connection timed out")))
    }

    @Test
    fun anythingElseIsOther() {
        assertEquals(TerminalProblem.OTHER, classifyTerminalFailure(IllegalStateException("boom")))
        assertEquals(TerminalProblem.OTHER, classifyTerminalFailure(ShellConnectException("shell ended")))
    }

    @Test
    fun unfixableByDiallingFailuresOfferEditHost() {
        assertEquals(TerminalErrorAction.EDIT_HOST, terminalErrorAction(TerminalProblem.AUTH))
        assertEquals(TerminalErrorAction.EDIT_HOST, terminalErrorAction(TerminalProblem.CHALLENGE))
        // No changed-key confirmation path exists on the terminal page; the card routes to the
        // editor rather than offering a reconnect that can never succeed.
        assertEquals(TerminalErrorAction.EDIT_HOST, terminalErrorAction(TerminalProblem.KEY_CHANGE))
    }

    @Test
    fun recoverableFailuresKeepReconnect() {
        assertEquals(TerminalErrorAction.RECONNECT, terminalErrorAction(TerminalProblem.NETWORK))
        assertEquals(TerminalErrorAction.RECONNECT, terminalErrorAction(TerminalProblem.BRIDGE))
        assertEquals(TerminalErrorAction.RECONNECT, terminalErrorAction(TerminalProblem.OTHER))
        assertEquals(TerminalErrorAction.RECONNECT, terminalErrorAction(null))
        // First contact is owned by the non-dismissible fingerprint dialog, not the error card.
        assertEquals(TerminalErrorAction.RECONNECT, terminalErrorAction(TerminalProblem.KEY_FIRST))
    }

    @Test
    fun titleSaysFailedWhenTheUserMustFixSomething() {
        assertEquals(R.string.terminal_connection_failed, terminalErrorTitleRes(TerminalProblem.AUTH))
        assertEquals(R.string.terminal_connection_failed, terminalErrorTitleRes(TerminalProblem.KEY_CHANGE))
        assertEquals(R.string.terminal_connection_failed, terminalErrorTitleRes(TerminalProblem.CHALLENGE))
        assertEquals(R.string.terminal_connection_lost, terminalErrorTitleRes(TerminalProblem.NETWORK))
        assertEquals(R.string.terminal_connection_lost, terminalErrorTitleRes(TerminalProblem.OTHER))
        assertEquals(R.string.terminal_connection_lost, terminalErrorTitleRes(null))
    }

    @Test
    fun hintsNameTheNextStepOnlyWhereOneIsNeeded() {
        assertEquals(R.string.terminal_problem_auth_hint, terminalErrorHintRes(TerminalProblem.AUTH))
        assertEquals(R.string.terminal_problem_key_change_hint, terminalErrorHintRes(TerminalProblem.KEY_CHANGE))
        assertEquals(R.string.terminal_problem_challenge_hint, terminalErrorHintRes(TerminalProblem.CHALLENGE))
        assertNull(terminalErrorHintRes(TerminalProblem.NETWORK))
        assertNull(terminalErrorHintRes(TerminalProblem.OTHER))
        assertNull(terminalErrorHintRes(null))
    }
}

/**
 * Reconnecting the same host keeps the reader's output: the old grid moves into scrollback
 * behind a separator, and nothing from the dead session (alternate screen, modes, cursor,
 * title, status) leaks into the new one.
 */
class TerminalReconnectBufferTest {

    private fun plain(line: dev.herdwatch.term.VtLine): String =
        line.runs.joinToString("") { it.text }.trimEnd()

    @Test
    fun reconnectKeepsOutputAndStartsOnABlankGrid() {
        val buffer = VtScreen(40, 10, 5000)
        buffer.feed("line one\r\nline two\r\n".toByteArray())
        beginReconnectedSession(buffer, "── reconnected ──")

        val snap = buffer.snapshot()
        val history = snap.lines.take(snap.scrollbackCount).map(::plain)
        assertTrue(history.any { it.contains("line one") })
        assertTrue(history.any { it.contains("line two") })
        assertTrue(history.any { it.contains("── reconnected ──") })
        // The new session starts at home on a blank grid.
        assertEquals(0, snap.cursorRow)
        assertEquals(0, snap.cursorCol)
        val grid = snap.lines.drop(snap.scrollbackCount).map(::plain)
        assertTrue(grid.all { it.isBlank() })
        // New output lands at the top, below the preserved history.
        buffer.feed("fresh prompt$ ".toByteArray())
        val after = buffer.snapshot()
        assertTrue(plain(after.lines[after.scrollbackCount]).startsWith("fresh prompt$"))
    }

    @Test
    fun deadAlternateScreenAndModesCannotLeak() {
        val buffer = VtScreen(40, 10, 5000)
        buffer.feed("shell output\r\n".toByteArray())
        // A full-screen program: alternate grid, application cursor keys, bracketed paste,
        // hidden cursor, origin mode, a window title and a status report.
        buffer.feed(
            ("\u001b[?1049h\u001b[?1h\u001b[?2004h\u001b[?25l\u001b[?6h" +
                "\u001b]0;vim model.py\u0007\u001b]7501;state=working:kind=build\u0007" +
                "vim screen text").toByteArray(),
        )
        assertTrue(buffer.snapshot().alternateScreen)

        beginReconnectedSession(buffer, "── reconnected ──")
        val snap = buffer.snapshot()
        assertFalse(snap.alternateScreen)
        assertFalse(snap.applicationCursorKeys)
        assertFalse(snap.bracketedPaste)
        assertTrue(snap.cursorVisible)
        assertNull(snap.title)
        assertNull(snap.status)
        assertEquals(0, snap.cursorRow)
        assertEquals(0, snap.cursorCol)
        // The primary grid's content survived; the alternate grid is discarded, as on a real
        // terminal, and the separator marks the new session.
        val history = snap.lines.take(snap.scrollbackCount).map(::plain)
        assertTrue(history.any { it.contains("shell output") })
        assertTrue(history.any { it.contains("── reconnected ──") })
        assertFalse(history.any { it.contains("vim screen text") })
    }

    @Test
    fun aHalfParsedSequenceFromTheDeadSessionDoesNotEatTheReset() {
        val buffer = VtScreen(40, 10, 5000)
        buffer.feed("output\r\n\u001b[31".toByteArray()) // dies mid-CSI
        beginReconnectedSession(buffer, "SEP")
        val snap = buffer.snapshot()
        assertTrue(snap.lines.take(snap.scrollbackCount).map(::plain).any { it.contains("SEP") })
        buffer.feed("hello".toByteArray())
        val after = buffer.snapshot()
        assertTrue(plain(after.lines[after.scrollbackCount]).startsWith("hello"))
    }

    @Test
    fun repeatedReconnectsAccumulateHistory() {
        val buffer = VtScreen(40, 10, 5000)
        buffer.feed("first session\r\n".toByteArray())
        beginReconnectedSession(buffer, "SEP")
        buffer.feed("second session\r\n".toByteArray())
        beginReconnectedSession(buffer, "SEP")
        val snap = buffer.snapshot()
        val history = snap.lines.take(snap.scrollbackCount).map(::plain)
        assertTrue(history.any { it.contains("first session") })
        assertTrue(history.any { it.contains("second session") })
        assertEquals(2, history.count { it.contains("SEP") })
    }

    /**
     * A host that refuses the shell channel refuses it every time. Retrying six times buried the
     * error under a wall of separators and still said "reconnecting".
     */
    @Test
    fun aRefusedShellIsNotRetriedAndSendsTheUserToTheConnection() {
        val refused = dev.herdwatch.ssh.ShellRefusedException("no shell for this account")
        assertEquals(TerminalProblem.SHELL_REFUSED, classifyTerminalFailure(refused))
        assertEquals(TerminalErrorAction.EDIT_HOST, terminalErrorAction(TerminalProblem.SHELL_REFUSED))
        assertNull(retryDelayMs(1, refused))
        assertTrue(dev.herdwatch.ssh.isShellRefusal(RuntimeException("failed to send channel request")))
        assertTrue(dev.herdwatch.ssh.isShellRefusal(RuntimeException("channel is not opened")))
    }

    @Test
    fun anEmptyBufferDoesNotCollectSeparators() {
        val buffer = VtScreen(40, 10, 5000)
        repeat(5) { beginReconnectedSession(buffer, "SEP") }
        val snap = buffer.snapshot()
        assertEquals(0, snap.lines.map(::plain).count { it.contains("SEP") })
    }
}
