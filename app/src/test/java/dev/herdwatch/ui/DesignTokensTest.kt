package dev.herdwatch.ui

import androidx.compose.ui.graphics.Color
import dev.herdwatch.model.AgentStatus
import dev.herdwatch.model.ConnState
import dev.herdwatch.ui.design.DsSpacing
import dev.herdwatch.ui.design.DsTypography
import dev.herdwatch.ui.theme.AppTypography
import dev.herdwatch.ui.theme.StatusPalette
import dev.herdwatch.ui.theme.statusColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the two token rules the layout work depends on:
 *
 * 1. an agent state always has the same color, wherever it is drawn;
 * 2. the compact density really is tighter than the comfortable one.
 */
class DesignTokensTest {

    private val palette = StatusPalette(
        waiting = Color(0xFFFB923C),
        working = Color(0xFFFACC15),
        review = Color(0xFF4ADE80),
        idle = Color(0xFFACB2C0),
        unknown = Color(0xFFACB2C0),
        waitingWash = Color(0xFF2B1D15),
        danger = Color(0xFFF87171),
    )

    @Test
    fun `every agent state has one color`() {
        assertEquals(palette.waiting, statusColor(AgentStatus.WAITING, palette))
        assertEquals(palette.working, statusColor(AgentStatus.WORKING, palette))
        assertEquals(palette.review, statusColor(AgentStatus.REVIEW, palette))
        assertEquals(palette.idle, statusColor(AgentStatus.IDLE, palette))
        assertEquals(palette.unknown, statusColor(AgentStatus.UNKNOWN, palette))
    }

    @Test
    fun `danger red is reserved for failures`() {
        // Waiting used to be drawn in the danger colour on the queue row and in amber in the
        // machine tree; one mapping now keeps both screens honest.
        assertTrue(statusColor(AgentStatus.WAITING, palette) != palette.danger)
    }

    @Test
    fun `connection states are distinguishable`() {
        assertEquals(ConnState.Connecting, ConnState.Connecting)
        assertTrue(ConnState.Connected !is ConnState.Failed)
        assertTrue(ConnState.Failed("boom") !is ConnState.Connected)
    }

    @Test
    fun `compact density is tighter than comfortable`() {
        assertTrue(DsSpacing.compact.gutter < DsSpacing.comfortable.gutter)
        assertTrue(DsSpacing.compact.row < DsSpacing.comfortable.row)
        assertTrue(DsSpacing.compact.block < DsSpacing.comfortable.block)
    }

    @Test
    fun `material typography follows the app type scale`() {
        // Material controls (fields inside sheets, menu items) read MaterialTheme.typography.
        // If that set is written twice it drifts from the app scale and the two sit next to
        // each other at different sizes.
        assertEquals(DsTypography.title, AppTypography.titleLarge)
        assertEquals(DsTypography.headline, AppTypography.titleMedium)
        assertEquals(DsTypography.body, AppTypography.bodyLarge)
        assertEquals(DsTypography.caption, AppTypography.labelMedium)
        assertEquals(DsTypography.micro, AppTypography.labelSmall)
        assertEquals(DsTypography.mono, dev.herdwatch.ui.theme.MonoStyle)
    }
}
