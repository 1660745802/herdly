package dev.herdwatch.ssh

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import dev.herdwatch.R
import dev.herdwatch.i18n.Str
import dev.herdwatch.model.AuthType
import dev.herdwatch.model.HostConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A failure that happened while establishing or using an interactive shell. */
class ShellConnectException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The host presents a key we have never stored. Nothing has been sent yet: the caller must show
 * the fingerprint to the user and only then reconnect with that exact fingerprint pinned.
 */
class UnknownHostKeyException(val fingerprint: String, val keyType: String? = null) :
    Exception(Str.get(R.string.ssh_unknown_host_key, fingerprint))

/**
 * One interactive SSH shell (PTY + `shell` channel) for the in-app terminal.
 *
 * Everything JSch-side is configured per session, never through `JSch.setConfig`, so several
 * hosts with different pinning/authentication policies can be connected at once. Host keys
 * follow the same policy as [HerdrClient]: a pinned fingerprint is verified *inside* the
 * handshake (credentials are never offered to an unexpected host), and without a pin the
 * observed fingerprint is rejected and reported to the caller for explicit confirmation.
 *
 * There is no automatic reconnect: a closed session stays closed.
 */
class ShellSession(
    private val config: HostConfig,
) : AutoCloseable {
    private val connectMutex = Mutex()
    private val writeMutex = Mutex()
    private val stateGate = Any()

    @Volatile
    private var session: Session? = null

    @Volatile
    private var channel: ChannelShell? = null

    @Volatile
    private var closed = false

    @Volatile
    private var lastSeenFingerprint: String? = null
    private var lastSeenType: String? = null

    @Volatile
    private var ptyCols = 80

    @Volatile
    private var ptyRows = 24

    @Volatile
    var fingerprint: String? = null
        private set

    val isConnected: Boolean get() = !closed && channel?.isConnected == true

    suspend fun connect() = connectMutex.withLock {
        check(!closed) { Str.get(R.string.ssh_session_closed) }
        if (isConnected) return@withLock
        closingIo { openSessionAndShell() }
    }

    /**
     * Pumps the shell until EOF, closed, or cancellation. [onBytes] runs on the IO thread and
     * receives the shared read buffer plus the valid length — copy whatever you keep.
     */
    suspend fun read(onBytes: (ByteArray, Int) -> Unit) {
        val ch = channel?.takeIf { it.isConnected }
            ?: throw ShellConnectException(Str.get(R.string.ssh_not_connected, config.label))
        val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            // A blocked socket read only unblocks when the channel goes away.
            if (cause is CancellationException) runCatching { ch.disconnect() }
        }
        try {
            withContext(Dispatchers.IO) { pump(ch, onBytes) }
        } finally {
            handle?.dispose()
        }
    }

    /** Appends [text]; with [triggerNewline] a terminal Enter (CR) follows. */
    suspend fun write(text: String, triggerNewline: Boolean = false) = writeMutex.withLock {
        val ch = channel?.takeIf { it.isConnected }
            ?: throw ShellConnectException(Str.get(R.string.ssh_not_connected, config.label))
        withContext(Dispatchers.IO) {
            try {
                val out = ch.outputStream
                if (text.isNotEmpty()) out.write(text.toByteArray(Charsets.UTF_8))
                if (triggerNewline) out.write(ENTER)
                out.flush()
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                throw ShellConnectException(Str.get(R.string.ssh_write_failed, detail(e)), e)
            }
        }
    }

    /** Safe before the channel exists: the size is remembered and applied on connect. */
    suspend fun resize(cols: Int, rows: Int) = writeMutex.withLock {
        ptyCols = cols.coerceAtLeast(1)
        ptyRows = rows.coerceAtLeast(1)
        val ch = channel?.takeIf { it.isConnected } ?: return@withLock
        withContext(Dispatchers.IO) {
            runCatching { ch.setPtySize(ptyCols, ptyRows, 0, 0) }
        }
    }

    override fun close() {
        val (ch, s) = synchronized(stateGate) {
            if (closed) return
            closed = true
            val currentChannel = channel
            val currentSession = session
            channel = null
            session = null
            currentChannel to currentSession
        }
        runCatching { ch?.disconnect() }
        runCatching { s?.disconnect() }
    }

    // ------------------------------------------------------------------ internals

    private suspend fun pump(ch: ChannelShell, onBytes: (ByteArray, Int) -> Unit) {
        val buffer = ByteArray(READ_BUFFER_BYTES)
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = ch.inputStream.read(buffer)
                if (read < 0) return
                if (read == 0) continue
                onBytes(buffer, read)
            }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive() // cancellation is not a transport failure
            throw ShellConnectException(Str.get(R.string.ssh_read_failed, detail(e)), e)
        }
    }

    private fun openSessionAndShell() {
        val pinned = config.fingerprint?.takeIf { it.isNotBlank() }
        lastSeenFingerprint = null
        val jsch = JSch()
        jsch.hostKeyRepository = pinningRepository(pinned)
        if (config.authType == AuthType.KEY) {
            val passphrase = config.passphrase.takeIf { it.isNotBlank() }?.toByteArray()
            jsch.addIdentity(
                "herdwatch-key",
                config.secret.replace("\r\n", "\n").toByteArray(),
                null,
                passphrase,
            )
        }
        val s = jsch.getSession(config.user, config.host, config.port)
        s.setConfig(
            "PreferredAuthentications",
            if (config.authType == AuthType.PASSWORD) "password,keyboard-interactive" else "publickey",
        )
        // Strict checking always runs inside the handshake. With no pin the repository rejects the
        // key after recording its fingerprint, so credentials are never sent before confirmation.
        s.setConfig("StrictHostKeyChecking", "yes")
        // Match OpenSSH's preference so the fingerprint shown to the user is the one an
        // administrator gets from ssh-keygen, not whichever curve JSch happens to like first.
        s.setConfig("server_host_key", HOST_KEY_ALGORITHMS)
        s.serverAliveInterval = 30_000
        s.serverAliveCountMax = 3
        if (config.authType == AuthType.PASSWORD) s.setPassword(config.secret)
        s.timeout = SOCKET_TIMEOUT_MS
        synchronized(stateGate) {
            check(!closed) { Str.get(R.string.ssh_session_closed) }
            session = s
        }
        try {
            s.connect(CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            forget(s)
            runCatching { s.disconnect() }
            val observed = lastSeenFingerprint
            if (pinned != null && observed != null && observed != pinned) {
                throw HostKeyMismatchException(pinned, observed)
            }
            if (pinned == null && observed != null) {
                throw UnknownHostKeyException(observed, lastSeenType ?: s.hostKey?.type)
            }
            throw ShellConnectException(Str.get(R.string.ssh_connect_failed, config.host, detail(e)), e)
        }
        val actual = fingerprintOf(s)
        if (pinned != null && pinned != actual) {
            forget(s)
            runCatching { s.disconnect() }
            throw HostKeyMismatchException(pinned, actual)
        }
        fingerprint = actual

        val ch = try {
            (s.openChannel("shell") as ChannelShell).apply {
                setPtyType("xterm-256color")
                setPtySize(ptyCols, ptyRows, 0, 0)
                setEnv("TERM", "xterm-256color")
            }
        } catch (e: Exception) {
            forget(s)
            runCatching { s.disconnect() }
            throw ShellConnectException(Str.get(R.string.ssh_open_shell_failed, detail(e)), e)
        }
        try {
            ch.connect(CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            runCatching { ch.disconnect() }
            forget(s)
            runCatching { s.disconnect() }
            throw ShellConnectException(Str.get(R.string.ssh_open_shell_failed, detail(e)), e)
        }
        synchronized(stateGate) {
            if (closed) {
                runCatching { ch.disconnect() }
                runCatching { s.disconnect() }
                throw ShellConnectException(Str.get(R.string.ssh_session_closed))
            }
            channel = ch
        }
    }

    private fun forget(s: Session) {
        synchronized(stateGate) { if (session === s) session = null }
    }

    private fun pinningRepository(pinned: String?): HostKeyRepository = object : HostKeyRepository {
        override fun check(host: String, key: ByteArray): Int {
            val observed = fingerprintOf(key)
            lastSeenFingerprint = observed
            lastSeenType = keyAlgorithm(key)
            return if (pinned != null && observed == pinned) {
                HostKeyRepository.OK
            } else {
                HostKeyRepository.NOT_INCLUDED
            }
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID(): String = "herdwatch-pin"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    private fun fingerprintOf(s: Session): String {
        val encoded = s.hostKey?.key ?: error(Str.get(R.string.ssh_no_host_key))
        return fingerprintOf(Base64.getDecoder().decode(encoded))
    }

    /** Key type of the key we actually negotiated, for display next to the fingerprint. */
    val hostKeyType: String?
        get() = synchronized(stateGate) { session?.hostKey?.type }

    private fun fingerprintOf(key: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    /** Make a JSch failure readable, and make a timeout identifiable in the text. */
    private fun detail(e: Throwable): String {
        val message = e.message.orEmpty()
        val lower = message.lowercase()
        return when {
            e is ShellConnectException -> message
            lower.contains("auth fail") || lower.contains("auth cancel") || lower.contains("userauth") ->
                Str.get(R.string.ssh_auth_failed)
            lower.contains("timeout") || lower.contains("timed out") || lower.contains("not established") ->
                Str.get(R.string.ssh_connect_timeout, CONNECT_TIMEOUT_MS / 1000)
            lower.contains("connection refused") -> Str.get(R.string.ssh_refused)
            lower.contains("unknownhost") || lower.contains("name or service not known") ->
                Str.get(R.string.ssh_unknown_host, config.host)
            message.isBlank() -> e.javaClass.simpleName
            else -> message
        }
    }

    /** Runs blocking SSH work on IO, closing the session if the caller cancels. */
    private suspend fun <T> closingIo(block: () -> T): T = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { close() }
        Dispatchers.IO.dispatch(EmptyCoroutineContext) {
            if (!cont.isActive) return@dispatch
            try {
                cont.resume(block())
            } catch (t: Throwable) {
                cont.resumeWithException(t)
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 12_000
        const val SOCKET_TIMEOUT_MS = 15_000
        const val READ_BUFFER_BYTES = 12 * 1024

        /** A terminal Enter is CR, not LF: the remote PTY translates it. */
        val ENTER = byteArrayOf('\r'.code.toByte())
    }
}
