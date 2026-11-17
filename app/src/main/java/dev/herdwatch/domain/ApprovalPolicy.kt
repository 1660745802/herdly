package dev.herdwatch.domain

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
        evidence.pane.truncated -> "屏幕内容不完整，请展开核对；辅助审批已停用，可核对原文后手动操作。"
        evidence.ask == null || evidence.ask.confidence == AskConfidence.NONE -> "尚未识别可核对的问题。"
        else -> null
    }

    /** Identity matters: a button retained from an older render must not approve a new ask. */
    fun validate(frozen: ApprovalEvidence, option: AskOption, fresh: ApprovalEvidence): String? = when {
        frozen.ask == null ||
            (frozen.ask.options.none { it === option } && frozen.ask.allow !== option && frozen.ask.deny !== option) ||
            frozen.ask.options.none { it == option } -> "选项已过期，请重新核对当前问题。"
        option.keys.isEmpty() -> "选项没有有效按键，没有发送。"
        unavailable(frozen) != null -> unavailable(frozen)
        unavailable(fresh) != null -> unavailable(fresh)
        // A valid revision becoming absent is also a loss of evidence, not equality.
        ((frozen.pane.revision ?: 0) > 0 || (fresh.pane.revision ?: 0) > 0) &&
            frozen.pane.revision != fresh.pane.revision -> "屏幕版本已变化，没有发送。请核对新内容。"
        !sameContext(frozen, fresh) -> "问题或上下文已变化，没有发送。请核对新内容。"
        else -> null
    }
}
