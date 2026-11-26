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
            // The wording of the question is identical; the command it is about is not. The ask
            // carries its own context lines, so the two are different evidence.
            assertEquals(frozen.ask!!.question, changed.ask!!.question)
            assertEquals(frozen.ask!!.options, changed.ask!!.options)
            assertNotEquals(frozen.ask!!.region, changed.ask!!.region)
            assertNotNull(ApprovalPolicy.validate(frozen, frozen.ask!!.options.first(), changed))
        }
    }

    @Test fun `a repaint outside the question does not refuse the approval`() {
        // Clocks, spinners and token counters live above the command. Refusing on them made the
        // common case fail on every animated TUI.
        val header = "claude code\n\n~/repo\n\n earlier output line\n\n another line\n"
        val frozen = ApprovalPolicy.evidence(PaneText(header + evidence().pane.text, 4, false))
        val repaint = ApprovalPolicy.evidence(
            PaneText(header.replace("claude code", "claude code   tokens 12345  12:00:01") + evidence().pane.text, 9, false),
        )
        assertEquals(frozen.ask, repaint.ask)
        assertNull(ApprovalPolicy.validate(frozen, frozen.ask!!.options.first(), repaint))
    }

    @Test fun `full matching evidence works without a revision`() {
        val frozen = evidence()
        assertNull(ApprovalPolicy.validate(frozen, frozen.ask!!.options.first(), evidence()))
    }

    @Test fun `losing a valid revision aborts, a repaint that keeps the question does not`() {
        val frozen = evidence(revision = 7)
        for (revision in listOf(0L, null)) {
            assertNotNull(ApprovalPolicy.validate(frozen, frozen.ask!!.options.first(), evidence(revision = revision)))
        }
        // Same question, same options, same command: a new revision alone is just a redraw.
        assertNull(ApprovalPolicy.validate(frozen, frozen.ask!!.options.first(), evidence(revision = 8)))
        // A different command under a new revision is still a refusal.
        assertNotNull(
            ApprovalPolicy.validate(frozen, frozen.ask!!.options.first(), evidence("rm /other", revision = 8)),
        )
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
