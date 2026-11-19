package dev.herdwatch.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * The hand-drawn glyph set of the "matte instrument" language.
 *
 * ## One grid, one weight
 *
 * Every glyph lives on a 24×24 grid with a 2dp safe margin, i.e. all ink stays inside the 20×20
 * live area. The family shares:
 *
 *  - one optical stroke weight, [NeoGlyphGeometry.BaseWeight] (1.8dp), with thin details at
 *    [NeoGlyphGeometry.DetailWeight] (1.5dp) — never an arbitrary third weight;
 *  - round caps and round joins, and explicit 1.5–2dp corner rounding on every closed shape
 *    (a round *join* alone only rounds a corner by half the stroke, which is what made the old
 *    set look improvised);
 *  - 45° diagonals only. Chevrons, arrowheads, slashes, the pencil, the check, the paper plane
 *    and the search handle all sit on the same diagonal;
 *  - shared terminals: every framed glyph uses the same frame box (left 3.5, right 20.5, top 4.0,
 *    bottom 20.0, radius 2.2), every prompt caret ends on the same baseline as its cursor line,
 *    every chevron uses the same half-span (5 units, arrowheads 2.4–2.6).
 *
 * Glyphs that are relatives are *built* from the same path and then rotated or mirrored, so their
 * weights cannot drift: Back = ChevronLeft, ChevronUp/Down/Left/Right = one chevron primitive,
 * Expand/Collapse = one 45° arrow pair, EyeOff = Eye + slash, ArrowUpToLine = ArrowDownToLine
 * mirrored, DotsVertical = More rotated, Tune = Sliders rotated, Hourglass = one half glass
 * mirrored, User/Agent = one person primitive + the Sparkle primitive,
 * AlertTriangle = the Warning triangle without the mark.
 *
 * ## Metaphor set (must stay legible at 16dp)
 *
 *  - Workspace — a 2×2 field of cells: the container that holds tabs.
 *  - Tab         — a frame with a raised tab on its top edge: the thing you switch between.
 *  - Pane        — a frame with one vertical split: one region of a tab.
 *  - Agent       — the person primitive plus a sparkle badge: a worker that thinks.
 *  - User        — the person primitive alone: the human.
 *  - Terminal    — a framed window with a prompt caret `›_`: a terminal session.
 *  - Shell       — the bare prompt caret, no frame: "open a shell" / command entry.
 *  - Command     — four loops on a square (⌘): a tool call, deliberately not a prompt.
 *  - Bolt        — a lightning bolt: the instruction palette (something you fire off).
 *  - Sparkle     — a four-point star: model activity / thinking.
 *
 * Icons are static: no animation, no reduced-motion dependency. See [NeoGlyphGeometry] for the
 * geometry, which is pure Kotlin so it can be verified in a JVM unit test.
 */
enum class NeoGlyph {
    Back,
    ChevronDown,
    ChevronRight,
    Terminal,
    Server,
    Tray,
    Sliders,
    More,
    Plus,
    Refresh,
    Copy,
    Check,
    Close,
    Search,
    Warning,
    Play,
    Stop,
    Key,
    Keyboard,
    Clock,
    Send,
    Edit,

    // Added with the workspace / agent / instruction UI.
    Pane,
    Workspace,
    Tab,
    Sparkle,
    Command,
    Bolt,
    ChevronUp,
    ChevronLeft,
    Expand,
    Collapse,
    Filter,
    Fingerprint,
    Link,
    Power,
    Info,
    DotsVertical,
    ArrowDownToLine,
    ArrowUpToLine,
    CheckCircle,
    AlertTriangle,
    Hourglass,
    User,
    Agent,
    Shell,
    Eye,
    EyeOff,
    Tune,
}

@Composable
fun NeoIcon(
    glyph: NeoGlyph,
    modifier: Modifier = Modifier.size(20.dp),
    contentDescription: String? = null,
    tint: Color = ds().text,
    strokeWidth: Dp = 1.8.dp,
    filled: Boolean = false,
) {
    val a11y = if (contentDescription == null) {
        Modifier
    } else {
        Modifier.semantics {
            this.contentDescription = contentDescription
            role = Role.Image
        }
    }
    Canvas(modifier.then(a11y)) {
        val strokePx = strokeWidth.toPx()
        NeoGlyphGeometry.shapes(glyph, solid = filled).forEach { shape ->
            val path = shape.toPath(size.minDimension / 24f)
            path.fillType = if (shape.evenOdd) PathFillType.EvenOdd else PathFillType.NonZero
            if (shape.alwaysSolid || (filled && shape.fillable)) {
                drawPath(path, tint)
            } else {
                drawPath(
                    path,
                    tint,
                    style = Stroke(
                        width = strokePx * (shape.weight / NeoGlyphGeometry.BaseWeight),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }
        }
    }
}

/**
 * The geometry language of the family. Everything here is expressed in 24-grid units and is free
 * of Compose and Android types on purpose, so `NeoGlyphTest` can check the whole set on the JVM:
 * shapes are non-empty, ink stays inside the 20×20 live area, and each glyph is centred.
 */
internal object NeoGlyphGeometry {

    /** The one optical stroke weight of the family, in dp at 24 units. */
    const val BaseWeight = 1.8f

    /** Thin details (dots inside frames, inner fingerprint loops) — the only permitted deviation. */
    const val DetailWeight = 1.5f

    // Shared grid anchors. Frames, prompts and counters across unrelated glyphs snap to these.
    private const val G = 12f          // optical centre of the grid
    private const val L = 3.5f         // frame left
    private const val R = 20.5f        // frame right
    private const val T = 4f           // frame top
    private const val B = 20f          // frame bottom
    private const val FR = 2.2f        // frame corner radius
    private const val CHEVRON = 5f     // chevron half span
    private const val ARROW = 2.4f     // arrowhead half span

    /** Glyphs whose `filled` flag is a real solid shape rather than a thicker stroke. */
    private val SolidGlyphs = setOf(
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

    fun supportsSolid(glyph: NeoGlyph): Boolean = glyph in SolidGlyphs

    fun shapes(glyph: NeoGlyph, solid: Boolean = false): List<NeoShape> = when (glyph) {

        // ---- navigation ---------------------------------------------------------------
        NeoGlyph.ChevronDown -> listOf(shape(chevron(G, 14f, CHEVRON, 90f)))
        NeoGlyph.ChevronUp -> listOf(shape(chevron(G, 10f, CHEVRON, 270f)))
        NeoGlyph.ChevronRight -> listOf(shape(chevron(14f, G, CHEVRON, 0f)))
        // Back is a chevron, not a second drawing: same path, same weight, same arms.
        NeoGlyph.Back, NeoGlyph.ChevronLeft -> listOf(shape(chevron(10f, G, CHEVRON, 180f)))

        // ---- terminals ----------------------------------------------------------------
        NeoGlyph.Terminal -> listOf(
            frame(),
            shape(listOf(m(L, 8.2f), l(R, 8.2f))),
            // Prompt caret, 45° arms, its lower terminal is the cursor baseline.
            shape(chevron(10.6f, 13.4f, 2.6f, 0f), DetailWeight),
            shape(listOf(m(12.2f, 16f), l(16.2f, 16f)), DetailWeight),
        )
        // Shell is the prompt alone — the caret that Terminal draws inside its frame.
        NeoGlyph.Shell -> listOf(
            shape(chevron(11.2f, 12.4f, 4.6f, 0f)),
            shape(listOf(m(13f, 17f), l(18.6f, 17f))),
        )

        // ---- infrastructure -----------------------------------------------------------
        NeoGlyph.Server -> listOf(
            shape(listOf(box(L, T, 17f, 6.6f, FR))),
            shape(listOf(box(L, 13.4f, 17f, 6.6f, FR))),
            solidDot(7.2f, 7.3f, 0.95f),
            solidDot(7.2f, 16.7f, 0.95f),
        )
        NeoGlyph.Tray -> listOf(
            shape(
                listOf(
                    m(L, 13.8f),
                    l(L, 17.6f), q(L, B, 6.1f, B),
                    l(17.9f, B), q(R, B, R, 17.6f),
                    l(R, 13.8f),
                ),
            ),
            shape(listOf(m(G, 4.2f), l(G, 12.4f))),
            shape(chevron(G, 12.4f, ARROW, 90f)),
        )
        NeoGlyph.Pane -> listOf(
            frame(),
            shape(listOf(m(10.6f, T), l(10.6f, B))),
        )
        NeoGlyph.Workspace -> listOf(
            shape(listOf(box(L, L, 7.4f, 7.4f, 2f))),
            shape(listOf(box(13.1f, L, 7.4f, 7.4f, 2f))),
            shape(listOf(box(L, 13.1f, 7.4f, 7.4f, 2f))),
            shape(listOf(box(13.1f, 13.1f, 7.4f, 7.4f, 2f))),
        )
        NeoGlyph.Tab -> listOf(
            shape(listOf(box(3.6f, 7.2f, 16.8f, 12.4f, FR))),
            shape(
                listOf(
                    m(7.4f, 7.2f), l(7.4f, 5.8f), q(7.4f, 4.4f, 8.8f, 4.4f),
                    l(13f, 4.4f), q(14.4f, 4.4f, 14.4f, 5.8f), l(14.4f, 7.2f),
                ),
            ),
        )
        NeoGlyph.Clock -> listOf(
            shape(listOf(circle(G, G, 8f))),
            shape(listOf(m(G, 7.4f), l(G, 12.2f), l(15.4f, 14.2f))),
        )

        // ---- actions ------------------------------------------------------------------
        NeoGlyph.Plus -> listOf(
            shape(listOf(m(G, 5f), l(G, 19f))),
            shape(listOf(m(5f, G), l(19f, G))),
        )
        NeoGlyph.Close -> listOf(
            shape(listOf(m(6.2f, 6.2f), l(17.8f, 17.8f))),
            shape(listOf(m(17.8f, 6.2f), l(6.2f, 17.8f))),
        )
        NeoGlyph.Refresh -> listOf(
            shape(listOf(arc(G, G, 7.6f, 30f, 300f))),
            // Head sits on the arc end and follows its tangent, so the arrow reads as motion.
            shape(chevron(18.58f, 8.2f, ARROW, 60f)),
        )
        NeoGlyph.Copy -> listOf(
            shape(listOf(box(8.2f, L, 12.3f, 12.3f, FR))),
            shape(
                listOf(
                    m(15.8f, 20.5f), l(7.6f, 20.5f), q(4.5f, 20.5f, 4.5f, 17.4f),
                    l(4.5f, 7.4f),
                ),
            ),
        )
        NeoGlyph.Check -> if (solid) {
            listOf(solidShape(ribbon(listOf(5f to 11.5f, 10f to 16.5f, 19f to 7.5f), 0.95f)))
        } else {
            listOf(shape(listOf(m(5f, 11.5f), l(10f, 16.5f), l(19f, 7.5f))))
        }
        NeoGlyph.CheckCircle -> {
            val mark = listOf(8.2f to 12.2f, 11f to 15f, 16f to 10f)
            if (solid) {
                // Solid disc with the check knocked out of it (EvenOdd), so the check needs no ink.
                listOf(
                    solidShape(
                        listOf(circle(G, G, 8.2f)) + ribbon(mark, 0.95f),
                        evenOdd = true,
                    ),
                )
            } else {
                listOf(shape(listOf(circle(G, G, 8.2f))), shape(mark.ops()))
            }
        }
        NeoGlyph.Search -> listOf(
            shape(listOf(circle(10.6f, 10.6f, 5.6f))),
            shape(listOf(m(14.5f, 14.5f), l(19f, 19f))),
        )
        NeoGlyph.Edit -> listOf(
            shape(
                roundedPolygon(
                    listOf(5f to 19f, 5.6f to 15.2f, 15.4f to 5.4f, 18.6f to 8.6f, 8.8f to 18.4f),
                    1.2f,
                ),
            ),
            shape(listOf(m(13.6f, 7.2f), l(16.8f, 10.4f))),
        )
        NeoGlyph.Send -> listOf(
            shape(
                roundedPolygon(
                    listOf(20.2f to 3.8f, 13f to 20.2f, 10.2f to 13f, 3.8f to 10.2f),
                    1.6f,
                ),
            ),
            shape(listOf(m(20.2f, 3.8f), l(10.2f, 13f))),
        )
        NeoGlyph.Filter -> listOf(
            shape(
                roundedPolygon(
                    listOf(
                        4f to 4.8f, 20f to 4.8f, 14.2f to 11.9f,
                        14.2f to 19.2f, 9.8f to 16.4f, 9.8f to 11.9f,
                    ),
                    1.8f,
                ),
            ),
        )
        NeoGlyph.Expand -> cornerArrows(outward = true)
        NeoGlyph.Collapse -> cornerArrows(outward = false)

        NeoGlyph.ArrowDownToLine -> listOf(
            shape(listOf(m(G, 4.2f), l(G, 14.2f))),
            shape(chevron(G, 14.2f, ARROW, 90f)),
            shape(listOf(m(5.6f, 19.4f), l(18.4f, 19.4f))),
        )
        // Same ink, mirrored vertically — never a redrawn arrow.
        NeoGlyph.ArrowUpToLine -> listOf(
            shape(mirroredY(listOf(m(G, 4.2f), l(G, 14.2f)))),
            shape(mirroredY(chevron(G, 14.2f, ARROW, 90f))),
            shape(mirroredY(listOf(m(5.6f, 19.4f), l(18.4f, 19.4f)))),
        )

        // ---- state --------------------------------------------------------------------
        NeoGlyph.Warning -> {
            val tri = triangle()
            val bar = box(11.3f, 9.2f, 1.4f, 4.6f, 0.7f)
            if (solid) {
                listOf(
                    solidShape(
                        tri + listOf(bar, circle(G, 16.4f, 0.95f)),
                        evenOdd = true,
                    ),
                )
            } else {
                listOf(
                    shape(tri),
                    shape(listOf(m(G, 9.8f), l(G, 13.6f)), DetailWeight),
                    solidDot(G, 16.4f, 0.95f),
                )
            }
        }
        // The bare warning silhouette: the triangle without the mark, solid-capable.
        NeoGlyph.AlertTriangle -> if (solid) {
            listOf(solidShape(triangle()))
        } else {
            listOf(shape(triangle()))
        }
        NeoGlyph.Info -> listOf(
            shape(listOf(circle(G, G, 8f))),
            solidDot(G, 8f, 0.95f),
            shape(listOf(m(G, 11.2f), l(G, 16.2f))),
        )
        NeoGlyph.Hourglass -> {
            val half = listOf(
                m(6.6f, 3.8f),
                c(6.6f, 7.4f, 9.8f, 9.6f, G, G),
                c(9.8f, 14.4f, 6.6f, 16.6f, 6.6f, 20.2f),
            )
            listOf(
                shape(listOf(m(4.6f, 3.8f), l(19.4f, 3.8f))),
                shape(listOf(m(4.6f, 20.2f), l(19.4f, 20.2f))),
                shape(half),
                shape(mirroredX(half)),
            )
        }
        NeoGlyph.Power -> if (solid) {
            listOf(
                // Annular sector: the ring is solid, the 90° gap at the top is the meaning.
                solidShape(
                    listOf(
                        arc(G, 12.6f, 7.4f, 135f, 270f),
                        arc(G, 12.6f, 5f, 45f, -270f, moveTo = false),
                        NeoOp.Close,
                    ),
                ),
                solidShape(ribbon(listOf(G to 3.6f, G to 10.6f), 1.1f)),
            )
        } else {
            listOf(
                shape(listOf(arc(G, 12.6f, 7.4f, 135f, 270f))),
                shape(listOf(m(G, 3.6f), l(G, 11f))),
            )
        }

        // ---- objects & people ---------------------------------------------------------
        NeoGlyph.Play -> {
            val tri = roundedPolygon(
                listOf(5.7f to 6.2f, 18.3f to G, 5.7f to 17.8f),
                2f,
            )
            if (solid) listOf(solidShape(tri)) else listOf(shape(tri))
        }
        NeoGlyph.Stop -> {
            val square = listOf(box(7f, 7f, 10f, 10f, 2.4f))
            if (solid) listOf(solidShape(square)) else listOf(shape(square))
        }
        NeoGlyph.Key -> listOf(
            shape(listOf(circle(8.4f, G, 3.4f))),
            shape(listOf(m(11.8f, G), l(19.4f, G))),
            shape(listOf(m(16.4f, G), l(16.4f, 15f))),
        )
        NeoGlyph.Keyboard -> listOf(
            shape(listOf(box(L, 7.3f, 17f, 9.4f, 2.4f))),
            solidDot(7.4f, 10.6f, 0.75f, DetailWeight),
            solidDot(10.4f, 10.6f, 0.75f, DetailWeight),
            solidDot(13.4f, 10.6f, 0.75f, DetailWeight),
            solidDot(16.4f, 10.6f, 0.75f, DetailWeight),
            shape(listOf(m(9.6f, 13.8f), l(14.4f, 13.8f))),
        )
        NeoGlyph.Sliders -> listOf(
            shape(listOf(m(3.8f, 6.6f), l(R, 6.6f))),
            shape(listOf(m(3.8f, G), l(R, G))),
            shape(listOf(m(3.8f, 17.4f), l(R, 17.4f))),
            solidDot(9f, 6.6f, 2.3f),
            solidDot(15.4f, G, 2.3f),
            solidDot(8.4f, 17.4f, 2.3f),
        )
        // The same three rails on the other axis: a mixer, not a second sliders icon.
        NeoGlyph.Tune -> shapes(NeoGlyph.Sliders).map { it.copy(ops = rotated(it.ops, 90f)) }
        NeoGlyph.More -> listOf(
            solidDot(5.8f, G, 1.5f),
            solidDot(G, G, 1.5f),
            solidDot(18.2f, G, 1.5f),
        )
        NeoGlyph.DotsVertical -> shapes(NeoGlyph.More).map { it.copy(ops = rotated(it.ops, 90f)) }
        NeoGlyph.Command -> listOf(
            shape(
                listOf(
                    circle(7.6f, 7.6f, 2.4f), circle(16.4f, 7.6f, 2.4f),
                    circle(16.4f, 16.4f, 2.4f), circle(7.6f, 16.4f, 2.4f),
                ),
            ),
            shape(
                listOf(
                    m(7.6f, 7.6f), l(16.4f, 7.6f), l(16.4f, 16.4f),
                    l(7.6f, 16.4f), NeoOp.Close,
                ),
            ),
        )
        NeoGlyph.Bolt -> {
            val bolt = roundedPolygon(
                listOf(
                    14.6f to 3.4f, 6.4f to 13f, 11f to 13f,
                    9.4f to 20.6f, 17.6f to 11f, 13f to 11f,
                ),
                1.4f,
            )
            if (solid) listOf(solidShape(bolt)) else listOf(shape(bolt))
        }
        NeoGlyph.Sparkle -> {
            val star = sparkle(G, G, 8.6f)
            if (solid) listOf(solidShape(star)) else listOf(shape(star))
        }
        NeoGlyph.User -> listOf(
            shape(person(12f, 8.6f, 3f, shoulderTop = 13f, halfWidth = 6.8f, baseY = 19.2f)),
        )
        // Agent = the user primitive, smaller and offset, plus the sparkle primitive as a badge.
        NeoGlyph.Agent -> listOf(
            shape(person(9.8f, 10.6f, 2.7f, shoulderTop = 14.6f, halfWidth = 6f, baseY = 20f)),
            shape(sparkle(18.2f, 6f, 2.8f)),
        )
        NeoGlyph.Eye -> listOf(
            shape(lens()),
            solidDot(G, G, 2.4f),
        )
        // Eye with the same lens and pupil, plus the family's 45° slash.
        NeoGlyph.EyeOff -> listOf(
            shape(lens()),
            solidDot(G, G, 2.4f),
            shape(listOf(m(4.4f, 4.4f), l(19.6f, 19.6f))),
        )
        NeoGlyph.Link -> listOf(
            shape(listOf(circle(9.6f, 14.4f, 3.8f))),
            shape(listOf(circle(14.4f, 9.6f, 3.8f))),
        )
        NeoGlyph.Fingerprint -> listOf(
            shape(arch(halfWidth = 8f, top = 4.8f, bottom = 18.8f, corner = 3.2f)),
            shape(arch(halfWidth = 5.4f, top = 7.8f, bottom = 16.2f, corner = 2.6f), DetailWeight),
            shape(arch(halfWidth = 2.6f, top = 10.8f, bottom = 13.4f, corner = 1.8f), DetailWeight),
        )
    }

    // ------------------------------------------------------------------------------------
    // shorthands
    // ------------------------------------------------------------------------------------

    private fun shape(ops: List<NeoOp>, weight: Float = BaseWeight): NeoShape =
        NeoShape(ops, weight)

    /** Dots are small and solid by nature: they never become rings when `filled` is false. */
    private fun solidDot(x: Float, y: Float, r: Float, weight: Float = BaseWeight): NeoShape =
        NeoShape(listOf(NeoOp.Circle(x, y, r)), weight, fillable = true, alwaysSolid = true)

    private fun solidShape(ops: List<NeoOp>, evenOdd: Boolean = false): NeoShape =
        NeoShape(ops, BaseWeight, fillable = true, evenOdd = evenOdd)

    private fun m(x: Float, y: Float) = NeoOp.Move(x, y)
    private fun l(x: Float, y: Float) = NeoOp.Line(x, y)
    private fun q(cx: Float, cy: Float, x: Float, y: Float) = NeoOp.Quad(cx, cy, x, y)
    private fun c(c1x: Float, c1y: Float, c2x: Float, c2y: Float, x: Float, y: Float) =
        NeoOp.Cubic(c1x, c1y, c2x, c2y, x, y)

    private fun circle(cx: Float, cy: Float, r: Float) = NeoOp.Circle(cx, cy, r)
    private fun box(x: Float, y: Float, w: Float, h: Float, r: Float) =
        NeoOp.RoundRect(x, y, w, h, r)

    private fun arc(cx: Float, cy: Float, r: Float, start: Float, sweep: Float, moveTo: Boolean = true) =
        NeoOp.Arc(cx, cy, r, r, start, sweep, moveTo)

    private fun frame(): NeoShape = NeoShape(listOf(box(L, T, R - L, B - T, FR)))

    private fun List<Pair<Float, Float>>.ops(): List<NeoOp> =
        buildList {
            add(NeoOp.Move(this@ops[0].first, this@ops[0].second))
            // `size` must be qualified: inside buildList the receiver's size shadows the points list.
            for (i in 1 until this@ops.size) add(NeoOp.Line(this@ops[i].first, this@ops[i].second))
        }

    // ------------------------------------------------------------------------------------
    // primitives — derived glyphs are built from these, never redrawn
    // ------------------------------------------------------------------------------------

    /**
     * A chevron whose tip is at ([tipX], [tipY]) pointing along [dirDeg] (0 = right, 90 = down).
     * Arms are always at 45° with a span of [half]. Chevrons and every arrowhead in the set come
     * from this one path, which is why their weights match exactly.
     */
    private fun chevron(tipX: Float, tipY: Float, half: Float, dirDeg: Float): List<NeoOp> {
        val rad = (dirDeg - 90f) * PI.toFloat() / 180f
        val cosA = cos(rad)
        val sinA = sin(rad)
        fun map(x: Float, y: Float): Pair<Float, Float> =
            (tipX + x * cosA - y * sinA) to (tipY + x * sinA + y * cosA)
        val a = map(-half, -half)
        val b = map(0f, 0f)
        val d = map(half, -half)
        return listOf(m(a.first, a.second), l(b.first, b.second), l(d.first, d.second))
    }

    /** Closed polygon with quad-rounded corners; [radius] is measured along the edges. */
    private fun roundedPolygon(points: List<Pair<Float, Float>>, radius: Float): List<NeoOp> {
        val n = points.size
        val corners = (0 until n).map { i ->
            val prev = points[(i - 1 + n) % n]
            val cur = points[i]
            val next = points[(i + 1) % n]
            val d1 = normalize(prev.first - cur.first, prev.second - cur.second)
            val d2 = normalize(next.first - cur.first, next.second - cur.second)
            val len = min(
                hypot(prev.first - cur.first, prev.second - cur.second),
                hypot(next.first - cur.first, next.second - cur.second),
            )
            val r = min(radius, len / 2.2f)
            Triple(
                (cur.first + d1.first * r) to (cur.second + d1.second * r),
                cur,
                (cur.first + d2.first * r) to (cur.second + d2.second * r),
            )
        }
        val out = ArrayList<NeoOp>(n * 2 + 1)
        out += NeoOp.Move(corners[0].first.first, corners[0].first.second)
        for (i in 1 until n) {
            val (a, ctrl, b) = corners[i]
            out += NeoOp.Line(a.first, a.second)
            out += NeoOp.Quad(ctrl.first, ctrl.second, b.first, b.second)
        }
        val (a0, c0, b0) = corners[0]
        out += NeoOp.Line(a0.first, a0.second)
        out += NeoOp.Quad(c0.first, c0.second, b0.first, b0.second)
        out += NeoOp.Close
        return out
    }

    /** Polygon ribbon around a polyline with rounded joints and caps: the solid Check mark. */
    private fun ribbon(points: List<Pair<Float, Float>>, half: Float): List<NeoOp> {
        val n = points.size
        val normals = (0 until n - 1).map { i ->
            val dx = points[i + 1].first - points[i].first
            val dy = points[i + 1].second - points[i].second
            val len = hypot(dx, dy)
            (-dy / len * half) to (dx / len * half)
        }
        fun offset(i: Int, normal: Int, sign: Float): Pair<Float, Float> =
            (points[i].first + normals[normal].first * sign) to
                (points[i].second + normals[normal].second * sign)

        val out = ArrayList<NeoOp>(n * 4)
        fun quadToPoint(corner: Pair<Float, Float>, p: Pair<Float, Float>) {
            out += NeoOp.Quad(corner.first, corner.second, p.first, p.second)
        }
        fun lineToPoint(p: Pair<Float, Float>) {
            out += NeoOp.Line(p.first, p.second)
        }

        var p = offset(0, 0, 1f)
        out += NeoOp.Move(p.first, p.second)
        for (i in 1 until n - 1) {
            lineToPoint(offset(i, i - 1, 1f))
            quadToPoint(points[i], offset(i, i, 1f))
        }
        lineToPoint(offset(n - 1, normals.size - 1, 1f))
        quadToPoint(points.last(), offset(n - 1, normals.size - 1, -1f))
        for (i in n - 2 downTo 1) {
            lineToPoint(offset(i, i, -1f))
            quadToPoint(points[i], offset(i, i - 1, -1f))
        }
        lineToPoint(offset(0, 0, -1f))
        quadToPoint(points[0], offset(0, 0, 1f))
        out += NeoOp.Close
        return out
    }

    private fun normalize(dx: Float, dy: Float): Pair<Float, Float> {
        val len = hypot(dx, dy).takeIf { it > 0.0001f } ?: 1f
        return (dx / len) to (dy / len)
    }

    /** Head + shoulders, shared by User and Agent so the silhouette cannot drift. */
    private fun person(
        headCx: Float,
        headCy: Float,
        headR: Float,
        shoulderTop: Float,
        halfWidth: Float,
        baseY: Float,
    ): List<NeoOp> = listOf(
        circle(headCx, headCy, headR),
        m(headCx - halfWidth, baseY),
        c(
            headCx - halfWidth, shoulderTop + 1.8f,
            headCx - halfWidth * 0.62f, shoulderTop,
            headCx, shoulderTop,
        ),
        c(
            headCx + halfWidth * 0.62f, shoulderTop,
            headCx + halfWidth, shoulderTop + 1.8f,
            headCx + halfWidth, baseY,
        ),
    )

    /** Four-point star with concave sides — thinking, and the Agent badge. */
    private fun sparkle(cx: Float, cy: Float, r: Float): List<NeoOp> {
        val k = r * 0.3f
        return listOf(
            m(cx, cy - r),
            q(cx + k, cy - k, cx + r, cy),
            q(cx + k, cy + k, cx, cy + r),
            q(cx - k, cy + k, cx - r, cy),
            q(cx - k, cy - k, cx, cy - r),
            NeoOp.Close,
        )
    }

    private fun triangle(): List<NeoOp> =
        roundedPolygon(listOf(G to 4.4f, 20.4f to 19.8f, 3.6f to 19.8f), 2.4f)

    /** Almond eye lid: two symmetric cubics, so EyeOff is literally the same lid. */
    private fun lens(): List<NeoOp> = listOf(
        m(3f, G),
        c(5.8f, 8f, 8.6f, 6.4f, G, 6.4f),
        c(15.4f, 6.4f, 18.2f, 8f, 21f, G),
        c(18.2f, 16f, 15.4f, 17.6f, G, 17.6f),
        c(8.6f, 17.6f, 5.8f, 16f, 3f, G),
        NeoOp.Close,
    )

    /** One fingerprint loop: a U-turn with vertical legs. */
    private fun arch(halfWidth: Float, top: Float, bottom: Float, corner: Float): List<NeoOp> =
        listOf(
            m(G - halfWidth, bottom),
            l(G - halfWidth, top + corner),
            q(G - halfWidth, top, G - halfWidth + corner, top),
            l(G + halfWidth - corner, top),
            q(G + halfWidth, top, G + halfWidth, top + corner),
            l(G + halfWidth, bottom),
        )

    /**
     * Expand / Collapse share the same two 45° shafts; only the head end flips (outer end pointing
     * out, or inner end pointing at the centre), so the two glyphs weigh exactly the same.
     */
    private fun cornerArrows(outward: Boolean): List<NeoShape> {
        val neA = 11.6f to 11.6f
        val neB = 19.2f to 4f
        val swA = 12.4f to 12.4f
        val swB = 4.8f to 20f
        val out = ArrayList<NeoOp>(4)
        out += m(neA.first, neA.second)
        out += l(neB.first, neB.second)
        out += m(swA.first, swA.second)
        out += l(swB.first, swB.second)
        if (outward) {
            out += chevron(neB.first, neB.second, 2.2f, -45f)
            out += chevron(swB.first, swB.second, 2.2f, 135f)
        } else {
            out += chevron(neA.first, neA.second, 2.2f, 45f)
            out += chevron(swA.first, swA.second, 2.2f, 225f)
        }
        return listOf(NeoShape(out))
    }

    // ------------------------------------------------------------------------------------
    // transforms — used to derive mirrored / rotated relatives
    // ------------------------------------------------------------------------------------

    private fun rotated(ops: List<NeoOp>, deg: Float): List<NeoOp> {
        val rad = deg * PI.toFloat() / 180f
        val cosA = cos(rad)
        val sinA = sin(rad)
        fun map(x: Float, y: Float): Pair<Float, Float> {
            val dx = x - G
            val dy = y - G
            return (G + dx * cosA - dy * sinA) to (G + dx * sinA + dy * cosA)
        }
        return ops.map { op -> mapOp(op, ::map, deg) }
    }

    private fun mirroredX(ops: List<NeoOp>): List<NeoOp> = ops.map { op ->
        mapOp(op, { x, y -> (2 * G - x) to y }, 0f)
    }

    private fun mirroredY(ops: List<NeoOp>): List<NeoOp> = ops.map { op ->
        mapOp(op, { x, y -> x to (2 * G - y) }, 0f)
    }

    private fun mapOp(op: NeoOp, f: (Float, Float) -> Pair<Float, Float>, rotDeg: Float): NeoOp =
        when (op) {
            is NeoOp.Move -> f(op.x, op.y).let { NeoOp.Move(it.first, it.second) }
            is NeoOp.Line -> f(op.x, op.y).let { NeoOp.Line(it.first, it.second) }
            is NeoOp.Quad -> {
                val ctrl = f(op.cx, op.cy)
                val end = f(op.x, op.y)
                NeoOp.Quad(ctrl.first, ctrl.second, end.first, end.second)
            }
            is NeoOp.Cubic -> {
                val c1 = f(op.c1x, op.c1y)
                val c2 = f(op.c2x, op.c2y)
                val end = f(op.x, op.y)
                NeoOp.Cubic(c1.first, c1.second, c2.first, c2.second, end.first, end.second)
            }
            is NeoOp.Circle -> f(op.cx, op.cy).let { NeoOp.Circle(it.first, it.second, op.r) }
            is NeoOp.RoundRect -> {
                val centre = f(op.x + op.w / 2f, op.y + op.h / 2f)
                val swap = kotlin.math.abs(kotlin.math.abs(rotDeg % 180f) - 90f) < 1f
                val w = if (swap) op.h else op.w
                val h = if (swap) op.w else op.h
                NeoOp.RoundRect(centre.first - w / 2f, centre.second - h / 2f, w, h, op.r)
            }
            is NeoOp.Arc -> {
                val centre = f(op.cx, op.cy)
                NeoOp.Arc(centre.first, centre.second, op.rx, op.ry, op.start + rotDeg, op.sweep, op.moveTo)
            }
            NeoOp.Close -> NeoOp.Close
        }

    // ------------------------------------------------------------------------------------
    // measurement — what NeoGlyphTest asserts on
    // ------------------------------------------------------------------------------------

    fun boundsOf(shape: NeoShape): NeoBox? {
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        fun add(x: Float, y: Float) {
            left = min(left, x)
            top = min(top, y)
            right = kotlin.math.max(right, x)
            bottom = kotlin.math.max(bottom, y)
        }
        shape.ops.forEach { op ->
            when (op) {
                is NeoOp.Move -> add(op.x, op.y)
                is NeoOp.Line -> add(op.x, op.y)
                is NeoOp.Quad -> { add(op.cx, op.cy); add(op.x, op.y) }
                is NeoOp.Cubic -> {
                    add(op.c1x, op.c1y); add(op.c2x, op.c2y); add(op.x, op.y)
                }
                is NeoOp.Circle -> {
                    add(op.cx - op.r, op.cy - op.r); add(op.cx + op.r, op.cy + op.r)
                }
                is NeoOp.RoundRect -> {
                    add(op.x, op.y); add(op.x + op.w, op.y + op.h)
                }
                is NeoOp.Arc -> {
                    add(op.cx - op.rx, op.cy - op.ry); add(op.cx + op.rx, op.cy + op.ry)
                }
                NeoOp.Close -> Unit
            }
        }
        return if (left > right) null else NeoBox(left, top, right, bottom)
    }
}

/** Path-building operation in 24-grid units. */
internal sealed interface NeoOp {
    data class Move(val x: Float, val y: Float) : NeoOp
    data class Line(val x: Float, val y: Float) : NeoOp
    data class Quad(val cx: Float, val cy: Float, val x: Float, val y: Float) : NeoOp
    data class Cubic(
        val c1x: Float,
        val c1y: Float,
        val c2x: Float,
        val c2y: Float,
        val x: Float,
        val y: Float,
    ) : NeoOp

    data class Circle(val cx: Float, val cy: Float, val r: Float) : NeoOp
    data class RoundRect(val x: Float, val y: Float, val w: Float, val h: Float, val r: Float) : NeoOp
    data class Arc(
        val cx: Float,
        val cy: Float,
        val rx: Float,
        val ry: Float,
        val start: Float,
        val sweep: Float,
        val moveTo: Boolean = true,
    ) : NeoOp

    data object Close : NeoOp
}

/** One strokable (or fillable) shape of a glyph, in 24-grid units. */
internal data class NeoShape(
    val ops: List<NeoOp>,
    val weight: Float = NeoGlyphGeometry.BaseWeight,
    val fillable: Boolean = false,
    val evenOdd: Boolean = false,
    /** Dot-like elements are solids in every state, never rings. */
    val alwaysSolid: Boolean = false,
)

/** Bounds of a shape in 24-grid units. */
internal data class NeoBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

private fun NeoShape.toPath(u: Float): Path {
    val path = Path()
    ops.forEach { op ->
        when (op) {
            is NeoOp.Move -> path.moveTo(op.x * u, op.y * u)
            is NeoOp.Line -> path.lineTo(op.x * u, op.y * u)
            is NeoOp.Quad -> path.quadraticBezierTo(op.cx * u, op.cy * u, op.x * u, op.y * u)
            is NeoOp.Cubic -> path.cubicTo(
                op.c1x * u, op.c1y * u,
                op.c2x * u, op.c2y * u,
                op.x * u, op.y * u,
            )
            is NeoOp.Circle -> path.addOval(
                Rect(
                    (op.cx - op.r) * u, (op.cy - op.r) * u,
                    (op.cx + op.r) * u, (op.cy + op.r) * u,
                ),
            )
            is NeoOp.RoundRect -> path.addRoundRect(
                RoundRect(
                    op.x * u, op.y * u,
                    (op.x + op.w) * u, (op.y + op.h) * u,
                    CornerRadius(op.r * u, op.r * u),
                ),
            )
            is NeoOp.Arc -> path.arcTo(
                Rect(
                    (op.cx - op.rx) * u, (op.cy - op.ry) * u,
                    (op.cx + op.rx) * u, (op.cy + op.ry) * u,
                ),
                op.start,
                op.sweep,
                forceMoveTo = op.moveTo,
            )
            NeoOp.Close -> path.close()
        }
    }
    return path
}
