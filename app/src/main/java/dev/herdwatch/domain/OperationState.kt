package dev.herdwatch.domain

import dev.herdwatch.R
import dev.herdwatch.i18n.Str

/** Page tickets protect A -> B -> A, where agent identity alone is insufficient. */
data class PageTicket(val agentKey: String, val generation: Long)

class OperationState {
    data class Record(
        val id: Long,
        val inFlight: Boolean = true,
        val notice: String? = null,
        val uncertain: Boolean = false,
        val blockedApproval: ApprovalEvidence? = null,
        val verificationRequired: Boolean = false,
    )

    data class Draft(val text: String = "", val version: Long = 0)

    private var sequence = 0L
    private val operations = mutableMapOf<String, Record>()
    private val drafts = mutableMapOf<String, Draft>()

    fun record(key: String): Record? = operations[key]
    fun draft(key: String): Draft = drafts[key] ?: Draft()
    fun setDraft(key: String, text: String) {
        val old = draft(key)
        if (old.text != text) drafts[key] = Draft(text, old.version + 1)
    }

    fun clearDraft(key: String, captured: Draft) {
        if (draft(key) == captured) setDraft(key, "")
    }

    fun begin(key: String): Record? {
        val old = operations[key]
        if (old?.inFlight == true || old?.verificationRequired == true) return null
        return Record(
            id = ++sequence, blockedApproval = old?.blockedApproval,
        ).also { operations[key] = it }
    }

    fun finish(key: String, id: Long, notice: String, uncertain: Boolean, blocked: ApprovalEvidence? = null) {
        val old = operations[key] ?: return
        if (old.id != id) return
        operations[key] = old.copy(
            inFlight = false, notice = notice, uncertain = uncertain,
            blockedApproval = blocked ?: old.blockedApproval,
            verificationRequired = uncertain,
        )
    }

    fun blocksApproval(key: String, evidence: ApprovalEvidence): Boolean =
        operations[key]?.blockedApproval?.let { ApprovalPolicy.sameContext(it, evidence) } == true

    fun observe(key: String, evidence: ApprovalEvidence) {
        val old = operations[key] ?: return
        val blocked = old.blockedApproval ?: return
        if (!old.inFlight && ApprovalPolicy.unavailable(evidence) == null &&
            !ApprovalPolicy.sameContext(blocked, evidence)
        ) {
            operations[key] = old.copy(blockedApproval = null)
        }
    }

    /** New evidence releases assisted bindings; unknown writes still need a human check. */
    fun verified(key: String) {
        val old = operations[key] ?: return
        if (!old.inFlight) operations[key] = old.copy(
            uncertain = false, verificationRequired = false, blockedApproval = null,
            notice = Str.get(R.string.policy_verified_notice),
        )
    }
}
