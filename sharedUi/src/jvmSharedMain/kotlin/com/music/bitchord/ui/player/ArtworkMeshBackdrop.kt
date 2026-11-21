package com.music.bitchord.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import coil3.compose.LocalPlatformContext
import com.music.bitchord.ui.graphics.argbPixels
import com.music.bitchord.ui.graphics.forPixelAccess
import com.music.bitchord.ui.graphics.imageBitmapOf
import com.music.bitchord.ui.graphics.toImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.graphics.ColorUtils
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import coil3.size.Scale
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.QueueEntryIdentity
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.artworkAt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The artwork's own colours, upside down and reduced to a mesh, for the player
 * to stand on.
 *
 * [MeshGradientBackground] answers a different question: it asks the quantiser
 * what colours a sleeve is *about* and paints four blobs of them. That is why a
 * cover that is nine-tenths black with a red stripe came out as a red screen —
 * the quantiser reports red because red is the interesting answer, and nothing
 * downstream knows how little of the picture it was. It also has no idea *where*
 * in the frame that red was.
 *
 * This is the other approach: no quantiser at all. The sleeve is averaged into a
 * [MESH_GRID] square of means; the row against the seam is kept in place, and
 * everything below it is flipped and then rotated sideways — see
 * [rotatedBelowSeam]. What the backdrop holds is the cover's own colours,
 * roughly in the cover's own proportions (nine-tenths black stays nine-tenths
 * black) and with each cell's neighbours exactly what they were in the source,
 * but not lined up in the cover's own *position*: a flip on its own reads as a
 * reflection on any cover with real structure to it — a face, a horizon, a
 * logo — however coarse the grid, because the layout still lines up column for
 * column with what's on screen above it. The rotation is what actually breaks
 * that column-for-column match; the coarseness just keeps any one cell from
 * being recognisable on its own.
 *
 * Held as a tiny bitmap rather than a list of colours because that is exactly
 * what the hardware wants: one [DrawScope.drawImage] with bilinear filtering
 * interpolates the whole mesh in the sampler. The alternative — a blob per cell,
 * as the old backdrop draws — would be thirty-six full-screen radial gradients.
 */
@Immutable
class ArtworkMesh internal constructor(internal val image: ImageBitmap)

/**
 * Extracts and heavily blurs the bottom edge of an album art image.
 *
 * Like an edge bleed RGB LED strip for monitors, this samples the colours along
 * the bottom edge of the sleeve (preserving their horizontal layout from left
 * to right across [targetWidth] columns), lifts saturation for LED-strip vibrance,
 * and box-blurs them into a diffuse gradient.
 */
fun extractBottomEdgeBlur(
    source: ImageBitmap,
    stripFraction: Float = 0.25f,
    targetWidth: Int = 48,
    targetHeight: Int = 16,
    passes: Int = 3,
): ImageBitmap {
    val srcW = source.width
    val srcH = source.height
    if (srcW < 2 || srcH < 2) return source

    val stripH = (srcH * stripFraction).roundToInt().coerceIn(1, srcH)
    val startY = srcH - stripH

    val stripPixels = IntArray(srcW * stripH)
    source.readPixels(stripPixels, startX = 0, startY = startY, width = srcW, height = stripH)

    val downsampled = IntArray(targetWidth * targetHeight)
    val xRatio = srcW.toFloat() / targetWidth
    val yRatio = stripH.toFloat() / targetHeight
    for (ty in 0 until targetHeight) {
        val sy = (ty * yRatio).toInt().coerceIn(0, stripH - 1)
        val rowOffset = ty * targetWidth
        val srcRowOffset = sy * srcW
        for (tx in 0 until targetWidth) {
            val sx = (tx * xRatio).toInt().coerceIn(0, source.width - 1)
            downsampled[rowOffset + tx] = stripPixels[srcRowOffset + sx]
        }
    }

    val hsl = FloatArray(3)
    for (i in downsampled.indices) {
        val pixel = downsampled[i]
        ColorUtils.colorToHSL(pixel, hsl)
        hsl[1] = (hsl[1] * 1.35f).coerceAtMost(1f)
        hsl[2] = (hsl[2] * 1.15f).coerceIn(0.08f, 0.85f)
        downsampled[i] = ColorUtils.HSLToColor(hsl)
    }

    val blurred = boxBlurPixels(downsampled, targetWidth, targetHeight, radius = 3, passes = passes)
    return imageBitmapOf(blurred, targetWidth, targetHeight)
}

/** LRU cache of recently decoded bottom-edge blur bitmaps. */
private val bottomBlurCache = object : LinkedHashMap<String, ImageBitmap>(0, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, ImageBitmap>) = size > 16
}

/**
 * Prepares the bottom-edge bleed blur bitmap for a cover or an active motion clip frame.
 *
 * Pre-blurs the artwork using multi-pass box blur on a background worker thread.
 */
@Composable
fun rememberBottomEdgeBlur(
    imageUrl: String?,
    canvasFrame: ImageBitmap? = null,
    prepare: Boolean = true,
): ImageBitmap? {
    val context = LocalPlatformContext.current
    var blurImage by remember { mutableStateOf(imageUrl?.let(bottomBlurCache::get)) }

    // Dynamic update when an animated motion clip provides a captured frame
    LaunchedEffect(canvasFrame) {
        if (canvasFrame != null) {
            val blurred = withContext(Dispatchers.Default) {
                canvasFrame.boxBlurred(FULL_BLUR_PASSES)
            }
            blurImage = blurred
        }
    }

    // Static artwork lookup via Coil
    LaunchedEffect(imageUrl, prepare) {
        imageUrl?.let(bottomBlurCache::get)?.let {
            if (canvasFrame == null) blurImage = it
            return@LaunchedEffect
        }
        if (!prepare || imageUrl == null) return@LaunchedEffect
        val request = ImageRequest.Builder(context)
            .data(imageUrl.artworkAt(CARD_ART_PX) ?: imageUrl)
            .size(FULL_BLUR_SOURCE_PX)
            .forPixelAccess()
            .build()
        val result = SingletonImageLoader.get(context).execute(request)
        val bitmap = (result as? SuccessResult)?.image?.toImageBitmap() ?: return@LaunchedEffect
        val blurred = withContext(Dispatchers.Default) {
            bitmap.boxBlurred(FULL_BLUR_PASSES)
        }
        bottomBlurCache[imageUrl] = blurred
        if (canvasFrame == null) {
            blurImage = blurred
        }
    }

    return blurImage
}

/**
 * Samples a solid background color from the very bottom edge of an album cover (or motion frame).
 *
 * Samples rows from 88% to 98% of the image (skipping the outermost 2% to avoid compression or
 * letterbox borders), averages the RGB channels, and normalizes lightness so white text and
 * controls have high contrast while preserving the artwork's genuine hue and tone.
 */
fun extractBottomSolidColor(source: ImageBitmap, stripFraction: Float = 0.03f): Color {
    val srcW = source.width
    val srcH = source.height
    if (srcW < 1 || srcH < 1) return FallbackBackdrop

    val allPixels = try {
        source.argbPixels()
    } catch (_: Throwable) {
        return FallbackBackdrop
    }
    if (allPixels.isEmpty()) return FallbackBackdrop

    val topY = (srcH * 0.88f).roundToInt().coerceIn(0, srcH - 1)
    val botY = (srcH * 0.98f).roundToInt().coerceIn(topY + 1, srcH)

    var rSum = 0L
    var gSum = 0L
    var bSum = 0L
    var count = 0

    var fbRSum = 0L
    var fbGSum = 0L
    var fbBSum = 0L
    var fbCount = 0

    for (y in topY until botY) {
        val rowOffset = y * srcW
        for (x in 0 until srcW) {
            val pixel = allPixels[rowOffset + x]
            val a = (pixel ushr 24) and 0xFF
            if (a < 128) continue
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            fbRSum += r
            fbGSum += g
            fbBSum += b
            fbCount++

            // Skip pure black letterbox or compression border pixels
            if (r == 0 && g == 0 && b == 0) continue

            rSum += r
            gSum += g
            bSum += b
            count++
        }
    }

    val finalCount = if (count > 0) count else fbCount
    if (finalCount == 0) return FallbackBackdrop

    val avgR = (if (count > 0) rSum / count else fbRSum / fbCount).toInt()
    val avgG = (if (count > 0) gSum / count else fbGSum / fbCount).toInt()
    val avgB = (if (count > 0) bSum / count else fbBSum / fbCount).toInt()

    val hsl = FloatArray(3)
    ColorUtils.RGBToHSL(avgR, avgG, avgB, hsl)
    // Preserve true hue and saturation from the artwork.
    // Gently cap lightness at 0.58f so white text/controls remain readable (Apple Music style).
    // Floor lightness at 0.10f to prevent pitch black crush.
    hsl[2] = hsl[2].coerceIn(0.10f, 0.58f)
    return Color(ColorUtils.HSLToColor(hsl))
}

/** LRU cache of recently sampled bottom-edge solid colors. */
private val bottomColorCache = object : LinkedHashMap<String, Color>(0, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, Color>) = size > 24
}

/** LRU cache of recently generated curved blur mask bitmaps. */
private val curvedMaskCache = object : LinkedHashMap<String, ImageBitmap>(0, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, ImageBitmap>) = size > 8
}

const val MASK_W = 128
const val MASK_H = 256
const val CURVED_BLUR_COVERAGE = 0.63f

/**
 * Stateful builder that mirrors `rebuildCurvedMask` identically:
 * - Skips rebuilding while dimensions, artwork height (< 0.5px), and coverage (< 0.005) stay within tolerance.
 * - Produces an alpha-only mask (`0` above the curve -> smoothstep -> `255` below the curve)
 *   stored as `(maskAlpha shl 24)` using the exact `0.54f` cosine dip and gentle `0.13f` / `0.85f` edge lift.
 */
class CurvedBlurMaskState {
    private var cachedWidth: Int = -1
    private var cachedHeight: Int = -1
    private var cachedArtworkHeight: Float = Float.NaN
    private var cachedCoverage: Float = Float.NaN
    private val maskPixels = IntArray(MASK_W * MASK_H)
    private var cachedMask: ImageBitmap? = null

    fun rebuild(
        width: Int,
        height: Int,
        effectiveArtworkHeight: Float,
        coverage: Float = CURVED_BLUR_COVERAGE,
    ): ImageBitmap? {
        if (
            abs(effectiveArtworkHeight - cachedArtworkHeight) < 0.5f &&
            abs(coverage - cachedCoverage) < 0.005f &&
            width == cachedWidth &&
            height == cachedHeight &&
            cachedMask != null
        ) {
            return cachedMask
        }

        cachedArtworkHeight = effectiveArtworkHeight
        cachedCoverage = coverage
        cachedWidth = width
        cachedHeight = height

        if (effectiveArtworkHeight <= 1.0f || width <= 0 || height <= 0) {
            cachedMask = null
            return null
        }

        val edgeLiftPx = effectiveArtworkHeight * 0.13f
        val fadeStartPx = effectiveArtworkHeight * coverage + 10.0f
        val fadeEndPx = minOf(height.toFloat(), effectiveArtworkHeight * 1.01f + 10.0f)

        for (col in 0 until MASK_W) {
            val normX = col.toFloat() / (MASK_W - 1).toFloat()
            val curveU = ((normX - 0.54f) / 0.54f).coerceIn(-1.0f, 1.0f)
            val curveDip = 0.5f * (1.0f + cos(PI * curveU).toFloat())

            val columnStartY = maxOf(0.0f, fadeStartPx - edgeLiftPx * (1.0f - curveDip))
            val columnEndY = maxOf(
                columnStartY + 8.0f,
                fadeEndPx - edgeLiftPx * 0.85f * (1.0f - curveDip),
            )
            val invBandSpan = 1.0f / (columnEndY - columnStartY)

            for (row in 0 until MASK_H) {
                val sampleY = (row.toFloat() / (MASK_H - 1).toFloat()) * height.toFloat()
                val maskAlpha = when {
                    sampleY <= columnStartY -> 0
                    sampleY >= columnEndY -> 255
                    else -> {
                        val progress = (sampleY - columnStartY) * invBandSpan
                        val smooth = progress * progress * (3.0f - 2.0f * progress)
                        (smooth * 255.0f).roundToInt().coerceIn(0, 255)
                    }
                }
                maskPixels[row * MASK_W + col] = maskAlpha shl 24
            }
        }

        val bitmap = imageBitmapOf(maskPixels, MASK_W, MASK_H)
        cachedMask = bitmap
        return bitmap
    }
}

/**
 * Applies the exact curved blur falloff to a sharp artwork layer (inside an offscreen compositing
 * layer) using `BlendMode.DstOut`, erasing the lower edge along the curve so the blurred backdrop
 * underneath shows through identically to `rebuildCurvedMask`.
 */
fun DrawScope.drawCurvedArtworkMask(
    maskState: CurvedBlurMaskState,
    effectiveArtworkHeight: Float = size.height,
    coverage: Float = CURVED_BLUR_COVERAGE,
    alpha: Float = 1f,
) {
    if (alpha <= 0f) return
    val drawWidth = size.width.roundToInt()
    val drawHeight = size.height.roundToInt()
    if (drawWidth <= 0 || drawHeight <= 0) return

    val maskBitmap = maskState.rebuild(
        width = drawWidth,
        height = drawHeight,
        effectiveArtworkHeight = effectiveArtworkHeight,
        coverage = coverage,
    ) ?: return

    drawImage(
        image = maskBitmap,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(maskBitmap.width, maskBitmap.height),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(drawWidth, drawHeight),
        alpha = alpha.coerceIn(0f, 1f),
        filterQuality = FilterQuality.Medium,
        blendMode = BlendMode.DstOut,
    )
}

fun buildCurvedMaskBitmap(
    w: Int,
    h: Int,
    effectiveArtH: Float,
    coverage: Float = CURVED_BLUR_COVERAGE,
    invert: Boolean = false,
): ImageBitmap {
    val key = "$w|$h|${(effectiveArtH * 2).roundToInt()}|${(coverage * 200).roundToInt()}|$invert"
    curvedMaskCache[key]?.let { return it }

    val safeHeight = h.coerceAtLeast(1)
    val safeArtHeight = effectiveArtH.coerceAtLeast(1.0f)
    val edgeLiftPx = safeArtHeight * 0.13f
    val fadeStartPx = safeArtHeight * coverage + 10.0f
    val fadeEndPx = minOf(safeHeight.toFloat(), safeArtHeight * 1.01f + 10.0f)

    val pixels = IntArray(MASK_W * MASK_H)

    for (col in 0 until MASK_W) {
        val normX = col.toFloat() / (MASK_W - 1).toFloat()
        val curveU = ((normX - 0.54f) / 0.54f).coerceIn(-1.0f, 1.0f)
        val curveDip = 0.5f * (1.0f + cos(PI * curveU).toFloat())

        val columnStartY = maxOf(0.0f, fadeStartPx - edgeLiftPx * (1.0f - curveDip))
        val columnEndY = maxOf(
            columnStartY + 8.0f,
            fadeEndPx - edgeLiftPx * 0.85f * (1.0f - curveDip),
        )
        val invBandSpan = 1.0f / (columnEndY - columnStartY)

        for (row in 0 until MASK_H) {
            val sampleY = (row.toFloat() / (MASK_H - 1).toFloat()) * safeHeight.toFloat()
            val maskAlpha = when {
                sampleY <= columnStartY -> 0
                sampleY >= columnEndY -> 255
                else -> {
                    val progress = (sampleY - columnStartY) * invBandSpan
                    val smooth = progress * progress * (3.0f - 2.0f * progress)
                    (smooth * 255.0f).roundToInt().coerceIn(0, 255)
                }
            }
            val finalAlpha = if (invert) 255 - maskAlpha else maskAlpha
            pixels[row * MASK_W + col] = finalAlpha shl 24
        }
    }

    val result = imageBitmapOf(pixels, MASK_W, MASK_H)
    curvedMaskCache[key] = result
    return result
}

fun getCurvedBlurMask(
    targetWidth: Int = MASK_W,
    targetHeight: Int = MASK_H,
    sideYFraction: Float = 0.50f,
    centerYFraction: Float = 0.57f,
    blurRadius: Int = 16,
    passes: Int = 4,
): ImageBitmap {
    val safeHeight = targetHeight.coerceAtLeast(1)
    val effectiveArtworkHeight = (safeHeight * centerYFraction / 1.01f).coerceAtLeast(1f)
    val coverage = (sideYFraction / centerYFraction).coerceIn(0.40f, 0.90f)
    return buildCurvedMaskBitmap(
        w = targetWidth,
        h = targetHeight,
        effectiveArtH = effectiveArtworkHeight,
        coverage = coverage,
        invert = false,
    )
}

fun getCurvedBlurMask(
    w: Int,
    h: Int,
    effectiveArtH: Float,
    coverage: Float = CURVED_BLUR_COVERAGE,
    invert: Boolean = false,
): ImageBitmap = buildCurvedMaskBitmap(
    w = w,
    h = h,
    effectiveArtH = effectiveArtH,
    coverage = coverage,
    invert = invert,
)

/**
 * Prepares the solid background color picked up from the very bottom edge of the album.
 *
 * Keeps the first pulled color; does not change colors on subsequent motion frames.
 */
@Composable
fun rememberBottomSolidColor(
    imageUrl: String?,
    canvasFrame: ImageBitmap? = null,
    defaultColor: Color = FallbackBackdrop,
): Color {
    val context = LocalPlatformContext.current
    var color by remember(imageUrl) { mutableStateOf(imageUrl?.let(bottomColorCache::get) ?: defaultColor) }
    var colorLocked by remember(imageUrl) { mutableStateOf(imageUrl != null && bottomColorCache.containsKey(imageUrl)) }

    // Keep the first pulled color; do not change colors as animated arts play
    LaunchedEffect(canvasFrame) {
        if (canvasFrame != null && !colorLocked) {
            val extracted = withContext(Dispatchers.Default) {
                extractBottomSolidColor(canvasFrame)
            }
            if (extracted != FallbackBackdrop) {
                color = extracted
                colorLocked = true
                if (imageUrl != null) {
                    bottomColorCache[imageUrl] = extracted
                }
            }
        }
    }

    LaunchedEffect(imageUrl) {
        if (imageUrl == null || colorLocked) return@LaunchedEffect
        val cached = bottomColorCache[imageUrl]
        if (cached != null) {
            color = cached
            colorLocked = true
            return@LaunchedEffect
        }
        val request = ImageRequest.Builder(context)
            .data(imageUrl.artworkAt(CARD_ART_PX) ?: imageUrl)
            .size(FULL_BLUR_SOURCE_PX)
            .forPixelAccess()
            .build()
        val result = SingletonImageLoader.get(context).execute(request)
        val bitmap = (result as? SuccessResult)?.image?.toImageBitmap() ?: return@LaunchedEffect
        val extracted = withContext(Dispatchers.Default) {
            extractBottomSolidColor(bitmap)
        }
        if (extracted != FallbackBackdrop) {
            bottomColorCache[imageUrl] = extracted
            if (!colorLocked) {
                color = extracted
                colorLocked = true
            }
        }
    }

    return color
}

/**
 * Backdrop with Apple Music-style slight curved arc blur:
 *
 * 1. Background: Clean solid color picked up from the lowest edge of the album.
 *    No mirrored images behind, no partitions, no vertical lines.
 * 2. Along the slight curve: The artwork blurs out and slowly transitions into the solid color.
 * 3. Below the curve: The clean solid color extends seamlessly behind the controls.
 */
@Composable
fun CurvedBottomEdgeBlurBackdrop(
    image: ImageBitmap?,
    solidColor: Color = FallbackBackdrop,
    modifier: Modifier = Modifier,
    sideYFraction: Float = 0.50f,
    centerYFraction: Float = 0.57f,
    blurRadius: Int = 16,
    effectiveArtH: Float = 0f,
    coverage: Float = CURVED_BLUR_COVERAGE,
) {
    Box(modifier = modifier.fillMaxSize().background(solidColor)) {
        if (image != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val drawWidth = size.width.roundToInt()
                val drawHeight = size.height.roundToInt()
                if (drawWidth <= 0 || drawHeight <= 0) return@Canvas

                drawImage(
                    image = image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(image.width, image.height),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(drawWidth, drawHeight),
                    filterQuality = FilterQuality.Medium,
                )

                // Subtle downward readability scrim so white controls and track titles
                // stay legible over bright covers without darkening the sharp artwork above.
                drawRect(
                    brush = Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.10f),
                        0.55f to Color.Black.copy(alpha = 0.24f),
                        1.0f to Color.Black.copy(alpha = 0.42f),
                    ),
                )
            }
        }
    }
}

/**
 * One pre-blurred bitmap for the full-cover player backdrop.
 *
 * This deliberately does not use Compose's full-screen `Modifier.blur`: that is
 * a live RenderEffect and the first frame which makes it visible can pay for a
 * screen-sized blur while the player is already animating. Instead a 128px copy
 * is decoded from Coil's existing artwork request, blurred once on a worker
 * thread, and cached by URL. Drawing it thereafter is one bilinear image sample.
 * The small decode and CPU pass are staggered by the caller after a song change,
 * so they never land on the track hand-off itself.
 *
 * Hold the last blur until the selected cover is ready. Clear it on failure
 * or when there is no selected cover, rather than retaining a stale bitmap.
 */
@Composable
fun rememberFullArtworkBlurImage(
    imageUrl: String?,
    artPx: Int = CARD_ART_PX,
    prepare: Boolean = true,
): ImageBitmap? = rememberFullArtworkBlurImage(imageUrl, artPx, prepare, seed = null)

@Composable
internal fun rememberFullArtworkBlurImage(
    imageUrl: String?, artPx: Int, prepare: Boolean, seed: ImageBitmap?,
    currentSelectionOnly: Boolean = false,
): ImageBitmap? {
    val context = LocalPlatformContext.current
    var loaded by remember { mutableStateOf(imageUrl to (seed ?: cachedFullArtworkBlur(imageUrl))) }

    LaunchedEffect(imageUrl, artPx, prepare, seed) {
        val image = seed ?: cachedFullArtworkBlur(imageUrl) ?: if (prepare && imageUrl != null) {
            loadFullArtworkBlur(context, imageUrl, artPx)
        } else null
        loaded = imageUrl to image
    }
    return if (currentSelectionOnly && loaded.first != imageUrl) cachedFullArtworkBlur(imageUrl) else loaded.second
}

private suspend fun loadFullArtworkBlur(context: PlatformContext, imageUrl: String, artPx: Int): ImageBitmap? {
    val request = artworkPixelsRequest(context, imageUrl, artPx, FULL_BLUR_SOURCE_PX)
    val result = SingletonImageLoader.get(context).execute(request)
    val bitmap = (result as? SuccessResult)?.image?.toImageBitmap() ?: return null
    val ready = withContext(Dispatchers.Default) { bitmap.boxBlurred(FULL_BLUR_PASSES) }
    fullBlurCache[imageUrl] = ready
    return ready
}

// Read the same artwork URL as the sleeve, so small pixel reads can share its disk entry.
private fun artworkPixelsRequest(
    context: PlatformContext,
    imageUrl: String,
    artPx: Int,
    decodePx: Int,
): ImageRequest = ImageRequest.Builder(context)
    .data(imageUrl.artworkAt(artPx))
    .size(decodePx)
    .forPixelAccess()
    .build()

/** Warms one incoming cover. Call on Main, like the effects that own the URL-keyed ambient caches. */
suspend fun prefetchAutoMixArtwork(
    context: PlatformContext,
    imageUrl: String,
    generation: Long,
    prepareCover: (Long) -> Boolean,
    prepareMiniCover: (Long) -> Boolean,
    isStillArmed: (Long) -> Boolean,
) = coroutineScope {
    if (!isStillArmed(generation) || (!prepareCover(generation) && !prepareMiniCover(generation))) return@coroutineScope
    val loader = SingletonImageLoader.get(context)
    fun wantsCover() = isStillArmed(generation) && prepareCover(generation)
    fun wantsMiniCover() = isStillArmed(generation) && prepareMiniCover(generation)

    // The mini surfaces derive their target from layout constraints. Warm their URL
    // with an inexact row-sized decode, using the same crop scale as AsyncImage.
    val miniJob = launch {
        if (!wantsMiniCover()) return@launch
        val mini = loader.execute(
            ImageRequest.Builder(context)
                .data(imageUrl.artworkAt(ROW_ART_PX))
                .size(ROW_ART_PX)
                .scale(Scale.FILL)
                .precision(Precision.INEXACT)
                .build(),
        )
        if (mini is SuccessResult && wantsMiniCover()) AutoMixPreparedCover.retainMini(generation, mini)
    }

    if (!wantsCover()) return@coroutineScope
    val ambient = PreparedAutoMixAmbient(generation, imageUrl)
    preparedAutoMixAmbient = ambient
    if (meshCache[imageUrl] == null) {
        val request = artworkPixelsRequest(context, imageUrl, ART_PX, MESH_PX)
        val result = loader.execute(request)
        (result as? SuccessResult)?.image?.toImageBitmap()?.let { cacheStillMesh(imageUrl, it) }
    }
    if (!wantsCover()) return@coroutineScope
    ambient.mesh = meshCache[imageUrl]
    if (fullBlurCache[imageUrl] == null) loadFullArtworkBlur(context, imageUrl, ART_PX)
    if (!wantsCover()) return@coroutineScope
    ambient.blur = fullBlurCache[imageUrl]
    val palette = prefetchArtworkPalette(context, imageUrl)
    if (wantsCover()) ambient.palette = palette

    // Coil can give different decode sizes the same key. Leave the full-player
    // cover in that slot when URLs coincide; retain the mini decode separately.
    miniJob.join()
    if (!wantsCover()) return@coroutineScope
    val cover = loader.execute(
        playerCoverRequest(context, imageUrl.artworkAt(ART_PX))
            .scale(Scale.FILL)
            .precision(Precision.INEXACT)
            .build(),
    )
    if (cover is SuccessResult && wantsCover()) AutoMixPreparedCover.retain(generation, cover)
}

/**
 * The phone and tablet player's backdrop — main player, lyrics and queue alike,
 * with Apple Music-style stretched-out U curved edge bleed blur.
 */
@Composable
fun FullArtworkBlurBackdrop(
    image: ImageBitmap?,
    modifier: Modifier = Modifier,
    solidColor: Color = FallbackBackdrop,
) {
    CurvedBottomEdgeBlurBackdrop(
        image = image,
        solidColor = solidColor,
        coverage = CURVED_BLUR_COVERAGE,
        modifier = modifier,
    )
}

@Composable
internal fun FullArtworkBlurBackdrop(
    image: ImageBitmap?,
    modifier: Modifier,
    mix: AutoMixAmbientMix?,
    currentIsOutgoing: Boolean,
    immediate: Boolean = false,
) {
    CurvedBottomEdgeBlurBackdrop(
        image = image,
        solidColor = FallbackBackdrop,
        coverage = CURVED_BLUR_COVERAGE,
        modifier = modifier,
    )
}

private class BlurLayers {
    var target: ImageBitmap? = null
    var layers: List<Pair<ImageBitmap, State<Float>>>? = null
}

/** The ordinary Crossfade behavior, with its visible layers accessible for the AutoMix hold. */
@Composable
private fun FullBlurTrackFade(image: ImageBitmap?, history: BlurLayers, draw: Boolean, immediate: Boolean) {
    val fade = updateTransition(image, label = "preparedBackdropImage")
    val visible = remember { mutableStateListOf(fade.currentState) }
    if (immediate || (fade.currentState == fade.targetState && visible.size > 1)) {
        visible.removeAll { it != fade.targetState }
    }
    if (fade.targetState !in visible) visible.add(fade.targetState)
    val layers = visible.mapNotNull { bitmap ->
        key(bitmap) {
            val alpha = fade.animateFloat(transitionSpec = { if (immediate) snap() else tween(320, easing = FastOutSlowInEasing) }) {
                if (it == bitmap) 1f else 0f
            }
            bitmap?.let {
                if (draw) FullBlurImage(it, Modifier.fillMaxSize().graphicsLayer { this.alpha = if (immediate) 1f else alpha.value })
                it to alpha
            }
        }
    }
    SideEffect {
        if (draw) {
            history.target = image
            history.layers = layers
        }
    }
}

@Composable
private fun FullBlurImage(bitmap: ImageBitmap, modifier: Modifier) {
    val painter = remember(bitmap) { BitmapPainter(bitmap) }
    Image(painter = painter, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
}

/**
 * The mesh for the artwork at [imageUrl], or null until one has been read.
 *
 * [canvasFrame] — a frame grabbed off a playing motion cover — takes over when
 * one arrives, for the reason [rememberArtworkColors] describes: a clip is
 * frequently graded nothing like the still sleeve it replaces. A caller that
 * keeps sending fresh ones gets a backdrop that follows the clip; see
 * [CanvasArtworkPlayer]'s `frameCapturePx` for what one of them costs.
 */
@Composable
fun rememberArtworkMesh(
    imageUrl: String?,
    canvasFrame: ImageBitmap? = null,
    /**
     * The artwork size to read, which should be the one the caller already has
     * on screen. Nothing here survives being averaged into thirty-six cells, so
     * resolution buys nothing and a shared disk-cache entry buys everything —
     * ask for a size no one is showing and the backdrop sits flat until a
     * second copy of the same cover comes over the wire.
     */
    artPx: Int = CARD_ART_PX,
): ArtworkMesh? = rememberArtworkMesh(imageUrl, canvasFrame, artPx, seed = null)

@Composable
internal fun rememberArtworkMesh(
    imageUrl: String?, canvasFrame: ImageBitmap?, artPx: Int, seed: ArtworkMesh?,
    clipKey: Any? = imageUrl,
): ArtworkMesh? {
    val context = LocalPlatformContext.current
    // Keep the old single displayed mesh, but scope clip-derived pixels to the
    // clip owner and let the backdrop fade to fallback when a still is missing.
    var mesh by remember(imageUrl, clipKey) {
        mutableStateOf(seed ?: clipMeshFor(clipKey) ?: imageUrl?.let(meshCache::get))
    }

    LaunchedEffect(imageUrl, artPx, clipKey) {
        if (imageUrl == null || mesh != null) return@LaunchedEffect
        val request = artworkPixelsRequest(context, imageUrl, artPx, MESH_PX)
        // Tried more than once, because this effect is keyed on the artwork and
        // nothing else: a read that fails is not retried by Coil and cannot be
        // re-triggered from here, so one dropped connection used to leave the
        // backdrop flat — no colour behind the player at all — until the track
        // changed. The cover on top of it has the same guard for the same
        // reason; see [NowPlayingScreen]'s artAttempt.
        repeat(MESH_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(MESH_RETRY_DELAY_MS)
            val result = SingletonImageLoader.get(context).execute(request)
            val bitmap = (result as? SuccessResult)?.image?.toImageBitmap()
            if (bitmap != null) {
                val found = cacheStillMesh(imageUrl, bitmap)
                if (found != null) mesh = found
                // A cover that decoded but had no mesh in it — see [meshOf] —
                // is an answer, not a failure. Asking again gets the same one.
                return@LaunchedEffect
            }
        }
    }

    LaunchedEffect(imageUrl, clipKey, canvasFrame) {
        val frame = canvasFrame ?: return@LaunchedEffect
        // Reject frames that are too dark or uniform — they are almost always
        // the first read after a surface recreation, before ExoPlayer has
        // decoded real content.  A frame whose mean luminance is below this
        // threshold is discarded; the last valid mesh is kept instead.
        val found = withContext(Dispatchers.Default) {
            if (isLikelyBlackFrame(frame)) null else meshOf(frame, imageUrl?.hashCode() ?: 0)
        } ?: return@LaunchedEffect
        mesh = found
        if (clipKey != null) lastClipMesh = ClipMesh(clipKey, found)
    }
    return mesh
}

/**
 * The player's backdrop: [mesh] hung from where the artwork stops, and stretched
 * over everything below it.
 *
 * The mesh is anchored rather than centred. Its first row is the sleeve's own
 * bottom edge and it is drawn starting at [seam], so the colour immediately
 * under the artwork is the colour the artwork ended on and there is no join to
 * hide. Above [seam] that first row is simply held — that stretch is behind the
 * artwork, and holding it means the backdrop is continuous everywhere rather
 * than only below the sleeve.
 *
 * ### What this costs
 *
 * One bilinear draw of a 32-texel texture, stretched by the sampler, and no
 * `RenderEffect`. The softness a full-screen blur used to supply is baked into
 * that texture off the main thread when it is read — see [MESH_BLUR_RADIUS] — so
 * nothing here has to be re-blurred as it changes, whatever it is sitting under.
 * A skip or a clip's new colours cost one fade of two such draws, and between
 * them the backdrop is only composited, however the player above it moves.
 *
 * The old backdrop paid a 60Hz blur permanently, for blobs orbiting behind a
 * screen nobody was looking at; see [MeshGradientBackground]'s note.
 */
@Composable
fun ArtworkMeshBackdrop(
    mesh: ArtworkMesh?,
    modifier: Modifier = Modifier,
    /**
     * How far down the surface the artwork's bottom edge sits. Zero for a
     * surface with no artwork over it, which puts the whole mesh on screen. Read
     * in the draw, so a seam that moves costs no composition.
     */
    seam: () -> Dp = { 0.dp },
    /** What the surface is before any artwork has been read. */
    fallback: Color = FallbackBackdrop,
) {
    ArtworkMeshBackdrop(mesh, modifier, seam, fallback, mix = null)
}

@Composable
internal fun ArtworkMeshBackdrop(
    mesh: ArtworkMesh?, modifier: Modifier, seam: () -> Dp, fallback: Color = FallbackBackdrop,
    mix: AutoMixAmbientMix?, currentIsOutgoing: Boolean = false,
    directSelection: Pair<Long, QueueEntryIdentity>? = null, directStill: Boolean = false,
) {
    val reduceAnimation by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()

    // The mesh on screen, and the one fading in over it. Two states rather than
    // one animated value because what crossfades here is a pair of bitmaps, not
    // a pair of colours: the outgoing one has to stay drawable until the fade
    // has finished with it.
    var shown by remember { mutableStateOf(mesh) }
    var incoming by remember { mutableStateOf<ArtworkMesh?>(null) }
    var fadingToFallback by remember { mutableStateOf(false) }
    val fade = remember { Animatable(0f) }
    var appliedDirect by remember { mutableStateOf<Pair<Long, QueueEntryIdentity>?>(null) }
    val immediate = directSelection != null && (appliedDirect != directSelection || directStill)

    LaunchedEffect(mesh, mix, reduceAnimation, directSelection, directStill) {
        if (mix != null) return@LaunchedEffect
        val next = mesh
        if (immediate) {
            shown = next
            incoming = null
            fadingToFallback = false
            appliedDirect = directSelection
            return@LaunchedEffect
        }
        // A change arriving mid-fade. Whatever was fading in has been on screen
        // for a while and is what the new one should fade *from*; leaving it in
        // [incoming] would restart the fade from the mesh before it, and a clip
        // sending frames faster than the fade runs would never settle at all.
        incoming?.let { shown = it }
        incoming = null
        fadingToFallback = false
        val current = shown
        if (next === current) return@LaunchedEffect

        // Nothing to fade *from* on the first read, and nothing to fade at all
        // when the user has asked for less motion.
        if (current == null || reduceAnimation) {
            shown = next
            return@LaunchedEffect
        }
        incoming = next
        fadingToFallback = next == null
        fade.snapTo(0f)
        fade.animateTo(1f, tween(MESH_FADE_MS, easing = FastOutSlowInEasing))
        shown = next
        incoming = null
        fadingToFallback = false
    }

    // If a still/frame fade was running, pin the last drawn blend, not its target.
    val drawn = remember { DrawnMesh() }
    val outgoing = remember(mix) {
        if (mix == null) null else drawn.copy().also {
            if (!it.wasDrawn) it.base = mix.outgoingUrl?.let { url -> clipMeshFor(url) ?: meshCache[url] }
                ?: mesh.takeIf { currentIsOutgoing }
        }
    }
    Canvas(
        modifier = modifier
            .fillMaxSize()
            // A flat opaque floor: the player is a sheet with the app's own pages
            // behind it, and every pixel here has to be opaque.
            .background(fallback),
    ) {
        val seamY = seam().toPx().coerceIn(0f, size.height)
        if (outgoing != null && mix != null) {
            outgoing.base?.let { drawMesh(it, seamY, alpha = outgoing.baseAlpha) }
            outgoing.overlay?.let { drawMesh(it, seamY, alpha = outgoing.alpha) }
            mix.prepared?.mesh?.let { drawMesh(it, seamY, alpha = mix.ease()) }
        } else {
            drawn.wasDrawn = true
            // Paint the selected mesh (or the regular fallback) even before the effect settles.
            drawn.base = if (immediate) mesh else shown
            drawn.baseAlpha = if (!immediate && fadingToFallback) 1f - fade.value else 1f
            drawn.overlay = incoming.takeUnless { immediate }
            drawn.alpha = fade.value
            drawn.base?.let { drawMesh(it, seamY, alpha = drawn.baseAlpha) }
            drawn.overlay?.let { drawMesh(it, seamY, alpha = drawn.alpha) }
        }

        // Enough of a scrim to keep white text off a bright sleeve, and no
        // more. The old backdrop needed a heavier one because it lightened
        // every colour it drew to a fixed band; these are the sleeve's own,
        // and a sleeve that ends dark should leave a dark screen.
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Black.copy(alpha = 0.06f),
                    Color.Black.copy(alpha = 0.30f),
                ),
            ),
        )
    }
}

private data class DrawnMesh(
    var wasDrawn: Boolean = false,
    var base: ArtworkMesh? = null,
    var baseAlpha: Float = 1f,
    var overlay: ArtworkMesh? = null,
    var alpha: Float = 0f,
)

/**
 * Paints [mesh] over the whole surface: the grid from [seamY] down, and its
 * first row held from there up. Two draws of one small texture, both stretched
 * by the sampler.
 */
private fun DrawScope.drawMesh(mesh: ArtworkMesh, seamY: Float, alpha: Float) {
    if (alpha <= 0.001f) return
    val image = mesh.image
    val width = size.width.roundToInt()

    // Above the seam: the sleeve's bottom edge, held. Skipped when the seam is
    // at the very top, where there is no such stretch to fill.
    if (seamY > 0.5f) {
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, 1),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(width, seamY.roundToInt()),
            alpha = alpha,
            filterQuality = FilterQuality.Low,
        )
    }

    // From the seam down: the mesh itself, stretched over whatever is left.
    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(0, seamY.roundToInt()),
        dstSize = IntSize(width, (size.height - seamY).roundToInt()),
        alpha = alpha,
        filterQuality = FilterQuality.Low,
    )
}

/** The shade over the blurred cover, deepening toward the controls at the foot. */
internal val FullArtworkScrim = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.34f),
    0.55f to Color.Black.copy(alpha = 0.48f),
    1f to Color.Black.copy(alpha = 0.64f),
)

/** Drawn only until an artwork has been read — never a colour anyone chose. */
internal val FallbackBackdrop = Color(0xFF121212)

/** A screen cannot recover detail discarded here; small is the performance feature. */
private const val FULL_BLUR_SOURCE_PX = 96
private const val FULL_BLUR_PASSES = 4

/** A handful of recent covers; each entry is only 64 KiB at 128x128 ARGB. */
internal fun cachedFullArtworkBlur(imageUrl: String?): ImageBitmap? = imageUrl?.let(fullBlurCache::get)

private val fullBlurCache = object : LinkedHashMap<String, ImageBitmap>(0, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, ImageBitmap>) = size > 8
}

/** Heavy multi-pass box blur for creamy, atmospheric, gradual backdrop falloff. */
private fun ImageBitmap.boxBlurred(passes: Int = FULL_BLUR_PASSES): ImageBitmap {
    val width = width
    val height = height
    if (width < 2 || height < 2) return this
    val source = argbPixels()
    if (source.isEmpty()) return this
    val target = IntArray(source.size)
    val radius = (minOf(width, height) / 5).coerceAtLeast(8)

    var src = source
    var dst = target
    repeat(passes) {
        boxBlurPass(src, dst, width, height, radius, horizontal = true)
        boxBlurPass(dst, src, width, height, radius, horizontal = false)
    }
    return imageBitmapOf(src, width, height)
}

private fun boxBlurPixels(
    source: IntArray,
    width: Int,
    height: Int,
    radius: Int,
    passes: Int,
): IntArray {
    var src = source
    var dst = IntArray(src.size)
    repeat(passes) {
        boxBlurPass(src, dst, width, height, radius, horizontal = true)
        boxBlurPass(dst, src, width, height, radius, horizontal = false)
    }
    return src
}

private fun boxBlurPass(
    source: IntArray,
    target: IntArray,
    width: Int,
    height: Int,
    radius: Int,
    horizontal: Boolean,
) {
    val major = if (horizontal) width else height
    val minor = if (horizontal) height else width
    val window = radius * 2 + 1
    repeat(minor) { fixed ->
        var alpha = 0
        var red = 0
        var green = 0
        var blue = 0
        fun pixel(at: Int): Int {
            val position = at.coerceIn(0, major - 1)
            return if (horizontal) source[fixed * width + position]
            else source[position * width + fixed]
        }
        for (offset in -radius..radius) {
            val color = pixel(offset)
            alpha += (color ushr 24) and 0xFF
            red += (color shr 16) and 0xFF
            green += (color shr 8) and 0xFF
            blue += color and 0xFF
        }
        repeat(major) { moving ->
            val index = if (horizontal) fixed * width + moving else moving * width + fixed
            val a = (alpha / window).coerceIn(0, 255)
            val r = (red / window).coerceIn(0, 255)
            val g = (green / window).coerceIn(0, 255)
            val b = (blue / window).coerceIn(0, 255)
            target[index] = (a shl 24) or (r shl 16) or (g shl 8) or b
            val leaving = pixel(moving - radius)
            val entering = pixel(moving + radius + 1)
            alpha += ((entering ushr 24) and 0xFF) - ((leaving ushr 24) and 0xFF)
            red += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
            green += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
            blue += (entering and 0xFF) - (leaving and 0xFF)
        }
    }
}

/**
 * Meshes already read, keyed by artwork URL — the artwork at a URL cannot
 * change, so reading it twice buys a decode and a downsample for the same
 * answer. Composition, [rememberArtworkMesh]'s effect and AutoMix prefetch
 * all access it on the main thread, so it needs no lock.
 *
 * Clip frames never land here: the next one for the same URL is a different
 * picture, and there would be a new entry several times a second.
 */
private val meshCache = object : LinkedHashMap<String, ArtworkMesh>(0, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, ArtworkMesh>) = size > MESH_CACHE_ENTRIES
}

private suspend fun cacheStillMesh(imageUrl: String, bitmap: ImageBitmap): ArtworkMesh? {
    val found = withContext(Dispatchers.Default) { meshOf(bitmap, imageUrl.hashCode()) }
    if (found != null) meshCache[imageUrl] = found
    return found
}

/** The mesh of the clip last on screen, kept for when the player comes back to it. */
private class ClipMesh(val key: Any, val mesh: ArtworkMesh)

/** One at a time: a clip's frames only matter for the clip that is playing. */
private var lastClipMesh: ClipMesh? = null

private fun clipMeshFor(key: Any?): ArtworkMesh? =
    lastClipMesh?.takeIf { it.key == key }?.mesh

/** A session's worth of covers, at four kilobytes of texture each. */
private const val MESH_CACHE_ENTRIES = 64

/**
 * What a still sleeve is decoded at before it is averaged down. A whole
 * multiple of [MESH_GRID], so every cell is an equal number of source pixels
 * and no column comes out weighted differently from its neighbour.
 */
private const val MESH_PX = 120

/**
 * How many goes the backdrop's read gets before it is given up on, and how long
 * it waits between them.
 *
 * Matched to the cover's own retry in [NowPlayingScreen] rather than chosen
 * separately: the two read the same URL out of the same cache, so a run of
 * attempts that gave up at a different point from the cover's would be a way for
 * the backdrop and the artwork on top of it to disagree about whether this track
 * has a picture.
 */
private const val MESH_ATTEMPTS = 4

/** @see MESH_ATTEMPTS */
private const val MESH_RETRY_DELAY_MS = 1_500L

/**
 * How many cells across the mesh is.
 *
 * Coarse deliberately — see [ArtworkMesh]. Enough that a cover's layout
 * survives (where the bright part was, how much of the frame it took) and few
 * enough that nothing recognisable does.
 */
private const val MESH_GRID = 6

/**
 * How far the grid is interpolated on the CPU before the GPU stretches it.
 *
 * Bilinear filtering straight off a 6x6 texture is smooth but not *soft*: it is
 * piecewise-linear, and the eye finds the crease at every cell boundary when
 * they are a hundred and eighty pixels apart. Resampling to this first, with a
 * curve that flattens at each control point, leaves creases too small to see
 * before they are magnified. It costs about a thousand lerps, off the main
 * thread, and the control points come through untouched, so the artwork's
 * proportions are exactly what they were.
 */
private const val MESH_TEX = 32

/**
 * The mesh's blur, baked in rather than left to a `RenderEffect`: a full-screen
 * one is paid again on every frame the backdrop changes, and keeps a layer the
 * size of the screen the rest of the time. Three box passes of this radius make
 * a Gaussian of about 1.4 texels, the same softness on a phone as the 32dp live
 * blur this replaces, for the same clamped edges and so the same opaque ones.
 */
private const val MESH_BLUR_RADIUS = 1
private const val MESH_BLUR_PASSES = 3

/**
 * How long the backdrop takes to change colour, whether that is a track skip or
 * the next frame of a clip playing over the sleeve.
 *
 * Long enough to read as the screen changing colour rather than cutting, and
 * comfortably inside the interval a clip's frames arrive on — see
 * `MESH_REFRESH_MS` in `NowPlayingScreen`. It has to finish before the next one
 * lands, or every fade is cut off partway and the backdrop never settles where
 * a frame actually put it.
 */
private const val MESH_FADE_MS = 900

/**
 * The most rows and columns of [source] that are actually looked at.
 *
 * A still sleeve is decoded at [MESH_PX] and falls well under this, but a clip
 * frame is whatever size the caller grabbed it at, and every pixel of it would
 * be read to produce thirty-six averages. Sampling on a stride instead bounds
 * the work at a fixed cost per frame however large the frame is, which is what
 * makes it safe to do this several times a second.
 */
private const val MESH_SAMPLE = 128

/**
 * Averages [source] into the mesh texture, flipped top to bottom and then
 * rotated sideways — see [rotatedBelowSeam] for why the flip alone isn't the
 * finish line.
 *
 * Row 0 of the grid is the sleeve's own bottom edge and later rows run back up
 * into it, which is the order [ArtworkMeshBackdrop] draws down the screen.
 *
 * A mean per cell, not a quantised swatch: what should sit under the artwork is
 * what a blur of the artwork would leave there, and a blur has no opinion about
 * which colour in the frame was the interesting one.
 */
private fun meshOf(source: ImageBitmap, seed: Int): ArtworkMesh? {
    val width = source.width
    val height = source.height
    if (width < 1 || height < 1) return null

    // A cell nothing lands in would come out black, so the grid never asks for
    // more cells than the artwork has pixels.
    val cols = MESH_GRID.coerceAtMost(width)
    val rows = MESH_GRID.coerceAtMost(height)
    val rowStep = (height / MESH_SAMPLE).coerceAtLeast(1)
    val colStep = (width / MESH_SAMPLE).coerceAtLeast(1)

    val cells = rows * cols
    val red = LongArray(cells)
    val green = LongArray(cells)
    val blue = LongArray(cells)
    val count = IntArray(cells)

    // One row at a time, so the scratch buffer is the width of the artwork
    // rather than the whole of it. A clip frame read whole is megabytes.
    val line = IntArray(width)
    var y = 0
    while (y < height) {
        source.readPixels(line, startX = 0, startY = y, width = width, height = 1)
        // Flipped as it is read — see the note above.
        val rowBase = ((height - 1 - y) * rows / height) * cols
        var x = 0
        while (x < width) {
            val cell = rowBase + x * cols / width
            val pixel = line[x]
            red[cell] += (pixel shr 16) and 0xFF
            green[cell] += (pixel shr 8) and 0xFF
            blue[cell] += pixel and 0xFF
            count[cell]++
            x += colStep
        }
        y += rowStep
    }

    val grid = IntArray(cells) { cell ->
        val n = count[cell].coerceAtLeast(1)
        argb((red[cell] / n).toInt(), (green[cell] / n).toInt(), (blue[cell] / n).toInt()).lifted()
    }
    val texels = grid.rotatedBelowSeam(cols, rows, seed).resampled(cols, rows, MESH_TEX)
    val scratch = IntArray(texels.size)
    repeat(MESH_BLUR_PASSES) {
        boxBlurPass(texels, scratch, MESH_TEX, MESH_TEX, MESH_BLUR_RADIUS, horizontal = true)
        boxBlurPass(scratch, texels, MESH_TEX, MESH_TEX, MESH_BLUR_RADIUS, horizontal = false)
    }
    return ArtworkMesh(imageBitmapOf(texels, MESH_TEX, MESH_TEX))
}

/**
 * Every row but row 0 — the seam row [meshOf] keeps literal, for the
 * no-visible-join reason [ArtworkMeshBackdrop] documents — rotated sideways
 * by the same random amount, and mirrored left-to-right half the time.
 *
 * The flip alone reads as a mirror on a cover with any real layout to it: a
 * face, a logo, a horizon line reappears upside down in the same columns it
 * left off in, and that symmetry is what makes the backdrop look like a
 * reflection rather than a colour field. An earlier version broke that by
 * shuffling every cell below the seam independently, which also broke the
 * *mesh*: a photo's neighbouring regions are usually close in colour, and a
 * random per-cell permutation puts wildly different cells next to each other
 * far more often than the photo itself ever did, so the smooth blend between
 * them read as a patchwork of small blended islands — a mosaic — rather than
 * the two or three broad blobs a real mesh gradient has.
 *
 * A cyclic shift doesn't have that problem: every cell keeps the exact
 * neighbours it started with, just carried around the row, so whatever
 * gradient existed between them survives untouched. What moves is *where on
 * screen* that gradient sits — which is exactly enough to stop the column
 * directly under the artwork's face or logo from being the same column that
 * face or logo was in above it, without inventing a single new boundary
 * between colours that weren't adjacent in the source.
 *
 * Seeded rather than re-rolled on every call: the same artwork should land in
 * the same arrangement each time it's read, or a canvas clip refreshing this
 * once a second (see `MESH_REFRESH_MS`) would show its layout visibly turning
 * underneath its own colours instead of just changing hue.
 */
private fun IntArray.rotatedBelowSeam(cols: Int, rows: Int, seed: Int): IntArray {
    if (rows <= 1) return this
    val random = Random(seed)
    val mirror = random.nextBoolean()
    val shift = random.nextInt(cols)
    val out = copyOf()
    for (row in 1 until rows) {
        val base = row * cols
        for (x in 0 until cols) {
            val src = if (mirror) cols - 1 - x else x
            out[base + x] = this[base + (src + shift) % cols]
        }
    }
    return out
}

/**
 * Smoothly resamples a [cols] x [rows] grid of control points up to a
 * [size] x [size] texture — see [MESH_TEX] for why this happens at all.
 */
private fun IntArray.resampled(cols: Int, rows: Int, size: Int): IntArray {
    val out = IntArray(size * size)
    for (ty in 0 until size) {
        val fy = (ty + 0.5f) / size * rows - 0.5f
        val y0 = floor(fy).toInt().coerceIn(0, rows - 1)
        val y1 = (y0 + 1).coerceAtMost(rows - 1)
        val wy = smoothstep(fy - y0)
        for (tx in 0 until size) {
            val fx = (tx + 0.5f) / size * cols - 0.5f
            val x0 = floor(fx).toInt().coerceIn(0, cols - 1)
            val x1 = (x0 + 1).coerceAtMost(cols - 1)
            val wx = smoothstep(fx - x0)
            val top = lerpArgb(this[y0 * cols + x0], this[y0 * cols + x1], wx)
            val bottom = lerpArgb(this[y1 * cols + x0], this[y1 * cols + x1], wx)
            out[ty * size + tx] = lerpArgb(top, bottom, wy)
        }
    }
    return out
}

/** Flat at both ends, so no cell boundary shows up as a crease once magnified. */
private fun smoothstep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

private fun lerpArgb(from: Int, to: Int, t: Float): Int {
    if (t <= 0f) return from
    if (t >= 1f) return to
    fun channel(shift: Int): Int {
        val a = (from shr shift) and 0xFF
        val b = (to shr shift) and 0xFF
        return (a + ((b - a) * t)).roundToInt().coerceIn(0, 255)
    }
    return argb(channel(16), channel(8), channel(0))
}

private fun argb(red: Int, green: Int, blue: Int): Int =
    (0xFF shl 24) or (red shl 16) or (green shl 8) or blue

/**
 * The one liberty taken with the artwork's colours.
 *
 * Saturation is nudged because averaging a block of pixels greys it — the mean
 * of a red stripe and the black around it is a dull maroon, and this puts back
 * roughly what the averaging took, not more. Lightness is only floored, off
 * pure black, which is not so much a colour choice as somewhere for the blur to
 * find an edge. Neither touches the *proportions*, which is the whole point of
 * this backdrop: a sleeve that is mostly black stays mostly black.
 */
private fun Int.lifted(): Int {
    val hsl = FloatArray(3).also { ColorUtils.colorToHSL(this, it) }
    hsl[1] = (hsl[1] * MESH_VIBRANCE).coerceAtMost(1f)
    hsl[2] = hsl[2].coerceAtLeast(MESH_FLOOR)
    return ColorUtils.HSLToColor(hsl)
}

private const val MESH_VIBRANCE = 1.12f
private const val MESH_FLOOR = 0.045f
