package dev.herdwatch.ui

import android.content.Context
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

/**
 * A terminal input endpoint, not a text editor.
 *
 * Android keyboards speak through [InputConnection]. A normal TextField turns those commands into
 * edits to a local document, which loses terminal events such as Backspace at an empty boundary and
 * lets arrows move a local caret. This view translates the commands directly to PTY bytes instead.
 * It keeps only unfinished IME composition (pinyin, kana, etc.) until the keyboard commits it.
 */
internal class TerminalInputView(context: Context) : View(context) {
    var onBytes: (String) -> Unit = {}
    var applicationCursorKeys: () -> Boolean = { false }

    /**
     * Mirrors DECSET 2004. Our own "paste" menu item already wraps its payload, but text pasted
     * through the keyboard — the way most people paste — went out raw, so a five-line paste ran as
     * five commands even in shells that asked to be told the difference.
     */
    var bracketedPaste: () -> Boolean = { false }

    /**
     * Which editing session the view currently represents.
     *
     * Bumped whenever the SSH session behind the view is replaced. An [InputConnection] captured the
     * value it was created with, so a commit the keyboard queued for the previous session is dropped
     * instead of being typed into the new one.
     */
    private var generation = 0

    /** Called on reconnect: forget the old line and force the keyboard to start a fresh connection. */
    fun resetSession() {
        generation += 1
        line.setLength(0)
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        manager.restartInput(this)
    }

    /**
     * What the IME believes is on the current line.
     *
     * The IME owns a document model even when the app does not: some keyboards refuse to send a
     * Backspace, or send (0, 0), when the editor reports no text before the cursor. This shadow is
     * never rendered and never used to reconstruct the line; it exists so deletion commands carry
     * the right count. Arrow keys move the remote cursor, not this shadow, so it can be stale — the
     * result is only ever a DEL, which the PTY treats as "erase left of cursor".
     */
    private val line = StringBuilder()

    fun markCommitted(text: String) {
        val next = shadowAfterCommit(line.toString(), text)
        line.setLength(0)
        line.append(next)
    }

    fun markDeleted(count: Int) {
        repeat(count) {
            if (line.isNotEmpty()) line.setLength(line.offsetByCodePoints(line.length, -1))
        }
    }

    fun markReturn() {
        line.setLength(0)
    }

    /** Text the IME may ask for; not the source of truth for the remote line. */
    fun lineText(): String = line.toString()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // A shell expects ASCII, so the keyboard should open in ASCII. With FILTER (or plain text)
        // a Chinese IME stays in pinyin composition: every command becomes a candidate row the user
        // has to confirm, and while composing we deliberately send nothing, so typing looks broken.
        // VISIBLE_PASSWORD would be the usual "latin only" trick, but MIUI marks password fields as
        // secure: the app then becomes impossible to screenshot or screen-share. URI is the next best
        // signal — IMEs switch to a latin layout with "/" and the suggestion row off, and it carries
        // no secure flag.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_URI
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        return TerminalInputConnection(this)
    }

    /** Focus first, then ask for the keyboard after Android has installed the input connection. */
    fun requestInput() {
        requestFocus()
        post {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val sequence = terminalKeySequence(event, applicationCursorKeys())
        if (sequence != null) {
            onBytes(sequence)
            when (keyCode) {
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> markReturn()
                KeyEvent.KEYCODE_DEL -> markDeleted(1)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private inner class TerminalInputConnection(target: View) : BaseInputConnection(target, false) {
        /** Unfinished IME composition (pinyin, kana, handwriting). Not yet sent anywhere. */
        private val composing = StringBuilder()
        private var composingCursor = 0

        /** Deduplicates the finalize-then-commit pattern some IMEs use. See [CompositionCommitGate]. */
        private val commitGate = CompositionCommitGate()

        /** The editing session this connection belongs to. A reconnect invalidates it. */
        private val generation = this@TerminalInputView.generation

        private val isCurrent: Boolean get() = generation == this@TerminalInputView.generation

        private fun emit(bytes: String) {
            // A commit queued by the keyboard can arrive after the SSH session was replaced. Sending
            // it would type into a different machine's shell.
            if (!isCurrent) return
            onBytes(bytes)
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val value = text?.toString().orEmpty()
            if (commitGate.commit(value)) return true
            composing.setLength(0)
            composingCursor = 0
            if (value.isEmpty()) return true
            val payload = terminalInputPayload(value)
            // Multi-line paste goes out wrapped in ESC[200~ .. ESC[201~ when the remote asked
            // for it (DECSET 2004), exactly like our own paste menu item.
            val wrap = bracketedPaste() && (value.contains('\n') || value.contains('\r'))
            emit(if (wrap) "\u001b[200~$payload\u001b[201~" else payload)
            markCommitted(value)
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            // Composition belongs to the IME until it is committed. Sending each pinyin rewrite
            // would duplicate and garble the eventual Chinese character at the remote PTY.
            val value = text?.toString().orEmpty()
            // A new composition is a new word, even when it repeats the previous one verbatim
            // (CJK/handwriting IMEs do this constantly). Without this the dedup gate would swallow
            // the commit of the repeated word and it would never reach the PTY.
            commitGate.composing(value)
            composing.setLength(0)
            composing.append(value)
            composingCursor = cursorFor(value, newCursorPosition)
            return true
        }

        override fun finishComposingText(): Boolean {
            // The Android contract finalizes whatever is composed. Keyboards that end with
            // finishComposingText and never call commitText (handwriting, voice, some CJK IMEs)
            // would otherwise silently drop the text the user just produced.
            if (composing.isNotEmpty()) {
                val value = composing.toString()
                composing.setLength(0)
                composingCursor = 0
                commitGate.finalized(value)
                emit(terminalInputPayload(value))
                markCommitted(value)
            }
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
            // This overload counts UTF-16 units. One emoji is two units but one terminal character,
            // so the count is converted before it becomes DEL bytes or the remote line loses half a
            // surrogate pair.
            delete(beforeUnits = beforeLength, afterUnits = afterLength)

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
            delete(beforeUnits = null, afterUnits = null, beforePoints = beforeLength, afterPoints = afterLength)

        private fun delete(
            beforeUnits: Int?,
            afterUnits: Int?,
            beforePoints: Int = 0,
            afterPoints: Int = 0,
        ): Boolean {
            if (composing.isNotEmpty()) {
                val dropBefore = beforeUnits?.let { composing.utf16ToCodePoints(composingCursor, it) } ?: beforePoints
                val dropAfter = afterUnits?.let { composing.utf16ToCodePoints(composingCursor, it, forward = true) } ?: afterPoints
                deleteComposing(dropBefore, dropAfter)
                return true
            }
            val delBefore = beforeUnits?.let { line.utf16ToCodePoints(line.length, it) } ?: beforePoints
            // Forward delete counts code points too: one emoji is one remote character, never two
            // deletes. The conversion scans the same shadow text backspace uses; the shadow ends at
            // the cursor (getTextAfterCursor reports nothing), so units the IME claims beyond it
            // cannot be verified and must not be replayed blindly per UTF-16 unit — that is what
            // split surrogate pairs into half-character deletes.
            val delAfter = afterUnits
                ?.let { units ->
                    val known = line.utf16ToCodePoints(line.length, units, forward = true)
                    // Anything the IME counts beyond our shadow cannot be inspected. Assume one
                    // unit per character there: dropping the request entirely made forward delete
                    // silently do nothing on keyboards that use it, and replaying it per unit is
                    // what split emoji into half-character deletes.
                    val unseen = (units - line.length).coerceAtLeast(0)
                    known + unseen
                }
                ?: afterPoints
            if (delBefore > 0) emit("\u007f".repeat(delBefore))
            if (delAfter > 0) emit("\u001b[3~".repeat(delAfter))
            // (0, 0) is defined as deleting nothing. Keyboards may issue it while synchronizing, and
            // turning that into a Backspace deleted characters the user never asked to remove.
            markDeleted(delBefore)
            return true
        }

        private fun deleteComposing(before: Int, after: Int) {
            val from = (composingCursor - before).coerceAtLeast(0)
            val to = (composingCursor + after).coerceAtMost(composing.length)
            if (to > from) composing.delete(from, to)
            composingCursor = from
        }

        override fun setSelection(start: Int, end: Int): Boolean {
            if (composing.isEmpty()) return true
            composingCursor = start.coerceIn(0, composing.length)
            return true
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            // Some IMEs route Enter here instead of commitText("\n"). Discarding the composition
            // dropped unfinished pinyin the user expected to commit; finalize it through the same
            // path as finishComposingText first, then press return.
            finishComposingText()
            emit("\r")
            markReturn()
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            val sequence = terminalKeySequence(event, applicationCursorKeys())
            if (sequence != null) {
                emit(sequence)
                when (event.keyCode) {
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> markReturn()
                    KeyEvent.KEYCODE_DEL -> markDeleted(1)
                }
                return true
            }
            return super.sendKeyEvent(event)
        }

        // The IME sees a line that is always at its end. Reporting it keeps deletion and the
        // backspace key functional on keyboards that consult the editor before acting.
        override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence? {
            val text = lineText()
            if (text.isEmpty() || length <= 0) return ""
            // `length` is in UTF-16 units. Trim to it without exposing half of a surrogate pair.
            var start = (text.length - length).coerceAtLeast(0)
            if (start > 0 && Character.isLowSurrogate(text[start])) start += 1
            return text.substring(start)
        }

        override fun getTextAfterCursor(length: Int, flags: Int): CharSequence? = ""

        override fun getSelectedText(flags: Int): CharSequence? = null

        override fun getCursorCapsMode(reqModes: Int): Int = 0

        override fun performContextMenuAction(id: Int): Boolean = false
    }

    /** Editor positions are 1-based; 0 and 1 both mean "after the last character". */
    private fun cursorFor(text: String, newCursorPosition: Int): Int =
        if (newCursorPosition <= 0) 0 else (newCursorPosition - 1).coerceAtMost(text.length)
}

/**
 * Dedup state machine for the finalize-then-commit IME pattern.
 *
 * States: idle (nothing finalized) and armed (a finalize transmitted [text]).
 * - [finalized] arms the gate with the text finishComposingText just sent.
 * - [commit] disarms unconditionally; it returns true only when the commit repeats the armed text,
 *   which the caller must swallow (sending both would duplicate the character at the prompt).
 * - [composing] with non-empty text disarms: a NEW composition starts a new word, so a later commit
 *   of the same string is a legitimate repeat, not a duplicate. Empty composition text only ends a
 *   composition (some IMEs clear it after finalizing) and must keep the gate armed.
 */
internal class CompositionCommitGate {
    private var armed: String? = null

    fun composing(text: String) {
        if (text.isNotEmpty()) armed = null
    }

    fun finalized(text: String) {
        armed = text
    }

    /** True when [value] duplicates the finalize and must be swallowed. Consumes the arm either way. */
    fun commit(value: String): Boolean {
        val duplicate = armed != null && armed == value
        armed = null
        return duplicate
    }
}

/**
 * Shadow line after a commit. A line break resets the shadow to the committed tail; IME Enter can
 * arrive as '\n', '\r\n' or a lone '\r' (commitText from some keyboards), so both break characters
 * count — otherwise the submitted text stayed in the shadow and skewed later DEL counts.
 */
internal fun shadowAfterCommit(previous: String, commit: String): String {
    val lastBreak = maxOf(commit.lastIndexOf('\n'), commit.lastIndexOf('\r'))
    return if (lastBreak >= 0) commit.substring(lastBreak + 1) else previous + commit
}

/**
 * How many code points sit in [units] UTF-16 units before or after [at].
 *
 * Units that run past the end of the known text are ignored here; callers decide what an
 * unverifiable request means (see the forward-delete path).
 */
internal fun CharSequence.utf16ToCodePoints(at: Int, units: Int, forward: Boolean = false): Int {
    if (units <= 0) return 0
    var remaining = units
    var index = at.coerceIn(0, length)
    var points = 0
    while (remaining > 0) {
        if (forward) {
            if (index >= length) break
            val ch = this[index]
            index += if (Character.isHighSurrogate(ch) && index + 1 < length && Character.isLowSurrogate(this[index + 1])) 2 else 1
            remaining -= if (Character.isHighSurrogate(ch)) 2 else 1
        } else {
            if (index <= 0) break
            val low = this[index - 1]
            val ch = if (Character.isLowSurrogate(low) && index - 2 >= 0 && Character.isHighSurrogate(this[index - 2])) {
                index -= 2
                this[index]
            } else {
                index -= 1
                this[index]
            }
            remaining -= if (Character.isHighSurrogate(ch)) 2 else 1
        }
        points += 1
    }
    return points
}

/** Maps a physical/editor key to the bytes expected by an xterm-style PTY. */
internal fun terminalKeySequence(event: KeyEvent, applicationCursorKeys: Boolean): String? {
    if (event.action != KeyEvent.ACTION_DOWN) return null

    if (event.isCtrlPressed && event.keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
        return ((event.keyCode - KeyEvent.KEYCODE_A) + 1).toChar().toString()
    }
    val mapped = when (event.keyCode) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "\r"
        KeyEvent.KEYCODE_DEL -> "\u007f"
        KeyEvent.KEYCODE_FORWARD_DEL -> "\u001b[3~"
        KeyEvent.KEYCODE_ESCAPE -> "\u001b"
        KeyEvent.KEYCODE_TAB -> if (event.isShiftPressed) "\u001b[Z" else "\t"
        KeyEvent.KEYCODE_DPAD_UP -> arrowKeySequence('A', applicationCursorKeys)
        KeyEvent.KEYCODE_DPAD_DOWN -> arrowKeySequence('B', applicationCursorKeys)
        KeyEvent.KEYCODE_DPAD_RIGHT -> arrowKeySequence('C', applicationCursorKeys)
        KeyEvent.KEYCODE_DPAD_LEFT -> arrowKeySequence('D', applicationCursorKeys)
        KeyEvent.KEYCODE_MOVE_HOME -> "\u001b[H"
        KeyEvent.KEYCODE_MOVE_END -> "\u001b[F"
        KeyEvent.KEYCODE_INSERT -> "\u001b[2~"
        KeyEvent.KEYCODE_PAGE_UP -> "\u001b[5~"
        KeyEvent.KEYCODE_PAGE_DOWN -> "\u001b[6~"
        else -> null
    }
    if (mapped != null) return mapped

    val unicode = event.unicodeChar
    if (unicode == 0) return null
    // A dead key (e.g. ^ then e) reports a combining accent flag instead of a code point. Passing
    // the raw value to Character.toChars throws, which would crash the page on a physical keyboard.
    if (unicode and KeyCharacterMap.COMBINING_ACCENT != 0) {
        val base = unicode and KeyCharacterMap.COMBINING_ACCENT_MASK
        return String(Character.toChars(base))
    }
    val text = String(Character.toChars(unicode))
    return if (event.isAltPressed) "\u001b$text" else text
}

/** Android IMEs insert LF for their return key; a PTY Enter is carriage return. */
internal fun terminalInputPayload(text: String): String =
    text.replace("\r\n", "\r").replace('\n', '\r')
