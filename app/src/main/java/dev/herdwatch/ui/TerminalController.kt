package dev.herdwatch.ui

import dev.herdwatch.R
import dev.herdwatch.i18n.Str
import dev.herdwatch.model.HostConfig
import dev.herdwatch.ssh.HostKeyMismatchException
import dev.herdwatch.ssh.ShellAuthException
import dev.herdwatch.ssh.ShellChallengeUnsupportedException
import dev.herdwatch.ssh.ShellConnectException
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

/**
 * What kind of failure killed the session. Decides the error card's title and its one primary
 * action: a wrong password is fixed by editing the connection, not by dialling it again.
 *
 * [BRIDGE] exists for the app's shared failure vocabulary but is never produced here: the
 * terminal is a raw SSH shell and does not talk to the Herdr bridge.
 */
enum class TerminalProblem { AUTH, KEY_FIRST, KEY_CHANGE, CHALLENGE, NETWORK, BRIDGE, SHELL_REFUSED, OTHER }

/** What the error card's primary button does for a failure class. */
internal enum class TerminalErrorAction { RECONNECT, EDIT_HOST }

/** What the terminal page needs to draw one frame. */
data class TerminalUiState(
    val hostId: String? = null,
    val hostLabel: String = "",
    val target: String = "",
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val error: String? = null,
    /** Class of [error], derived from the exception type / raw transport message, never from copy. */
    val problem: TerminalProblem? = null,
    /** Transient, recoverable notice (e.g. dropped input). The session stays alive. */
    val inputNotice: String? = null,
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
    private var noticeJob: Job? = null

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

    /** One consumer preserves UI event order; bounded capacity prevents a stalled SSH write from
     * retaining an unbounded paste backlog (which can contain secrets) in process memory. */
    private val writes = Channel<PendingWrite>(WRITE_QUEUE_CAPACITY)

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
        // Reconnecting the SAME host keeps the output the user was reading: the old grid is
        // scrolled into scrollback behind a quiet separator instead of being thrown away. Only a
        // different host starts a clean buffer. The dead session's alternate screen, modes and
        // cursor are reset first so they cannot leak into the new shell.
        val previous = screen
        val buffer = if (current.hostId == config.id && previous != null) {
            beginReconnectedSession(previous, Str.get(R.string.terminal_session_separator))
            previous
        } else {
            VtScreen(cols, rows, SCROLLBACK_LINES)
        }
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
                problem = null,
                inputNotice = null,
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
        noticeJob?.cancel()
        noticeJob = null
        session?.close()
        session = null
        dirty = false
        _state.update {
            it.copy(connecting = false, connected = false, retrying = false, retryAttempt = 0, inputNotice = null)
        }
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
        val queued = writes.trySend(
            PendingWrite(
                generation = generation,
                session = s,
                text = text,
                newline = submit,
            )
        )
        if (queued.isFailure) {
            // Input backpressure is not a session failure: killing a healthy shell over a large
            // paste destroyed the output the user was reading. Drop the newest input, say so, and
            // keep the session and buffer alive. The notice names the dropped keys so a silently
            // changed command is never a surprise.
            val overloadedGeneration = generation
            val overloadedSession = s
            noticeJob?.cancel()
            noticeJob = scope.launch {
                if (generation != overloadedGeneration || session !== overloadedSession) return@launch
                val notice = Str.get(R.string.term_input_queue_full)
                _state.update { it.copy(inputNotice = notice) }
                delay(INPUT_NOTICE_MILLIS)
                _state.update { if (it.inputNotice == notice) it.copy(inputNotice = null) else it }
            }
        }
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
        val reconnectGeneration = generation
        scope.launch {
            // The pin write is JSON + AES + SharedPreferences.commit(): disk I/O, not click work.
            val saved = withContext(Dispatchers.IO) {
                runCatching { onFingerprintTrusted(current.id, accepted) }
            }
            // Persistence can outlive this page. Never let its completion replace a newer host's
            // session or accept a fingerprint that changed while the dialog was on screen.
            if (generation != reconnectGeneration || config !== current ||
                _state.value.hostId != current.id || _state.value.pendingFingerprint != accepted
            ) return@launch
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
                        error = null,
                        problem = null,
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
        val problem = classifyTerminalFailure(error)
        if (error is UnknownHostKeyException) {
            _state.update {
                it.copy(
                    connecting = false,
                    connected = false,
                    error = null,
                    problem = problem,
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
            _state.update {
                it.copy(connecting = false, connected = false, error = message, problem = problem, retrying = false, retryAttempt = 0)
            }
            return
        }
        retryAttempt++
        val wait = retryDelayMs(retryAttempt, error)
        if (wait == null) {
            _state.update { it.copy(connecting = false, connected = false, error = message, problem = problem, retrying = false) }
            return
        }
        _state.update {
            it.copy(
                connecting = false,
                connected = false,
                error = message,
                problem = problem,
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
        const val WRITE_QUEUE_CAPACITY = 256

        /** How long the dropped-input notice stays up before it clears itself. */
        const val INPUT_NOTICE_MILLIS = 3_500L
    }
}

/**
 * Classify a failure on the exception type or the RAW transport message, never on the localized
 * sentence: a copy edit must not turn a wrong password into a network problem.
 */
internal fun classifyTerminalFailure(error: Throwable): TerminalProblem {
    generateSequence(error) { it.cause }.forEach { cause ->
        when (cause) {
            is UnknownHostKeyException -> return TerminalProblem.KEY_FIRST
            is HostKeyMismatchException -> return TerminalProblem.KEY_CHANGE
            is ShellChallengeUnsupportedException -> return TerminalProblem.CHALLENGE
            is ShellAuthException -> return TerminalProblem.AUTH
            is dev.herdwatch.ssh.ShellRefusedException -> return TerminalProblem.SHELL_REFUSED
        }
    }
    generateSequence(error) { it.cause }.forEach { cause ->
        if (cause is java.net.SocketTimeoutException ||
            cause is java.net.UnknownHostException ||
            cause is java.net.NoRouteToHostException ||
            cause is java.net.SocketException // includes ConnectException
        ) {
            return TerminalProblem.NETWORK
        }
        // ShellConnectException messages are translated sentences; only causes below them carry
        // the transport's own words.
        if (cause !is ShellConnectException) {
            val raw = cause.message.orEmpty().lowercase()
            if (NETWORK_MESSAGE_MARKERS.any { raw.contains(it) }) return TerminalProblem.NETWORK
        }
    }
    return TerminalProblem.OTHER
}

/** Raw (English) transport markers, matched against cause messages only. */
private val NETWORK_MESSAGE_MARKERS = listOf(
    "timeout", "timed out", "socket is not established",
    "connection refused", "connection reset", "broken pipe", "socket closed",
    "enetunreach", "ehostunreach", "network is unreachable", "no route to host",
    "unknownhost", "name or service not known", "session is down",
)

/**
 * The one primary action for a failure class. Auth, challenge and changed-key failures can never
 * succeed by dialling again — the user has to change the connection or verify the key first.
 * KEY_FIRST is handled by the non-dismissible fingerprint dialog, not the error card.
 */
internal fun terminalErrorAction(problem: TerminalProblem?): TerminalErrorAction = when (problem) {
    TerminalProblem.AUTH, TerminalProblem.CHALLENGE, TerminalProblem.KEY_CHANGE,
    TerminalProblem.SHELL_REFUSED -> TerminalErrorAction.EDIT_HOST
    else -> TerminalErrorAction.RECONNECT
}

/** "Connection failed" for failures that need the user; "Connection lost" for a dropped link. */
internal fun terminalErrorTitleRes(problem: TerminalProblem?): Int = when (problem) {
    TerminalProblem.AUTH, TerminalProblem.KEY_FIRST, TerminalProblem.KEY_CHANGE, TerminalProblem.CHALLENGE,
    TerminalProblem.SHELL_REFUSED -> R.string.terminal_connection_failed
    else -> R.string.terminal_connection_lost
}

/** A one-line next step under the error text, or null when the message already says it. */
internal fun terminalErrorHintRes(problem: TerminalProblem?): Int? = when (problem) {
    TerminalProblem.AUTH -> R.string.terminal_problem_auth_hint
    TerminalProblem.KEY_CHANGE -> R.string.terminal_problem_key_change_hint
    TerminalProblem.CHALLENGE -> R.string.terminal_problem_challenge_hint
    TerminalProblem.SHELL_REFUSED -> R.string.terminal_problem_shell_refused_hint
    else -> null
}

/**
 * Prepare a kept [VtScreen] for a new session of the same host: everything the dead session left
 * on the active grid is scrolled into scrollback behind a dim separator, and the new shell starts
 * on a blank grid at home.
 *
 * The reset is fed as escape sequences because the buffer's own API is private: ST + CAN abort
 * any half-parsed sequence, DECSTR (CSI ! p) clears modes/pen/scroll region, exiting
 * 1049 restores the primary grid (a full-screen program's alternate grid is discarded, exactly as
 * a real terminal does), and the title/status of the dead program are cleared. Pure JVM, so the
 * unit tests exercise it against a real buffer.
 */
internal fun beginReconnectedSession(buffer: VtScreen, separator: String) {
    val initial = buffer.snapshot()
    // First settle the parser and the modes: a session can die mid-escape-sequence, and the
    // dead program's alternate screen, title or status must not survive into the new shell.
    val prefix = buildString {
        append("\u001b\\") // ST: end any dangling OSC/string from the dead session
        append('\u0018') // CAN: abort any half-parsed CSI
        if (initial.alternateScreen) append("\u001b[?1049l")
        append("\u001b[!p") // DECSTR: pen, autowrap, origin, cursor keys, bracketed paste, region
        append("\u001b]0;\u0007") // forget the dead program's window title
        append("\u001b]7501;state=clear\u0007") // and its status report
    }
    buffer.feed(prefix.toByteArray(Charsets.UTF_8))

    val snap = buffer.snapshot()
    val rows = snap.rows
    // Scroll only the rows that hold output into history — pushing the blank padding below
    // them would bury the old output under a screenful of empty lines.
    val lastContent = snap.lines.drop(snap.scrollbackCount).indexOfLast { line ->
        line.runs.any { it.text.isNotBlank() }
    }
    // Nothing to keep means nothing to divide. A host that refuses every attempt used to stack a
    // wall of separators, one per retry, above an empty screen.
    if (lastContent < 0) {
        buffer.feed("[H7".toByteArray(Charsets.UTF_8))
        return
    }
    val separatorRows = maxOf(1, (terminalCells(separator) + snap.cols - 1) / snap.cols)
    val suffix = buildString {
        append("\u001b[${rows};1H")
        repeat(lastContent + 1) { append('\n') } // each feed at the bottom scrolls one top row out
        append("\u001b[H") // separator goes on row 0, directly under the preserved output
        append("\u001b[2m")
        append(separator)
        append("\u001b[0m")
        append("\u001b[${rows};1H")
        repeat(separatorRows) { append('\n') } // scroll the separator itself into history
        append("\u001b[H")
        append("\u001b7") // overwrite the DECSC slot so DECRC cannot restore the dead cursor
    }
    buffer.feed(suffix.toByteArray(Charsets.UTF_8))
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
    // A refused shell channel is the server's answer about this account, not a lost packet.
    error !is dev.herdwatch.ssh.ShellRefusedException &&
    error !is HostKeyMismatchException &&
        error !is UnknownHostKeyException &&
        error !is dev.herdwatch.ssh.ShellAuthException &&
        // A host that wants a one-time code will want it again on every attempt.
        error !is dev.herdwatch.ssh.ShellChallengeUnsupportedException
