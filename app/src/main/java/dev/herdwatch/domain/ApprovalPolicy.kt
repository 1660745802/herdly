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

    fun sameContext(a: ApprovalEvidence, b: ApprovalEvidence): Boolean =
        a.ask == b.ask && a.pane.truncated == b.pane.truncated &&
            a.pane.text.replace("\r\n", "\n").trimEnd() == b.pane.text.replace("\r\n", "\n").trimEnd()

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
        ((frozen.pane.revision ?: 0) > 0 || (fresh.pane.revision ?: 0) > 0) &&
            frozen.pane.revision != fresh.pane.revision -> Str.get(R.string.policy_revision_changed)
        !sameContext(frozen, fresh) -> Str.get(R.string.policy_context_changed)
        else -> null
    }
}
