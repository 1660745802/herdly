package dev.herdwatch.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Which finished turns the person has actually looked at.
 *
 * The bridge reports `done` when an agent stops working, but "done" is not the same question as
 * "you have not seen this yet". Keeping them separate is what makes a queue trustworthy: an agent
 * that finished while the screen was elsewhere stays at the top, and one whose result has been on
 * screen drops back to idle instead of waiting to be dismissed by hand.
 *
 * This is the official Herdr client's rule, kept locally because our bridge protocol (22) has no
 * shared `herdr.seen` label. It is per process: a restart re-reads every finished agent as unseen,
 * which errs toward showing work rather than hiding it.
 */
object SeenMarks {
    private val _marks = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** agent key → highest completion sequence that has been displayed. */
    val marks: StateFlow<Map<String, Long>> = _marks.asStateFlow()

    /**
     * Records that the finished turn of [agentKey] at [completionSeq] has been seen.
     *
     * Only raises: re-reading an older snapshot must not make a newer result unseen again.
     */
    fun mark(agentKey: String, completionSeq: Long) {
        if (agentKey.isEmpty() || completionSeq <= 0L) return
        if ((_marks.value[agentKey] ?: 0L) >= completionSeq) return
        _marks.update { it + (agentKey to completionSeq) }
    }

    /** Tests get a clean slate; nothing in the app calls this. */
    fun clear() {
        _marks.value = emptyMap()
    }
}
