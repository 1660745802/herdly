package dev.herdwatch.ui.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the icon family's craft rules mechanically. The geometry model in
 * `NeoIcons.kt` is pure Kotlin, so this runs on the JVM without Robolectric.
 */
class NeoGlyphTest {

    private val liveInset = 2f

    @Test
    fun everyGlyphDrawsSomething() {
        NeoGlyph.entries.forEach { glyph ->
            val shapes = NeoGlyphGeometry.shapes(glyph)
            assertTrue("$glyph has no shapes", shapes.isNotEmpty())
            shapes.forEachIndexed { index, shape ->
                assertTrue("$glyph shape $index is empty", shape.ops.isNotEmpty())
                // A lone primitive (a disc, a rounded box, an arc) is complete on its own; anything
                // built from segments needs at least a move plus one drawing op.
                val lonePrimitive = shape.ops.size == 1 && shape.ops[0].isPrimitive()
                assertTrue(
                    "$glyph shape $index is a bare segment",
                    lonePrimitive || shape.ops.size >= 2,
                )
                shape.ops.forEach { op ->
                    assertTrue("$glyph has a non-finite op", op.isFinite())
                }
            }
        }
    }

    @Test
    fun everyGlyphStaysInsideTheLiveArea() {
        NeoGlyph.entries.forEach { glyph ->
            NeoGlyphGeometry.shapes(glyph).forEach { shape ->
                val box = NeoGlyphGeometry.boundsOf(shape)
                assertNotNull("$glyph has unbounded geometry", box)
                box!!
                val half = shape.weight / 2f
                assertTrue(
                    "$glyph overflows left: ${box.left}",
                    box.left - half >= liveInset - 0.1f,
                )
                assertTrue(
                    "$glyph overflows top: ${box.top}",
                    box.top - half >= liveInset - 0.1f,
                )
                assertTrue(
                    "$glyph overflows right: ${box.right}",
                    box.right + half <= 24f - liveInset + 0.1f,
                )
                assertTrue(
                    "$glyph overflows bottom: ${box.bottom}",
                    box.bottom + half <= 24f - liveInset + 0.1f,
                )
            }
        }
    }

    @Test
    fun everyGlyphIsOpticallyCentred() {
        NeoGlyph.entries.forEach { glyph ->
            val boxes = NeoGlyphGeometry.shapes(glyph).mapNotNull { NeoGlyphGeometry.boundsOf(it) }
            val left = boxes.minOf { it.left }
            val top = boxes.minOf { it.top }
            val right = boxes.maxOf { it.right }
            val bottom = boxes.maxOf { it.bottom }
            val cx = (left + right) / 2f
            val cy = (top + bottom) / 2f
            // A chevron or a triangle is deliberately nudged by half a unit so that its *mass*,
            // not its box, sits on the centre; nothing may drift further than that.
            assertTrue("$glyph is $cx horizontally, expected ~12", kotlin.math.abs(cx - 12f) <= 1.2f)
            assertTrue("$glyph is $cy vertically, expected ~12", kotlin.math.abs(cy - 12f) <= 1.2f)
        }
    }

    @Test
    fun oneOpticalStrokeWeight() {
        NeoGlyph.entries.forEach { glyph ->
            NeoGlyphGeometry.shapes(glyph).forEach { shape ->
                assertEquals(
                    "$glyph is off the two permitted weights",
                    true,
                    shape.weight == NeoGlyphGeometry.BaseWeight ||
                        shape.weight == NeoGlyphGeometry.DetailWeight,
                )
            }
        }
    }

    @Test
    fun solidVariantsExistWherePromised() {
        val promised = listOf(
            NeoGlyph.Check,
            NeoGlyph.CheckCircle,
            NeoGlyph.Warning,
            NeoGlyph.AlertTriangle,
            NeoGlyph.Play,
            NeoGlyph.Stop,
            NeoGlyph.Sparkle,
            NeoGlyph.Bolt,
            NeoGlyph.Power,
        )
        promised.forEach { glyph ->
            assertTrue("$glyph should have a solid variant", NeoGlyphGeometry.supportsSolid(glyph))
            val solid = NeoGlyphGeometry.shapes(glyph, solid = true)
            assertTrue("$glyph solid variant is empty", solid.isNotEmpty())
            assertTrue("$glyph solid variant is not filled", solid.any { it.fillable })
            // A solid variant must be geometry, not a fatter stroke.
            assertTrue(
                "$glyph solid variant looks like a stroked glyph",
                solid != NeoGlyphGeometry.shapes(glyph, solid = false),
            )
        }
    }

    @Test
    fun derivedGlyphsShareTheirGeometry() {
        // Back is the left chevron, not a second drawing.
        assertEquals(
            NeoGlyphGeometry.shapes(NeoGlyph.ChevronLeft),
            NeoGlyphGeometry.shapes(NeoGlyph.Back),
        )
        // DotsVertical is More rotated: same dot radius, same spacing, other axis.
        val more = NeoGlyphGeometry.shapes(NeoGlyph.More)
        val dots = NeoGlyphGeometry.shapes(NeoGlyph.DotsVertical)
        assertEquals(more.size, dots.size)
        assertEquals(
            more.mapNotNull { NeoGlyphGeometry.boundsOf(it)?.let { b -> b.width } },
            dots.mapNotNull { NeoGlyphGeometry.boundsOf(it)?.let { b -> b.height } },
        )
        // Expand and Collapse must share the same ink bounds.
        val expand = NeoGlyphGeometry.shapes(NeoGlyph.Expand).first()
        val collapse = NeoGlyphGeometry.shapes(NeoGlyph.Collapse).first()
        val expandBox = NeoGlyphGeometry.boundsOf(expand)!!
        val collapseBox = NeoGlyphGeometry.boundsOf(collapse)!!
        assertEquals(expandBox.left, collapseBox.left, 0.01f)
        assertEquals(expandBox.top, collapseBox.top, 0.01f)
        assertEquals(expandBox.right, collapseBox.right, 0.01f)
        assertEquals(expandBox.bottom, collapseBox.bottom, 0.01f)
        // ArrowUpToLine is ArrowDownToLine mirrored, so the boxes are complementary.
        val down = NeoGlyphGeometry.shapes(NeoGlyph.ArrowDownToLine).mapNotNull {
            NeoGlyphGeometry.boundsOf(it)
        }
        val up = NeoGlyphGeometry.shapes(NeoGlyph.ArrowUpToLine).mapNotNull {
            NeoGlyphGeometry.boundsOf(it)
        }
        assertEquals(down.size, up.size)
        down.zip(up).forEach { (d, u) ->
            assertEquals(d.left, u.left, 0.01f)
            assertEquals(d.right, u.right, 0.01f)
            assertEquals(24f - d.top, u.bottom, 0.01f)
            assertEquals(24f - d.bottom, u.top, 0.01f)
        }
    }

    @Test
    fun allGlyphsAreEnumeratedOnce() {
        assertEquals(NeoGlyph.entries.size, NeoGlyph.entries.toSet().size)
    }

    private fun NeoOp.isPrimitive(): Boolean = when (this) {
        is NeoOp.Circle, is NeoOp.RoundRect, is NeoOp.Arc -> true
        else -> false
    }

    private fun NeoOp.isFinite(): Boolean = when (this) {
        is NeoOp.Move -> x.isFinite() && y.isFinite()
        is NeoOp.Line -> x.isFinite() && y.isFinite()
        is NeoOp.Quad -> cx.isFinite() && cy.isFinite() && x.isFinite() && y.isFinite()
        is NeoOp.Cubic -> c1x.isFinite() && c1y.isFinite() && c2x.isFinite() &&
            c2y.isFinite() && x.isFinite() && y.isFinite()
        is NeoOp.Circle -> cx.isFinite() && cy.isFinite() && r.isFinite() && r > 0f
        is NeoOp.RoundRect -> x.isFinite() && y.isFinite() && w.isFinite() &&
            h.isFinite() && r.isFinite() && w > 0f && h > 0f
        is NeoOp.Arc -> cx.isFinite() && cy.isFinite() && rx.isFinite() && ry.isFinite()
        NeoOp.Close -> true
    }
}
