package dev.herdwatch

import dev.herdwatch.domain.ApprovalPolicy
import dev.herdwatch.model.PaneText
import org.junit.Assert.*
import org.junit.Test

class ApprovalPolicyTest {
    private fun evidence(command: String = "cat /safe/file", revision: Long? = null, truncated: Boolean = false) =
        ApprovalPolicy.evidence(PaneText("$command\nDo you want to proceed?\n1. Yes\n2. No\n3. View details", revision, truncated))

    @Test fun `equal questions do not hide changed commands when revisions are missing or zero`() {
        for (revision in listOf(null, 0L)) {
            val frozen = evidence(revision = revision)
            val changed = evidence("rm /different/file", revision)
            assertEquals(frozen.ask, changed.ask)
            assertNotNull(ApprovalPolicy.validate(frozen, frozen.ask!!.options.first(), changed))
        }
    }

    @Test fun `full matching evidence works without a revision`() {
        val frozen = evidence()
        assertNull(ApprovalPolicy.validate(frozen, frozen.ask!!.options.first(), evidence()))
    }

    @Test fun `revision changes and loss of valid revision abort`() {
        val frozen = evidence(revision = 7)
        for (revision in listOf(8L, 0L, null)) {
            assertNotNull(ApprovalPolicy.validate(frozen, frozen.ask!!.options.first(), evidence(revision = revision)))
        }
    }

    @Test fun `truncation on either read blocks assisted approval`() {
        val frozen = evidence()
        assertNotNull(ApprovalPolicy.validate(frozen, frozen.ask!!.options.first(), evidence(truncated = true)))
        assertNotNull(ApprovalPolicy.unavailable(evidence(truncated = true)))
    }

    @Test fun `old equal options and fabricated options are rejected`() {
        val old = evidence()
        val current = evidence()
        assertNotNull(ApprovalPolicy.validate(current, old.ask!!.options.first(), evidence()))
        assertNotNull(ApprovalPolicy.validate(current, current.ask!!.options.first().copy(keys = listOf("9")), evidence()))
        assertNull(ApprovalPolicy.validate(current, current.ask.options.last(), evidence()))
    }

    @Test fun `changed menu options abort even with same question`() {
        val frozen = evidence()
        val fresh = ApprovalPolicy.evidence(PaneText(frozen.pane.text.replace("2. No", "2. Cancel forever"), null, false))
        assertNotNull(ApprovalPolicy.validate(frozen, frozen.ask!!.options.first(), fresh))
    }
}
