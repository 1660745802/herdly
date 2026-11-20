package dev.herdwatch.ui

import dev.herdwatch.ssh.HostKeyMismatchException
import dev.herdwatch.ssh.ShellAuthException
import dev.herdwatch.ssh.ShellChallengeUnsupportedException
import dev.herdwatch.ssh.ShellConnectException
import dev.herdwatch.ssh.UnknownHostKeyException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The automatic reconnect must stop for the failures the user has to fix, and only for those.
 */
class TerminalRetryPolicyTest {

    @Test
    fun transientFailuresAreRetried() {
        assertTrue(isRetryableFailure(ShellConnectException("connection refused")))
        assertTrue(isRetryableFailure(ShellConnectException("timeout")))
        assertTrue(isRetryableFailure(java.io.IOException("socket closed")))
    }

    @Test
    fun credentialAndKeyFailuresAreNotRetried() {
        assertFalse(isRetryableFailure(ShellAuthException("auth failed")))
        assertFalse(isRetryableFailure(HostKeyMismatchException("SHA256:a", "SHA256:b")))
        assertFalse(isRetryableFailure(UnknownHostKeyException("SHA256:a", "ED25519")))
        assertFalse(isRetryableFailure(ShellChallengeUnsupportedException("one-time code")))
    }

    @Test
    fun theBackoffGrowsAndIsBounded() {
        val delays = TerminalUiState.RETRY_DELAYS_MS
        assertTrue(delays.size >= 3)
        assertTrue(delays.first() <= 2_000)
        delays.toList().zipWithNext().forEach { (a, b) -> assertTrue("$a -> $b", b > a) }
        assertTrue(delays.last() <= 60_000)
    }

    @Test
    fun eachAttemptWaitsLongerAndTheLadderEnds() {
        val error = ShellConnectException("connection refused")
        val waits = (1..TerminalUiState.RETRY_DELAYS_MS.size).map { retryDelayMs(it, error) }
        assertEquals(TerminalUiState.RETRY_DELAYS_MS.toList(), waits)
        assertNull("past the ladder the app parks instead of dialling forever", retryDelayMs(waits.size + 1, error))
        assertNull(retryDelayMs(0, error))
    }

    @Test
    fun aFailureTheUserMustFixNeverWaits() {
        assertNull(retryDelayMs(1, ShellAuthException("auth failed")))
        assertNull(retryDelayMs(2, HostKeyMismatchException("SHA256:a", "SHA256:b")))
    }
}
