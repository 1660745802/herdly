package dev.herdwatch.ui

import dev.herdwatch.R
import dev.herdwatch.i18n.Str
import dev.herdwatch.model.HostConfig
import dev.herdwatch.ssh.HostKeyMismatchException
import dev.herdwatch.ssh.ShellSession
import dev.herdwatch.ssh.UnknownHostKeyException
import dev.herdwatch.term.VtScreen
import dev.herdwatch.term.VtSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel

/** What the terminal page needs to draw one frame. */
data class TerminalUiState(
    val hostId: String? = null,
    val hostLabel: String = "",
    val target: String = "",
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val error: String? = null,
    val fingerprint: String? = null,
    val snapshot: VtSnapshot? = null,
    val cols: Int = 80,
    val rows: Int = 24,
    val fontSize: Float = DEFAULT_FONT_SIZE,
    /** Bumped whenever the page should scroll back to the newest output. */
    val followRequests: Long = 0,
    /** Bumped when a fresh session starts, so the page can reset transient input state. */
    val sessionNonce: Long = 0,
    /** Set when the host has no saved pin yet: the page must ask the user before trusting it. */
    val pendingFingerprint: String? = null,
    /** Algorithm of the key behind [pendingFingerprint], so the user can compare with ssh-keygen. */
    val pendingKeyType: String? = null,
    /** 0 while idle; n while the controller is about to make its n-th automatic attempt. */
    val retryAttempt: Int = 0,
    /** True while an automatic reconnect is scheduled or running. */
    val retrying: Boolean = false,
) {
    companion object {
        const val DEFAULT_FONT_SIZE = 13f

        /** 1s, 2s, 4s, 8s, 16s, 30s: six tries over about a minute, then hand control back. */
        internal val RETRY_DELAYS_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000)
    }
}

/**
 * Owns one interactive SSH shell at a time.
 *
 * The page is a real PTY session, independent of the Herdr bridge: output bytes are fed into a
 * [VtScreen] and rendered as a terminal grid. Nothing here goes through Herdr, so a host without
 * Herdr installed is still fully usable.
 */
class TerminalController(
    private val scope: CoroutineScope,
    /** Called when the user confirms an unpinned host key, before connecting with that pin. */
    private val onFingerprintTrusted: (hostId: String, fingerprint: String) -> Unit = { _, _ -> },
    /**
     * Application context, used only to be told that the network came back. A dropped Wi-Fi or a
     * dead cellular link is the ordinary way a mobile session dies, and waiting for a keepalive to
     * time out (30s x 3) before noticing is not an answer. Null in JVM tests.
     */
    applicationContext: android.content.Context? = null,
) {
    private val _state = MutableStateFlow(TerminalUiState())
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()

    @Volatile
    private var config: HostConfig? = null
    @Volatile
    private var session: ShellSession? = null
    private var resizeJob: Job? = null
    @Volatile
    private var screen: VtScreen? = null
    private var connectJob: Job? = null
    private var readJob: Job? = null
    private var pumpJob: Job? = null

    // Written on Main, read by the IO write consumer below: without the volatile the consumer can
    // keep a stale epoch and deliver a keystroke into the session it was meant to abandon.
    @Volatile
    private var generation = 0L

    private data class PendingWrite(
        val generation: Long,
        val session: ShellSession,
        val text: String,
        val newline: Boolean,
    )

    /** One consumer preserves UI event order; independent IO coroutines may acquire a Mutex out of order. */
    private val writes = Channel<PendingWrite>(Channel.UNLIMITED)

    init {
        scope.launch(Dispatchers.IO) {
            for (request in writes) {
                if (request.generation != generation || session !== request.session || !_state.value.connected) continue
                runCatching { request.session.write(request.text, request.newline) }
                    .onFailure {
                        if (request.generation == generation && session === request.session) fail(it)
                    }
            }
        }
    }

    @Volatile
    private var dirty = false

    private var retryJob: Job? = null

    // Written from Main (manual reconnect, network callback) and from the IO write consumer's
    // failure path; a stale read would restart the backoff from 1s.
    @Volatile
    private var retryAttempt = 0
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private var networkManager: android.net.ConnectivityManager? = null

    init {
        val context = applicationContext?.applicationContext
        if (context != null) {
            runCatching {
                val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
                val callback = object : android.net.ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) {
                        // The link is back. Do not wait out the rest of the backoff: the user is
                        // looking at a dead screen and this is the cheapest possible retry.
                        if (_state.value.retrying && !_state.value.connected) {
                            // A timer already waiting must not also fire: two opens in a row leave
                            // the first session orphaned.
                            retryJob?.cancel()
                            retryAttempt = 0
                            scope.launch { reconnectInternal() }
                        }
                    }
                }
                manager?.registerDefaultNetworkCallback(callback)
                networkManager = manager
                networkCallback = callback
            }
        }
    }

    /**
     * [resetRetries] is false for the retry timer: an automatic attempt must not reset its own
     * counter, or the backoff never grows past the first second and a dead network is dialled
     * once per second forever.
     */
    fun open(config: HostConfig, fontSize: Float = _state.value.fontSize, resetRetries: Boolean = true) {
        val sameHost = _state.value.hostId == config.id && (session != null || connectJob != null)
        // An edited host (a new fallback address, a different user or port) must take effect on the
        // next open, not only after a manual reconnect: comparing ids alone kept the old session.
        // The label and the stored pin are not connection parameters — a renamed host, or a pin we
        // just confirmed, must not tear down a working session.
        val live = this.config
        val sameConfig = live != null &&
            live.endpoints == config.endpoints &&
            live.user == config.user &&
            live.authType == config.authType &&
            live.secret == config.secret &&
            live.passphrase == config.passphrase &&
            live.session == config.session
        if (sameHost && sameConfig && _state.value.connected) return
        close()
        if (resetRetries) retryAttempt = 0
        val gen = ++generation
        this.config = config
        val current = _state.value
        val cols = if (current.hostId == config.id) current.cols else 80
        val rows = if (current.hostId == config.id) current.rows else 24
        val buffer = VtScreen(cols, rows, SCROLLBACK_LINES)
        screen = buffer
        dirty = false
        _state.update {
            it.copy(
                hostId = config.id,
                hostLabel = config.label,
                target = "${config.user}@${config.host}:${config.port}",
                connecting = true,
                connected = false,
                error = null,
                pendingFingerprint = null,
                fingerprint = config.fingerprint,
                snapshot = buffer.snapshot(),
                cols = cols,
                rows = rows,
                fontSize = fontSize,
                retrying = false,
                retryAttempt = if (resetRetries) 0 else retryAttempt,
                sessionNonce = it.sessionNonce + 1,
            )
        }
        start(gen, config, cols, rows)
        startPump(gen)
    }

    fun reconnect() {
        retryAttempt = 0
        reconnectInternal()
    }

    /** Re-open the current config without touching the caller's intent (used by the retry timer). */
    private fun reconnectInternal() {
        val current = config ?: return
        close()
        open(current, _state.value.fontSize, resetRetries = false)
    }

    fun close() {
        generation++
        retryJob?.cancel()
        retryJob = null
        resizeJob?.cancel()
        resizeJob = null
        connectJob?.cancel()
        readJob?.cancel()
        pumpJob?.cancel()
        connectJob = null
        readJob = null
        pumpJob = null
        session?.close()
        session = null
        dirty = false
        _state.update { it.copy(connecting = false, connected = false, retrying = false, retryAttempt = 0) }
    }

    /** Releases the process-scoped network callback; without this every finished page leaks its VM. */
    fun dispose() {
        close()
        retryAttempt = 0
        runCatching { networkCallback?.let { networkManager?.unregisterNetworkCallback(it) } }
        networkCallback = null
        networkManager = null
        writes.close()
    }

    fun write(text: String, submit: Boolean = false) {
        val s = session ?: return
        if (!_state.value.connected) return
        writes.trySend(
            PendingWrite(
                generation = generation,
                session = s,
                text = text,
                newline = submit,
            )
        )
    }

    /** Sends a literal escape sequence, e.g. ESC or an arrow key. */
    fun key(sequence: String) = write(sequence)


    fun resize(cols: Int, rows: Int) {
        val nextCols = cols.coerceIn(20, 400)
        val nextRows = rows.coerceIn(6, 200)
        val current = _state.value
        if (current.cols == nextCols && current.rows == nextRows) return
        _state.update { it.copy(cols = nextCols, rows = nextRows) }
        screen?.resize(nextCols, nextRows)
        dirty = true
        val s = session
        if (s != null && current.connected) {
            // The keyboard animates over ~200ms and the grid changes size on every frame. Sending
            // each intermediate size means a queue of window-change signals, and a shell answers
            // every one of them by redrawing its prompt — which is how a user ends up staring at
            // three copies of their prompt. Only the settled size reaches the PTY.
            resizeJob?.cancel()
            val measuredFor = generation
            resizeJob = scope.launch(Dispatchers.IO) {
                delay(RESIZE_SETTLE_MILLIS)
                // A reconnect inside the settle window must not receive the old window size.
                if (measuredFor != generation || session !== s) return@launch
                runCatching { s.resize(nextCols, nextRows) }
            }
        }
    }

    /** Persist the exact key the user accepted, then reconnect with that durable pin. */
    fun trustFingerprint() {
        val current = config ?: return
        val accepted = _state.value.pendingFingerprint ?: return
        scope.launch {
            // The pin write is JSON + AES + SharedPreferences.commit(): disk I/O, not click work.
            val saved = withContext(Dispatchers.IO) {
                runCatching { onFingerprintTrusted(current.id, accepted) }
            }
            saved.onFailure {
                _state.update { state ->
                    state.copy(error = Str.get(R.string.term_pin_save_failed))
                }
                return@launch
            }
            trustAndReconnect(current, accepted)
        }
    }

    private fun trustAndReconnect(current: HostConfig, accepted: String) {
        close()
        val pinned = current.copy(fingerprint = accepted)
        config = pinned
        val gen = ++generation
        val state = _state.value
        val cols = state.cols
        val rows = state.rows
        val buffer = VtScreen(cols, rows, SCROLLBACK_LINES)
        screen = buffer
        dirty = false
        _state.update {
            it.copy(
                connecting = true,
                connected = false,
                error = null,
                pendingFingerprint = null,
                fingerprint = accepted,
                snapshot = buffer.snapshot(),
                sessionNonce = it.sessionNonce + 1,
            )
        }
        start(gen, pinned, cols, rows)
        startPump(gen)
    }

    fun setFontSize(size: Float) {
        _state.update { it.copy(fontSize = size.coerceIn(9f, 22f)) }
    }


    fun follow() {
        _state.update { it.copy(followRequests = it.followRequests + 1) }
    }

    private fun start(gen: Long, config: HostConfig, cols: Int, rows: Int) {
        connectJob = scope.launch {
            val s = ShellSession(config)
            session = s
            try {
                s.connect()
                if (gen != generation) {
                    s.close()
                    return@launch
                }
                s.resize(cols, rows)
                val observed = s.fingerprint
                retryAttempt = 0
                _state.update {
                    it.copy(
                        connecting = false,
                        connected = true,
                        fingerprint = observed ?: it.fingerprint,
                        retryAttempt = 0,
                        retrying = false,
                    )
                }
                readJob = launch(Dispatchers.IO) {
                    try {
                        s.read { bytes, length ->
                            if (gen == generation) {
                                screen?.feed(bytes, length)
                                dirty = true
                            }
                        }
                        if (gen == generation) {
                            // The socket ended without an exception (the usual way a mobile link
                            // dies). Route it through the same retry policy instead of parking a
                            // dead screen with a button.
                            fail(dev.herdwatch.ssh.ShellConnectException(Str.get(R.string.term_connection_ended)))
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (gen == generation) fail(e)
                    }
                }
            } catch (e: CancellationException) {
                s.close()
                throw e
            } catch (e: Exception) {
                s.close()
                if (gen == generation) fail(e)
            }
        }
    }

    private fun startPump(gen: Long) {
        pumpJob = scope.launch {
            while (isActive && gen == generation) {
                delay(FRAME_MILLIS)
                if (dirty) {
                    dirty = false
                    val shot = screen?.snapshot() ?: continue
                    _state.update { it.copy(snapshot = shot) }
                }
            }
        }
    }

    private fun fail(error: Throwable) {
        if (error is UnknownHostKeyException) {
            _state.update {
                it.copy(
                    connecting = false,
                    connected = false,
                    error = null,
                    pendingFingerprint = error.fingerprint,
                    pendingKeyType = error.keyType,
                )
            }
            return
        }
        val message = when (error) {
            is HostKeyMismatchException -> error.message
            else -> error.message?.takeIf { it.isNotBlank() } ?: Str.get(R.string.term_connection_lost)
        }
        if (!isRetryableFailure(error)) {
            retryAttempt = 0
            retryJob?.cancel()
            _state.update { it.copy(connecting = false, connected = false, error = message, retrying = false, retryAttempt = 0) }
            return
        }
        retryAttempt++
        val wait = retryDelayMs(retryAttempt, error)
        if (wait == null) {
            _state.update { it.copy(connecting = false, connected = false, error = message, retrying = false) }
            return
        }
        _state.update {
            it.copy(
                connecting = false,
                connected = false,
                error = message,
                retrying = true,
                retryAttempt = retryAttempt,
            )
        }
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(wait)
            if (!_state.value.connected) reconnectInternal()
        }
    }

    private companion object {
        const val FRAME_MILLIS = 33L
        const val RESIZE_SETTLE_MILLIS = 180L

        /**
         * Rows kept above the viewport. 2000 was about one `less` on a phone; 5000 is roughly what
         * the desktop sessions people compare us to still have, and costs ~3 MB at 80 columns.
         */
        const val SCROLLBACK_LINES = 5_000
    }
}

/**
 * A wrong password, an unconfirmed key, or a changed key fails the same way forever: retrying
 * those only burns battery and hides the one thing the user has to fix.
 */
/**
 * How long to wait before attempt [attempt] (1-based), or null when this failure must not be retried
 * or the ladder is exhausted. Pure so the ladder can be tested without a socket.
 */
internal fun retryDelayMs(attempt: Int, error: Throwable): Long? {
    if (!isRetryableFailure(error)) return null
    val ladder = TerminalUiState.RETRY_DELAYS_MS
    if (attempt < 1 || attempt > ladder.size) return null
    return ladder[attempt - 1]
}

internal fun isRetryableFailure(error: Throwable): Boolean =
    error !is HostKeyMismatchException &&
        error !is UnknownHostKeyException &&
        error !is dev.herdwatch.ssh.ShellAuthException &&
        // A host that wants a one-time code will want it again on every attempt.
        error !is dev.herdwatch.ssh.ShellChallengeUnsupportedException
