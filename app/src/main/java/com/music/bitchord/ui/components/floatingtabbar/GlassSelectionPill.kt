/*
 * The selection pill's motion — the travel spring, the held lift, the
 * acceleration squash and the glass-to-flat hand-off, with their constants —
 * is ported from liquid_glass_easy's LiquidGlassAnimatedNavBar and
 * LiquidGlassLensMotion (pub.dev/packages/liquid_glass_easy, 4.3.4).
 * Copyright (c) 2025 Ahmed Gamil, MIT License.
 *
 * The rendering is BitChord's own, on the vendored backdrop library: the lens
 * refracts the tab bar's exported glass and its recorded tab row rather than a
 * screen capture.
 */
package com.music.bitchord.ui.components.floatingtabbar

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.components.LocalAppBackdrop
import com.music.bitchord.ui.components.backdrop.Backdrop
import com.music.bitchord.ui.components.backdrop.backdrops.LayerBackdrop
import com.music.bitchord.ui.components.backdrop.drawBackdrop
import com.music.bitchord.ui.components.backdrop.effects.lens
import com.music.bitchord.ui.components.backdrop.highlight.Highlight
import com.music.bitchord.ui.components.backdrop.highlight.HighlightStyle
import com.music.bitchord.ui.components.backdrop.shadow.Shadow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sign

/** How much taller than the bar the pill stands while it is lifted. */
internal val PILL_GROW_HEIGHT = 4.dp

/**
 * The selection pill's physics, owned by the tab bar and handed to whichever
 * pill is drawing it.
 *
 * Tuned for snappy, immediate transitions when tapping another tab:
 * quick critically-damped travel spring, subtle lift, and allocation-free
 * dynamics.
 *
 * Positions are in tab steps: `0` is the first tab, `1` the second.
 */
@Stable
internal class GlassPillMotion(
    private val scope: CoroutineScope,
    initialIndex: Int,
) {
    /** Where the pill is drawn, in tab steps. */
    var position by mutableFloatStateOf(initialIndex.toFloat())
        private set

    /** The width lift, `0` at rest and `1` at the raised size; overshoots both. */
    var liftX by mutableFloatStateOf(0f)
        private set

    /** The height lift; see [liftX]. */
    var liftY by mutableFloatStateOf(0f)
        private set

    /** How much of the material is on, `0`..`1`. */
    var material by mutableFloatStateOf(0f)
        private set

    /** Acceleration squash: width scales by `1 + this`, height by `1 - this`. */
    var deviation by mutableFloatStateOf(0f)
        private set

    /** How much of the pill still reads as glass, `1` lifted to `0` flat. */
    var glassPresence by mutableFloatStateOf(0f)
        private set

    /**
     * True when there is nothing left in the pill but a flat fill at rest size,
     * where the plain pill draws exactly the same picture.
     */
    var isFlat by mutableStateOf(true)
        private set

    /** The tab the pill is headed for. */
    var index: Int = initialIndex
        private set

    /** One tab step in dp, which the squash model samples the pill's travel in. */
    var stepDp: Float = 0f

    /** "Reduce animation": every move lands at once, with no lift. */
    var reduceMotion: Boolean = false

    private var travelPos = initialIndex.toDouble()
    private var travelVel = 0.0
    private var travelTarget = travelPos
    private var travelFrom = travelPos
    private var travelActive = false

    private var lifted = false
    private var liftXPos = 0.0
    private var liftXVel = 0.0
    private var liftYPos = 0.0
    private var liftYVel = 0.0
    private var liftPos = 0.0
    private var liftVel = 0.0

    private var dragging = false
    private var dragTarget = 0.0
    private var dragFollow = 0.0

    private var travelSign = 0.0
    private var travelSignEased = 0.0
    private var handover = 1.0
    private var squash = 0.0
    private val squashModel = PillSquashModel()

    private var running = false

    /** A tab was chosen: travel there, lifted. Re-choosing the current tab is a no-op. */
    fun animateTo(next: Int) {
        if (next == index) return
        if (reduceMotion) {
            snapTo(next)
            return
        }
        index = next
        travelActive = true
        lifted = true
        // Retarget from wherever the pill is; the spring keeps its velocity.
        travelFrom = travelPos
        travelTarget = next.toDouble()
        travelSign = signOfTravel(travelTarget - travelFrom)
        isFlat = false
        start()
    }

    /** Place the pill on [target] at rest, with no travel and no lift. */
    fun snapTo(target: Int) {
        index = target
        travelPos = target.toDouble()
        travelTarget = travelPos
        travelFrom = travelPos
        travelVel = 0.0
        travelActive = false
        dragging = false
        lifted = false
        liftXPos = 0.0; liftXVel = 0.0
        liftYPos = 0.0; liftYVel = 0.0
        liftPos = 0.0; liftVel = 0.0
        handover = 1.0
        squash = 0.0
        squashModel.stop()
        travelSign = 0.0
        travelSignEased = 0.0
        publish()
        isFlat = true
    }

    /** A finger has picked the pill up where it stands. */
    fun startDrag() {
        dragging = true
        travelActive = false
        lifted = !reduceMotion
        // The hand takes the deformation back off the travel.
        travelSign = 0.0
        dragFollow = travelPos
        dragTarget = travelPos
        travelVel = 0.0
        isFlat = false
        start()
    }

    /** The finger is over [position] tab steps. */
    fun dragTo(position: Float) {
        if (dragging) dragTarget = position.toDouble()
    }

    /**
     * The finger let go and the pill is to settle on [next]. It stays lifted
     * through the snap and comes down on landing, exactly as a tap's does.
     */
    fun release(next: Int) {
        if (!dragging) return
        val from = dragFollow
        dragging = false
        index = next
        if (reduceMotion) {
            snapTo(next)
            return
        }
        travelActive = true
        travelPos = from
        travelVel = 0.0
        travelFrom = from
        travelTarget = next.toDouble()
        travelSign = signOfTravel(travelTarget - travelFrom)
        start()
    }

    private fun start() {
        if (running) return
        running = true
        scope.launch {
            var last = -1L
            try {
                while (true) {
                    val now = withFrameNanos { it }
                    val dt = if (last < 0) 0.0 else (now - last) / 1e9
                    last = now
                    if (!tick(dt, now / 1e9)) break
                }
            } finally {
                running = false
            }
        }
    }

    /** One frame of everything. False once all of it has settled. */
    private fun tick(dt: Double, now: Double): Boolean {
        // 1) Travel: snappy, critically-damped spring.
        var travelSettled = true
        if (travelActive) {
            val r = springStep(travelPos, travelVel, travelTarget, dt, TRAVEL_STIFFNESS, TRAVEL_DAMPING)
            travelPos = r.first
            travelVel = r.second
            travelSettled = abs(travelPos - travelTarget) < 0.003 && abs(travelVel) < 0.05
            if (travelSettled) {
                travelPos = travelTarget
                travelVel = 0.0
            }
        }

        // 2) Under a finger, chase it smoothly.
        if (dragging) {
            dragFollow += if (reduceMotion) {
                dragTarget - dragFollow
            } else {
                (dragTarget - dragFollow) * (1 - exp(-dt / FOLLOW_TAU))
            }
        }

        // 3) The lift, held for the journey and released on landing.
        if (!dragging && (travelSettled || travelProgress() >= HANDOVER_START)) {
            lifted = false
        }
        val liftTarget = if (lifted) 1.0 else 0.0
        stepLift(liftXPos, liftXVel, liftTarget, dt, LIFT_STIFFNESS, LIFT_DAMPING_X).let {
            liftXPos = it.first; liftXVel = it.second
        }
        stepLift(liftYPos, liftYVel, liftTarget, dt, LIFT_STIFFNESS, LIFT_DAMPING_Y).let {
            liftYPos = it.first; liftYVel = it.second
        }
        stepLift(liftPos, liftVel, liftTarget, dt, MATERIAL_STIFFNESS, MATERIAL_DAMPING).let {
            liftPos = it.first; liftVel = it.second
        }
        val liftSettled = !lifted && liftXPos == 0.0 && liftYPos == 0.0 && liftPos == 0.0

        // 4) The deflation outlives the spring that carried the pill there.
        if (travelActive && travelSettled && liftSettled && !dragging) {
            travelActive = false
        }

        // 5) Sample the pill where it is drawn this frame.
        val frac = if (dragging) dragFollow else travelPos
        if (stepDp > 0f) {
            if (!squashModel.isTracking) squashModel.start()
            squash = squashModel.track(frac * stepDp, now, dt)
            if (travelSignEased == 0.0) {
                travelSignEased = travelSign
            } else if (travelSignEased != travelSign) {
                travelSignEased += (travelSign - travelSignEased) * (1 - exp(-dt / SIGN_TAU))
                if (abs(travelSign - travelSignEased) < 0.01) travelSignEased = travelSign
            }
            // Keep the force's magnitude and take its sign from the direction
            // of travel, so a trip deforms one way the whole way.
            val key = travelSignEased
            if (key != 0.0) squash = squash * (1 - abs(key)) - key * abs(squash)
        }

        // 6) The hand-off rides the same landing as the deflation.
        val handoverTarget = if (lifted) 0.0 else 1.0
        val tau = if (handoverTarget > handover) HANDOVER_TAU else GLASS_RETURN_TAU
        handover += (handoverTarget - handover) * (1 - exp(-dt / tau))
        if (abs(handoverTarget - handover) < 0.002) handover = handoverTarget

        val settled = !travelActive && !dragging && liftSettled &&
            abs(squash) < 0.0005 && handover >= 1.0
        if (settled) {
            squashModel.stop()
            squash = 0.0
            travelSign = 0.0
            travelSignEased = 0.0
        }
        publish()
        isFlat = settled
        return !settled
    }

    private fun publish() {
        position = (if (dragging) dragFollow else travelPos).toFloat()
        liftX = liftXPos.toFloat()
        liftY = liftYPos.toFloat()
        material = liftPos.coerceIn(0.0, 1.0).toFloat()
        deviation = squash.toFloat()
        glassPresence = (1 - handover).coerceIn(0.0, 1.0).toFloat()
    }

    private fun travelProgress(): Double {
        val span = abs(travelTarget - travelFrom)
        if (span < 1e-6) return 1.0
        return (1 - abs(travelTarget - travelPos) / span).coerceIn(0.0, 1.0)
    }

    private fun signOfTravel(span: Double): Double = if (abs(span) < 1e-6) 0.0 else sign(span)

    /** The size the pill is drawn at this frame, between [rest] and [lifted]. */
    fun liveSize(rest: Size, lifted: Size): Size {
        val w = rest.width + (lifted.width - rest.width) * liftX
        val h = rest.height + (lifted.height - rest.height) * liftY
        return Size(w * (1 + deviation), h * (1 - deviation))
    }

    private companion object {
        const val TRAVEL_STIFFNESS = 480.0
        const val TRAVEL_DAMPING = 42.0

        /** Damping ratio 0.85 across, 0.9 down: snappy and responsive. */
        const val LIFT_STIFFNESS = 420.0
        const val LIFT_DAMPING_X = 35.0
        const val LIFT_DAMPING_Y = 38.0

        /** Critically damped: the glass is on swiftly and sheds promptly. */
        const val MATERIAL_STIFFNESS = 1400.0
        const val MATERIAL_DAMPING = 74.0

        const val HANDOVER_START = 0.85
        const val HANDOVER_TAU = 0.045
        const val GLASS_RETURN_TAU = 0.035
        const val FOLLOW_TAU = 0.04
        const val SIGN_TAU = 0.18

        /** Semi-implicit Euler in 1/240 s substeps, so a long frame cannot blow it up. */
        fun springStep(
            x: Double,
            vel: Double,
            target: Double,
            dt: Double,
            stiffness: Double,
            damping: Double,
        ): Pair<Double, Double> {
            var t = dt
            var px = x
            var pv = vel
            while (t > 0) {
                val step = if (t > 1 / 240.0) 1 / 240.0 else t
                val accel = -stiffness * (px - target) - damping * pv
                pv += accel * step
                px += pv * step
                t -= step
            }
            return px to pv
        }

        /** A spring step snapped to its target once it has nothing left to say. */
        fun stepLift(
            x: Double,
            vel: Double,
            target: Double,
            dt: Double,
            stiffness: Double,
            damping: Double,
        ): Pair<Double, Double> {
            val r = springStep(x, vel, target, dt, stiffness, damping)
            return if (abs(r.first - target) < 0.0008 && abs(r.second) < 0.01) target to 0.0 else r
        }
    }
}

/**
 * Acceleration to deformation: samples the pill's position into a fixed ring
 * buffer without per-frame allocations, differentiated and eased.
 */
private class PillSquashModel {
    private companion object {
        const val MAX_SAMPLES = 8
        const val SAMPLE_WINDOW = 0.25
        const val SENSITIVITY = 0.00007
        const val MAX_DEFORMATION = 0.08
        const val RESPONSE_TIME = 0.12
    }

    private val xHistory = DoubleArray(MAX_SAMPLES)
    private val tHistory = DoubleArray(MAX_SAMPLES)
    private var count = 0
    private var head = 0
    var isTracking = false
        private set
    private var value = 0.0

    fun start() {
        count = 0
        head = 0
        value = 0.0
        isTracking = true
    }

    fun stop() {
        isTracking = false
        count = 0
        head = 0
        value = 0.0
    }

    fun track(x: Double, now: Double, dt: Double): Double {
        if (!isTracking) return value
        xHistory[head] = x
        tHistory[head] = now
        head = (head + 1) % MAX_SAMPLES
        if (count < MAX_SAMPLES) count++

        val raw = (calculateAcceleration(now) * SENSITIVITY).coerceIn(-MAX_DEFORMATION, MAX_DEFORMATION)
        val ease = (dt / RESPONSE_TIME).coerceIn(0.0, 1.0)
        value += (raw - value) * ease
        return value
    }

    private fun calculateAcceleration(now: Double): Double {
        if (count < 3) return 0.0
        val cutoff = now - SAMPLE_WINDOW

        val p2 = (head - 1 + MAX_SAMPLES) % MAX_SAMPLES
        val p1 = (head - 2 + MAX_SAMPLES) % MAX_SAMPLES
        val p0 = (head - 3 + MAX_SAMPLES) % MAX_SAMPLES

        if (tHistory[p0] < cutoff) return 0.0

        val dt1 = tHistory[p1] - tHistory[p0]
        val dt2 = tHistory[p2] - tHistory[p1]
        if (dt1 <= 0.0 || dt2 <= 0.0) return 0.0

        val v1 = (xHistory[p1] - xHistory[p0]) / dt1
        val v2 = (xHistory[p2] - xHistory[p1]) / dt2
        val dtMid = (dt1 + dt2) * 0.5
        if (dtMid <= 0.0) return 0.0

        return (v2 - v1) / dtMid
    }
}

/**
 * Places a pill-sized child centred on [center] at [size], inside a parent it
 * matches the size of — so the pill can move and grow past that parent's
 * edges without ever changing its measured size. Both read on layout, not
 * composition, so a frame of motion is a relayout and nothing more.
 */
internal fun Modifier.pillPlacement(center: () -> Offset, size: () -> Size): Modifier =
    layout { measurable, constraints ->
        val s = size()
        val w = s.width.roundToInt().coerceAtLeast(1)
        val h = s.height.roundToInt().coerceAtLeast(1)
        val placeable = measurable.measure(Constraints.fixed(w, h))
        layout(constraints.minWidth, constraints.minHeight) {
            val c = center()
            placeable.place((c.x - w / 2f).roundToInt(), (c.y - h / 2f).roundToInt())
        }
    }

/** The flat pill: a fill and nothing else. */
@Composable
internal fun FlatSelectionPill(
    color: Color,
    modifier: Modifier,
) {
    val shape = remember { RoundedCornerShape(percent = 50) }
    Box(modifier.background(color, shape))
}

/**
 * The lifted pill: a lens over the tab bar that refracts the bar's own glass
 * and its tab row, drawn above both and free to stand past the bar's edges.
 *
 * Rendered with an optimized single-pass refraction shader and softened
 * highlight reflection for fluid 120Hz frame rates.
 */
@Composable
internal fun GlassSelectionPill(
    motion: GlassPillMotion,
    fillColor: Color,
    barGlass: LayerBackdrop,
    tabRow: LayerBackdrop,
    modifier: Modifier,
) {
    val page = LocalAppBackdrop.current
    val shape = remember { RoundedCornerShape(percent = 50) }
    val backdrop = remember(page, barGlass, tabRow, fillColor) {
        SelectionPillBackdrop(page, barGlass, tabRow) {
            fillColor.copy(alpha = fillColor.alpha * (1f - motion.material))
        }
    }
    val shapeLambda = remember { { shape } }
    val pillHighlight = remember {
        Highlight(
            width = 0.5.dp,
            alpha = 0.65f,
            style = HighlightStyle.Default(
                color = Color.White.copy(alpha = 0.30f),
                blendMode = BlendMode.Plus,
                angle = 45f,
                falloff = 1.3f,
            ),
        )
    }
    val density = LocalDensity.current
    val lensHeightPx = with(density) { LENS_HEIGHT.toPx() }
    val lensAmountPx = with(density) { LENS_AMOUNT.toPx() }

    Box(
        modifier.drawBackdrop(
            backdrop = backdrop,
            shape = shapeLambda,
            effects = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    lens(
                        refractionHeight = lensHeightPx * motion.glassPresence,
                        refractionAmount = lensAmountPx * motion.material,
                        chromaticAberration = false,
                    )
                }
            },
            highlight = {
                val alpha = motion.material * rimOf(motion.glassPresence)
                if (alpha > 0.01f) pillHighlight.copy(alpha = pillHighlight.alpha * alpha) else null
            },
            shadow = {
                val alpha = motion.material * rimOf(motion.glassPresence)
                if (alpha > 0.01f) PILL_SHADOW.copy(alpha = PILL_SHADOW.alpha * alpha) else null
            },
        ),
    )
}

/** The rim and its shadow are gone by the time the hand-off is 55% through. */
private fun rimOf(presence: Float): Float = ((presence - 0.45f) / 0.55f).coerceIn(0f, 1f)

private val LENS_HEIGHT = 7.dp
private val LENS_AMOUNT = 8.dp

private val PILL_SHADOW = Shadow(
    radius = 6.dp,
    offset = DpOffset(0.dp, 1.5.dp),
    color = Color.Black.copy(alpha = 0.18f),
)

/**
 * What the pill refracts, bottom to top: the page, the bar's glass, the
 * selection fill, and the tab row. The fill sits under the tabs here for the
 * same reason the flat pill does — over them it would grey the glyphs.
 */
private class SelectionPillBackdrop(
    private val page: Backdrop,
    private val bar: Backdrop,
    private val tabs: Backdrop,
    private val fill: () -> Color,
) : Backdrop {

    override val isCoordinatesDependent: Boolean = true

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        with(page) { drawBackdrop(density, coordinates, layerBlock) }
        with(bar) { drawBackdrop(density, coordinates, layerBlock) }
        val color = fill()
        if (color.alpha > 0f) {
            // Past every edge: the recorded area includes the lens's padding.
            drawRect(
                color = color,
                topLeft = Offset(-size.width, -size.height),
                size = Size(size.width * 3, size.height * 3),
            )
        }
        with(tabs) { drawBackdrop(density, coordinates, layerBlock) }
    }
}
