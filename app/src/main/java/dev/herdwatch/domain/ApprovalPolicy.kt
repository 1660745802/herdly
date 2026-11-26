package dev.herdwatch.domain

import dev.herdwatch.R
import dev.herdwatch.i18n.Str
import dev.herdwatch.model.Ask
import dev.herdwatch.model.AskConfidence
import dev.herdwatch.model.AskOption
import dev.herdwatch.model.PaneText

/** Full evidence, not just the extracted (and possibly shortened) question. */
data class ApprovalEvidence(val pane: PaneText, val ask: Ask?)

object ApprovalPolicy {
    fun evidence(pane: PaneText): ApprovalEvidence = ApprovalEvidence(
        pane, AskParser.parse(pane.text.lineSequence().toList().takeLast(90).joinToString("\n")),
    )

    /**
     * The question, its options and the command lines above them, byte for byte. Spinners, clocks
     * and token counters live elsewhere on the screen and must not invalidate an approval.
     */
    private fun sameRegion(a: ApprovalEvidence, b: ApprovalEvidence): Boolean {
        val left = a.ask?.region.orEmpty()
        return left.isNotBlank() && left == b.ask?.region.orEmpty()
    }

    private fun revisionLostOrUnexplained(a: ApprovalEvidence, b: ApprovalEvidence): Boolean {
        val had = (a.pane.revision ?: 0) > 0
        val now = (b.pane.revision ?: 0) > 0
        if (had && !now) return true
        if (!had && now) return true
        if (!had) return false
        return a.pane.revision != b.pane.revision && !sameRegion(a, b)
    }

    private fun sameWholeScreen(a: ApprovalEvidence, b: ApprovalEvidence): Boolean =
        a.pane.text.replace("\r\n", "\n").trimEnd() == b.pane.text.replace("\r\n", "\n").trimEnd()

    fun sameContext(a: ApprovalEvidence, b: ApprovalEvidence): Boolean =
        a.ask == b.ask && a.pane.truncated == b.pane.truncated &&
            // With a located ask region, that region is the evidence. Without one (older parser
            // output, hand-built evidence) fall back to the whole screen rather than guess.
            (sameRegion(a, b) || sameWholeScreen(a, b))

    fun unavailable(evidence: ApprovalEvidence): String? = when {
        evidence.pane.truncated -> Str.get(R.string.policy_unavailable_truncated)
        evidence.ask == null || evidence.ask.confidence == AskConfidence.NONE -> Str.get(R.string.policy_unavailable_unrecognized)
        else -> null
    }

    /** Identity matters: a button retained from an older render must not approve a new ask. */
    fun validate(frozen: ApprovalEvidence, option: AskOption, fresh: ApprovalEvidence): String? = when {
        frozen.ask == null ||
            (frozen.ask.options.none { it === option } && frozen.ask.allow !== option && frozen.ask.deny !== option) ||
            frozen.ask.options.none { it == option } -> Str.get(R.string.policy_option_expired)
        option.keys.isEmpty() -> Str.get(R.string.policy_option_no_keys)
        unavailable(frozen) != null -> unavailable(frozen)
        unavailable(fresh) != null -> unavailable(fresh)
        // A valid revision becoming absent is also a loss of evidence, not equality.
        // Losing a revision is a loss of evidence, not equality. A revision that merely moved on
        // while the question, its options and its command stayed byte-identical is a redraw.
        revisionLostOrUnexplained(frozen, fresh) -> Str.get(R.string.policy_revision_changed)
        !sameContext(frozen, fresh) -> Str.get(R.string.policy_context_changed)
        else -> null
    }
}
