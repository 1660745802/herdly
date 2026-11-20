package dev.herdwatch.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContrastColorTest {
    @Test
    fun `readable ink is left exactly as the program asked`() {
        val white = Color(0xFFFFFFFF)
        assertEquals(white, ensureContrast(white, Color(0xFF13151A)))
    }

    @Test
    fun `black ink on the dark terminal surface is lifted until it can be seen`() {
        val surface = Color(0xFF13151A)
        val ink = xtermColor(0, Color(0xFFE4E7EE))
        val fixed = ensureContrast(ink, surface)
        assertTrue(contrastRatio(fixed, surface) >= 3f)
        // Hue is kept: the result stays a neutral grey, not an arbitrary colour.
        val max = maxOf(fixed.red, fixed.green, fixed.blue)
        val min = minOf(fixed.red, fixed.green, fixed.blue)
        assertTrue(max - min < 0.05f)
    }

    @Test
    fun `dark ink on a light background is sunk, not lifted`() {
        val paper = Color(0xFFF7F7F4)
        val pale = Color(0xFFFFF8A0)
        val fixed = ensureContrast(pale, paper)
        assertTrue(contrastRatio(fixed, paper) >= 3f)
        assertTrue(relativeLuminance(fixed) <= relativeLuminance(pale))
    }

    @Test
    fun `transparent means inherit, so nothing is repainted`() {
        val transparent = Color(0x00000000)
        assertEquals(transparent, ensureContrast(transparent, Color(0xFF13151A)))
    }

    @Test
    fun `contrast maths matches the wcag reference pair`() {
        val ratio = contrastRatio(Color(0xFFFFFFFF), Color(0xFF000000))
        assertEquals(21f, ratio, 0.01f)
    }
}
