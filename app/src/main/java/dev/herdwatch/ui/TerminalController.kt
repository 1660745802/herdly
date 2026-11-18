package dev.herdwatch.ui

import dev.herdwatch.R
import dev.herdwatch.data.InputMode
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
) {
    companion object {
        const val DEFAULT_FONT_SIZE = 13f
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
) {
    private val _state = MutableStateFlow(TerminalUiState())
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()

    private var config: HostConfig? = null
    private var session: ShellSession? = null
    private var screen: VtScreen? = null
    private var connectJob: Job? = null
    private var readJob: Job? = null
    private var pumpJob: Job? = null
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

    /**
     * Composer semantics for this session. LINE (the default) has the page buffer a whole line and
     * submit it with Enter. DIRECT streams each committed character (vim/tmux); the page sends
     * Enter as its own key, so a submit-style write must not append a second line terminator here.
     */
    @Volatile
    private var inputMode: InputMode = InputMode.LINE

    @Volatile
    private var dirty = false

    fun open(config: HostConfig, fontSize: Float = _state.value.fontSize) {
        val sameHost = _state.value.hostId == config.id && (session != null || connectJob != null)
        if (sameHost && _state.value.connected) return
        close()
        val gen = ++generation
        this.config = config
        val current = _state.value
        val cols = if (current.hostId == config.id) current.cols else 80
        val rows = if (current.hostId == config.id) current.rows else 24
        val buffer = VtScreen(cols, rows)
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
                sessionNonce = it.sessionNonce + 1,
            )
        }
        start(gen, config, cols, rows)
        startPump(gen)
    }

    fun reconnect() {
        val current = config ?: return
        close()
        open(current, _state.value.fontSize)
    }

    fun close() {
        generation++
        connectJob?.cancel()
        readJob?.cancel()
        pumpJob?.cancel()
        connectJob = null
        readJob = null
        pumpJob = null
        session?.close()
        session = null
        dirty = false
        _state.update { it.copy(connecting = false, connected = false) }
    }

    fun write(text: String, submit: Boolean = false) {
        val s = session ?: return
        if (!_state.value.connected) return
        writes.trySend(
            PendingWrite(
                generation = generation,
                session = s,
                text = text,
                newline = submit && inputMode == InputMode.LINE,
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
            scope.launch(Dispatchers.IO) { runCatching { s.resize(nextCols, nextRows) } }
        }
    }

    /** Persist the exact key the user accepted, then reconnect with that durable pin. */
    fun trustFingerprint() {
        val current = config ?: return
        val accepted = _state.value.pendingFingerprint ?: return
        try {
            onFingerprintTrusted(current.id, accepted)
        } catch (error: Exception) {
            fail(error)
            return
        }
        close()
        val pinned = current.copy(fingerprint = accepted)
        config = pinned
        val gen = ++generation
        val state = _state.value
        val cols = state.cols
        val rows = state.rows
        val buffer = VtScreen(cols, rows)
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

    /** Switches the composer between line buffering and per-character streaming. */
    fun setInputMode(mode: InputMode) {
        inputMode = mode
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
                _state.update {
                    it.copy(connecting = false, connected = true, fingerprint = observed ?: it.fingerprint)
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
                            _state.update { it.copy(connected = false, error = Str.get(R.string.term_connection_ended)) }
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
                it.copy(connecting = false, connected = false, error = null, pendingFingerprint = error.fingerprint)
            }
            return
        }
        val message = when (error) {
            is HostKeyMismatchException -> error.message
            else -> error.message?.takeIf { it.isNotBlank() } ?: Str.get(R.string.term_connection_lost)
        }
        _state.update { it.copy(connecting = false, connected = false, error = message) }
    }

    private companion object {
        const val FRAME_MILLIS = 33L
    }
}
