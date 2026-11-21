package com.music.bitchord

import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.ui.MainViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class AlbumBlurBackdropTest {

    @Test
    fun `album browseIds are recognized for animated artwork and edge bleed blur`() {
        val albumIds = listOf(
            "MPREb_kX987654321",
            "VLOLAK5uy_kX123456789",
            "OLAK5uy_testRelease",
        )
        for (id in albumIds) {
            val type = MainViewModel.browseTypeOf(id)
            val isAlbum = type == BrowseType.ALBUM ||
                id.startsWith("MPREb") ||
                id.startsWith("VLOLAK") ||
                id.startsWith("OLAK")
            assertTrue("Expected $id to be recognized as album", isAlbum)
        }
    }

    @Test
    fun `stretched out U curve reaches deeper along cosine dip`() {
        val width = 1080f
        val height = 1920f
        val effectiveArtH = 1080f
        val coverage = 0.63f

        val edgeLiftPx = effectiveArtH * 0.13f
        val baseStartPx = effectiveArtH * coverage + 10.0f
        val baseEndPx = minOf(height, effectiveArtH * 1.01f + 10.0f)

        fun evaluateCosineBowl(x: Float): Pair<Float, Float> {
            val nx = (x / width).coerceIn(0f, 1f)
            val u = ((nx - 0.54f) / 0.54f).coerceIn(-1.0f, 1.0f)
            val dip = 0.5f * (1.0f + cos(Math.PI.toFloat() * u))
            val colStartY = maxOf(0.0f, baseStartPx - edgeLiftPx * (1.0f - dip))
            val colEndY = maxOf(colStartY + 8.0f, baseEndPx - edgeLiftPx * 0.85f * (1.0f - dip))
            return colStartY to colEndY
        }

        val (leftStart, leftEnd) = evaluateCosineBowl(0f)
        val (peakStart, peakEnd) = evaluateCosineBowl(width * 0.54f)

        // At peak dip (u = 0), dip is 1.0
        assertEquals(baseStartPx, peakStart, 0.01f)
        assertEquals(baseEndPx, peakEnd, 0.01f)

        // At left edge (u = -1.0), dip is 0.0
        assertEquals(baseStartPx - edgeLiftPx, leftStart, 0.01f)
        assertTrue("Peak must dip deeper than left side", peakStart > leftStart)
        assertTrue("Peak end must dip deeper than left side end", peakEnd > leftEnd)
    }

    @Test
    fun `bottom solid color lightness is normalized for text readability`() {
        val hsl = FloatArray(3)
        // White bricks (like The Wall): high lightness ~0.85
        com.music.bitchord.ui.graphics.ColorUtils.RGBToHSL(220, 220, 220, hsl)
        val tunedLightnessHigh = hsl[2].coerceIn(0.10f, 0.58f)
        assertTrue("High lightness must be toned down for white text contrast", tunedLightnessHigh <= 0.58f)

        // Very dark cover: low lightness ~0.02
        com.music.bitchord.ui.graphics.ColorUtils.RGBToHSL(5, 5, 5, hsl)
        val tunedLightnessLow = hsl[2].coerceIn(0.10f, 0.58f)
        assertTrue("Low lightness must be floored to prevent pure black crush", tunedLightnessLow >= 0.10f)
    }

    @Test
    fun `matchesAlbum correctly matches albums with remastered or deluxe subtitles`() {
        val clip = com.music.bitchord.data.canvas.CanvasArtwork(
            url = "https://example.com/the_wall.m3u8",
            title = "The Wall (Remastered)",
            artist = "Pink Floyd",
            album = "The Wall (Remastered)",
        )
        assertTrue(
            "Expected 'The Wall' by 'Pink Floyd' to match 'The Wall (Remastered)'",
            clip.matchesAlbum("The Wall", "Pink Floyd"),
        )
    }
}
