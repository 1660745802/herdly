package dev.herdwatch.ssh

import com.jcraft.jsch.JSchException
import dev.herdwatch.model.AgentStatus
import dev.herdwatch.model.HostConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real JSch -> loopback Paramiko -> canned bridge; no Android runtime or external host. */
class HerdrClientTransportTest {
    @Test(timeout = 20_000)
    fun `connect read and mutate use real SSH exec channels`() = withFixture { fixture ->
        HerdrClient(fixture.config()).use { client ->
            client.connect()
            assertTrue(client.isConnected)
            assertEquals(fixture.fingerprint, client.fingerprint)
            assertEquals("0.9.5" to 22, client.ping())
            val before = client.readPane("w1:p1")
            assertEquals(12L, before.revision)
            assertTrue(before.text.contains("Command: npm test"))
            assertFalse(before.truncated)
            client.sendKeys("w1:p1", listOf("1", "enter"))
            val after = client.readPane("w1:p1")
            assertEquals(13L, after.revision)
            assertTrue(after.text.contains("Approval accepted"))
            val snapshot = client.snapshot()
            assertEquals(4, snapshot.agents.size)
            val agent = snapshot.agents.single { it.paneId == "w1:p1" }
            assertEquals(AgentStatus.REVIEW, agent.status)
            assertEquals(4L, agent.completionSeq)
            assertEquals(13L, snapshot.structure.panes.single { it.id == "w1:p1" }.revision)
            assertEquals(1, fixture.count("mutations"))
        }
        fixture.awaitCount("active_transports", 0)
    }

    @Test(timeout = 20_000)
    fun `outer read cancellation closes socket under one second and fresh client recovers`() =
        withFixture("--latency-ms", "3000", "--delay-method", "pane.read") { fixture ->
            HerdrClient(fixture.config()).use { client ->
                client.connect()
                assertEquals("0.9.5" to 22, client.ping()) // Delay must not affect handshake/ping.
                val started = System.nanoTime()
                try {
                    withTimeout(700) { client.readPane("w1:p1") }
                    fail("delayed pane.read should be cancelled")
                } catch (_: TimeoutCancellationException) {
                    val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                    assertTrue("cancellation took ${elapsedMs}ms", elapsedMs < 1_000)
                }
                assertEquals("request reached the server before cancellation", 1, fixture.count("pane_reads"))
                assertFalse(client.isConnected)
                fixture.awaitCount("active_transports", 0) // Verify remote socket closure too.
                val failure = requestFailure { client.connect() }
                assertTrue("cancelled client must stay closed", failure is IllegalStateException)
            }
            HerdrClient(fixture.config()).use { fresh ->
                fresh.connect()
                assertEquals("0.9.5" to 22, fresh.ping())
                assertEquals(12L, fresh.snapshot().structure.panes.single { it.id == "w1:p1" }.revision)
                assertEquals(12L, fresh.readPane("w1:p1").revision)
                assertEquals(2, fixture.count("pane_reads"))
            }
            fixture.awaitCount("active_transports", 0)
        }

    @Test(timeout = 20_000)
    fun `own request deadline reports timeout failure and closes connection without retry`() =
        withFixture("--latency-ms", "3000", "--delay-method", "pane.read") { fixture ->
            HerdrClient(fixture.config(), requestTimeoutMillis = 700).use { client ->
                client.connect()
                val failure = requestFailure { client.readPane("w1:p1") }
                assertTrue("own deadline must report a request error", failure is HerdrRequestTimeoutException)
                assertFalse(failure is CancellationException)
                assertFalse(client.isConnected)
                fixture.awaitCount("active_transports", 0)
                assertEquals(1, fixture.count("pane_reads"))
            }
            HerdrClient(fixture.config()).use { fresh ->
                fresh.connect()
                assertEquals(4, fresh.snapshot().agents.size)
            }
        }

    @Test(timeout = 20_000)
    fun `lost send keys reply is uncertain and not retried but fresh snapshot shows one commit`() =
        lostMutationReply(prompt = false)

    @Test(timeout = 20_000)
    fun `lost prompt reply is uncertain and not retried but fresh snapshot shows one commit`() =
        lostMutationReply(prompt = true)

    private fun lostMutationReply(prompt: Boolean) = withFixture("--scenario", "accepted-response-lost") { fixture ->
        HerdrClient(fixture.config()).use { client ->
            client.connect()
            assertEquals(12L, client.readPane("w1:p1").revision)
            val failure = requestFailure {
                if (prompt) client.prompt("w1:p1", "private fixture prompt")
                else client.sendKeys("w1:p1", listOf("1", "enter"))
            }
            assertFalse("transport loss is not cancellation", failure is CancellationException)
            assertFalse("transport loss is not a pin mismatch", failure is HostKeyMismatchException)
            fixture.awaitCount("active_transports", 0)
            assertEquals(1, fixture.count("drops"))
            assertEquals(1, fixture.count("mutation_requests")) // Includes failed/duplicate attempts.
            assertEquals(1, fixture.count("mutations"))
            assertFalse(client.isConnected)
        }
        HerdrClient(fixture.config()).use { fresh ->
            fresh.connect()
            val snapshot = fresh.snapshot()
            val agent = snapshot.agents.single { it.paneId == "w1:p1" }
            assertEquals(4413L, agent.stateChangeSeq)
            assertEquals(if (prompt) AgentStatus.WAITING else AgentStatus.REVIEW, agent.status)
            assertEquals(if (prompt) 3L else 4L, agent.completionSeq)
            assertEquals(13L, snapshot.structure.panes.single { it.id == "w1:p1" }.revision)
            val read = fresh.readPane("w1:p1")
            assertEquals(13L, read.revision)
            assertTrue(read.text.contains(if (prompt) "New simulated task received" else "Approval accepted"))
            assertFalse(read.text.contains("private fixture prompt"))
            assertEquals(1, fixture.count("mutation_requests"))
            assertEquals(1, fixture.count("mutations"))
        }
        fixture.awaitCount("active_transports", 0)
    }

    @Test(timeout = 20_000)
    fun `interactive shell rejects an unpinned key before password authentication`() = withFixture("--canned") { fixture ->
        ShellSession(fixture.unpinnedConfig()).use { shell ->
            val failure = requestFailure { shell.connect() }
            assertTrue("expected unknown host key, got ${failure.javaClass.simpleName}", failure is UnknownHostKeyException)
            failure as UnknownHostKeyException
            assertEquals(fixture.fingerprint, failure.fingerprint)
            assertFalse(shell.isConnected)
        }
        fixture.awaitCount("active_transports", 0)
        assertEquals("credentials must not reach an unconfirmed host", 0, fixture.count("auth_attempts"))
    }

    @Test(timeout = 20_000)
    fun `bridge rejects an unpinned key before password authentication`() = withFixture("--canned") { fixture ->
        HerdrClient(fixture.unpinnedConfig()).use { client ->
            val failure = requestFailure { client.connect() }
            assertTrue("expected unknown host key, got ${failure.javaClass.simpleName}", failure is UnknownHostKeyException)
            failure as UnknownHostKeyException
            assertEquals(fixture.fingerprint, failure.fingerprint)
            assertFalse(client.isConnected)
        }
        fixture.awaitCount("active_transports", 0)
        assertEquals("credentials must not reach an unconfirmed host", 0, fixture.count("auth_attempts"))
    }

    @Test(timeout = 20_000)
    fun `unreachable primary address falls through to the fallback`() = withFixture { fixture ->
        // The LAN address is what the phone would try first at home; a dead one stands in for
        // "not on this network" so the fallback route has to carry the session.
        val dead = HostConfig(
            id = "transport-test", label = "loopback fixture", host = "127.0.0.1", port = 1,
            user = "fixture", secret = fixture.password, fingerprint = fixture.fingerprint,
            fallbackHost = "127.0.0.1", fallbackPort = fixture.port,
        )
        HerdrClient(dead).use { client ->
            client.connect()
            assertTrue(client.isConnected)
            assertEquals("127.0.0.1:${fixture.port}", client.activeAddress)
            assertEquals("0.9.5" to 22, client.ping())
        }
        fixture.awaitCount("active_transports", 0)
    }

    @Test(timeout = 20_000)
    fun `rejected password does not fall through to the fallback`() = withFixture { fixture ->
        // Reaching the host and being refused is a decision, not a routing problem: retrying the
        // other address would double every wrong password and hide the real cause.
        val wrongPassword = fixture.config().copy(
            secret = "wrong-password",
            fallbackHost = "127.0.0.1",
            fallbackPort = 1,
        )
        HerdrClient(wrongPassword).use { client ->
            val failure = requestFailure { client.connect() }
            assertTrue("expected JSch authentication failure, got ${failure.javaClass.simpleName}", failure is JSchException)
            assertNull(client.activeAddress)
            assertFalse(client.isConnected)
        }
        fixture.awaitCount("active_transports", 0)
        assertEquals("only the primary address may see a password attempt", 1, fixture.count("auth_attempts"))
    }

    @Test(timeout = 20_000)
    fun `matching pin with wrong password stays an authentication failure`() = withFixture { fixture ->
        HerdrClient(fixture.config().copy(secret = "wrong-password")).use { client ->
            val failure = requestFailure { client.connect() }
            assertTrue("expected JSch authentication failure, got ${failure.javaClass.simpleName}", failure is JSchException)
            assertTrue("JSch must report auth failure", failure.message.orEmpty().contains("Auth fail", ignoreCase = true))
            assertFalse(failure is HostKeyMismatchException)
            assertFalse(client.isConnected)
        }
        fixture.awaitCount("active_transports", 0)
        assertTrue(fixture.count("auth_attempts") >= 1)
        HerdrClient(fixture.config()).use { fresh ->
            fresh.connect()
            assertEquals("0.9.5" to 22, fresh.ping())
        }
        fixture.awaitCount("active_transports", 0)
    }

    @Test(timeout = 20_000)
    fun `actual pin mismatch is rejected before password authentication`() = withFixture { fixture ->
        val wrongPin = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        assertNotEquals(wrongPin, fixture.fingerprint)
        HerdrClient(fixture.config().copy(fingerprint = wrongPin)).use { client ->
            val failure = requestFailure { client.connect() }
            assertTrue("expected host key mismatch, got ${failure.javaClass.simpleName}", failure is HostKeyMismatchException)
            failure as HostKeyMismatchException
            assertEquals(wrongPin, failure.expected)
            assertEquals(fixture.fingerprint, failure.actual)
            assertFalse(client.isConnected)
        }
        fixture.awaitCount("active_transports", 0)
        assertEquals("password authentication must never reach the mismatched host", 0, fixture.count("auth_attempts"))
        HerdrClient(fixture.config()).use { fresh ->
            fresh.connect()
            assertEquals("0.9.5" to 22, fresh.ping())
        }
        fixture.awaitCount("active_transports", 0)
    }

    private suspend fun requestFailure(block: suspend () -> Unit): Exception {
        try {
            block()
        } catch (failure: Exception) {
            // A stalled request must fail the test, not masquerade as the expected error.
            if (failure is CancellationException) throw failure
            return failure
        }
        throw AssertionError("request unexpectedly succeeded")
    }

    private fun withFixture(vararg options: String, block: suspend (Fixture) -> Unit) = runBlocking {
        Fixture().use { fixture ->
            try {
                fixture.start(options.toList())
                withTimeout(8_000) { block(fixture) }
            } catch (failure: Throwable) {
                throw AssertionError("SSH transport regression failed. Fixture log:\n${fixture.logs()}", failure)
            }
        }
    }

    private class Fixture : AutoCloseable {
        private val directory = Files.createTempDirectory("herdr-transport-").toFile()
        private val readyFile = File(directory, "ready.json")
        private val statusFile = File(directory, "status.json")
        private val logFile = File(directory, "fixture.log")
        val password = UUID.randomUUID().toString()
        private var process: Process? = null
        private lateinit var ready: JsonObject
        val fingerprint: String get() = ready.getValue("fingerprint").jsonPrimitive.content
        val port: Int get() = ready.getValue("port").jsonPrimitive.int

        suspend fun start(options: List<String>) {
            process = ProcessBuilder(listOf(
                python!!, "-u", script!!.absolutePath, "--canned", "--port", "0",
                "--key", File(directory, "host-key").absolutePath,
                "--user", "fixture", "--password", password,
                "--ready-file", readyFile.absolutePath, "--status-file", statusFile.absolutePath,
            ) + options).redirectErrorStream(true).redirectOutput(logFile).start()
            withTimeout(8_000) {
                while (!readyFile.isFile) {
                    check(process!!.isAlive) { "fixture exited before readiness" }
                    delay(10)
                }
            }
            ready = readJson(readyFile)
        }

        fun config() = HostConfig(
            id = "transport-test", label = "loopback fixture", host = "127.0.0.1",
            port = ready.getValue("port").jsonPrimitive.int, user = "fixture", secret = password,
            fingerprint = fingerprint,
        )

        /** A host the user has never confirmed: no pin at all. */
        fun unpinnedConfig() = config().copy(fingerprint = null)

        fun count(name: String): Int = readJson(statusFile).getValue(name).jsonPrimitive.int

        suspend fun awaitCount(name: String, expected: Int) = withTimeout(2_000) {
            while (count(name) != expected) delay(10)
        }

        fun logs(): String = if (logFile.isFile) logFile.readText().takeLast(8_000) else "(no fixture log)"

        override fun close() {
            try {
                process?.let { child ->
                    child.destroy()
                    if (!child.waitFor(2, TimeUnit.SECONDS)) {
                        child.destroyForcibly()
                        check(child.waitFor(2, TimeUnit.SECONDS)) { "fixture process did not stop" }
                    }
                    child.inputStream.close()
                    child.errorStream.close()
                    child.outputStream.close()
                }
            } finally {
                check(directory.deleteRecursively()) { "could not delete fixture temporary directory" }
            }
        }

        private fun readJson(file: File) = Json.parseToJsonElement(file.readText()).jsonObject
    }

    companion object {
        private var python: String? = null
        private var script: File? = null

        @JvmStatic
        @BeforeClass
        fun requireLocalFixture() {
            script = generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
                .map { File(it, "tools/dev_fake_ssh.py") }.firstOrNull { it.isFile }
            checkNotNull(script) { "Cannot locate tools/dev_fake_ssh.py from the project cwd or its parents" }
            val candidates = listOfNotNull(System.getenv("HERDR_TEST_PYTHON"), System.getenv("PYTHON"), "python", "python3").distinct()
            python = candidates.firstOrNull { executable ->
                var probe: Process? = null
                try {
                    // Keep probe output private in its pipe. Redirect.DISCARD is absent
                    // from the Android compile stubs used even for JVM unit tests.
                    val child = ProcessBuilder(executable, "-c", "import paramiko")
                        .redirectErrorStream(true).start()
                    probe = child
                    child.waitFor(3, TimeUnit.SECONDS) && child.exitValue() == 0
                } catch (_: Exception) {
                    false
                } finally {
                    probe?.let {
                        it.destroyForcibly()
                        check(it.waitFor(2, TimeUnit.SECONDS)) { "Python discovery process did not stop" }
                        it.inputStream.close()
                        it.errorStream.close()
                        it.outputStream.close()
                    }
                }
            }
            assumeTrue(
                "Real SSH tests skipped: Python with Paramiko is unavailable. Install Paramiko in a venv and set HERDR_TEST_PYTHON to its interpreter, or put python/python3 on PATH.",
                python != null,
            )
        }
    }
}
