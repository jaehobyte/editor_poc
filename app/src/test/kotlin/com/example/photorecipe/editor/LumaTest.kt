package com.example.photorecipe.editor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LumaTest {

    private val tol = 1e-4f

    // ════════════════════════════════════════════════════════════════
    //  YCbCr conversion
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `gray maps to Y equal to gray with Cb Cr equal zero`() {
        val ycc = rgbToYCbCr(0.5f, 0.5f, 0.5f)
        assertArrayEquals(floatArrayOf(0.5f, 0f, 0f), ycc, tol)
    }

    @Test
    fun `pure red has known YCbCr values`() {
        val (y, cb, cr) = rgbToYCbCr(1f, 0f, 0f)
        assertEquals(77f / 256f, y, tol)
        assertEquals(-43f / 256f, cb, tol)
        assertEquals(128f / 256f, cr, tol)
    }

    @Test
    fun `RGB to YCbCr to RGB round trip within fixed-point precision`() {
        // BT.601 256-quantized coeffs lose ~0.2% precision, but values
        // still fall in [0, 1] after clamp.
        val samples = listOf(
            floatArrayOf(0f, 0f, 0f),
            floatArrayOf(1f, 1f, 1f),
            floatArrayOf(0.3f, 0.6f, 0.2f),
            floatArrayOf(0.8f, 0.1f, 0.5f),
        )
        for (rgb in samples) {
            val (y, cb, cr) = rgbToYCbCr(rgb[0], rgb[1], rgb[2])
            val back = yCbCrToRgb(y, cb, cr)
            assertArrayEquals(rgb, back, 5e-3f)
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Brightness
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `brightness ui 0 is no-op at any y`() {
        for (y in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            assertEquals(y, applyBrightness(y, 0f), tol)
        }
    }

    @Test
    fun `brightness plus 100 lower branch at y 0_4`() {
        // (0.7999*100 + 127) * 0.4 / 127 = 206.99 * 0.4 / 127
        val expected = (0.7999f * 100f + 127f) * 0.4f / 127f
        assertEquals(expected, applyBrightness(0.4f, 100f), tol)
    }

    @Test
    fun `brightness plus 100 upper branch at y 0_6`() {
        // 0.7999*100 * (1 - 0.6) / 127 + 0.6
        val expected = 0.7999f * 100f * (1f - 0.6f) / 127f + 0.6f
        assertEquals(expected, applyBrightness(0.6f, 100f), tol)
    }

    @Test
    fun `brightness minus 100 darkens lower branch`() {
        val expected = (0.7999f * -100f + 127f) * 0.4f / 127f
        assertEquals(expected, applyBrightness(0.4f, -100f), tol)
    }

    @Test
    fun `brightness boundary pixels do not move`() {
        // y=0 lower branch → 0 ; y=1 upper branch → 1 regardless of ui sign
        assertEquals(0f, applyBrightness(0f, 100f), tol)
        assertEquals(0f, applyBrightness(0f, -100f), tol)
        assertEquals(1f, applyBrightness(1f, 100f), tol)
        assertEquals(1f, applyBrightness(1f, -100f), tol)
    }

    @Test
    fun `brightness ui clamps`() {
        assertEquals(applyBrightness(0.5f, 100f), applyBrightness(0.5f, 250f), 0f)
        assertEquals(applyBrightness(0.5f, -100f), applyBrightness(0.5f, -250f), 0f)
    }

    // ════════════════════════════════════════════════════════════════
    //  Exposure
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `exposure ui 0 is no-op`() {
        assertEquals(0.5f, applyExposure(0.5f, 0f), tol)
    }

    @Test
    fun `exposure plus 100 adds 100x100 over 25500`() {
        // y + 100*100 / 25500
        assertEquals(0.5f + 10000f / 25500f, applyExposure(0.5f, 100f), tol)
    }

    @Test
    fun `exposure is quadratic — plus 50 adds 50x50 over 25500`() {
        assertEquals(0.5f + 2500f / 25500f, applyExposure(0.5f, 50f), tol)
    }

    @Test
    fun `exposure minus 100 clamps to 0 on darks`() {
        assertEquals(0f, applyExposure(0.1f, -100f), tol)
    }

    // ════════════════════════════════════════════════════════════════
    //  Highlights
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `highlights ui 0 is no-op`() {
        assertEquals(0.5f, applyHighlights(0.5f, 0f), tol)
    }

    @Test
    fun `highlights at y 0 unchanged regardless of ui`() {
        // y multiplier → 0 at y=0
        assertEquals(0f, applyHighlights(0f, 100f), tol)
        assertEquals(0f, applyHighlights(0f, -100f), tol)
    }

    @Test
    fun `highlights plus 100 at y 0_5 matches formula`() {
        // y + 0.00276 * 100 * 0.5 * (1.275*0.5 - 0.4978) = 0.5 + 0.138 * 0.1397
        val expected = 0.5f + 0.00276f * 100f * 0.5f * (1.275f * 0.5f - 0.4978f)
        assertEquals(expected, applyHighlights(0.5f, 100f), tol)
    }

    @Test
    fun `highlights plus 100 at y 1 clamps to 1`() {
        // Formula gives 1 + 0.276 * 0.7772 ≈ 1.2145 → clamp 1
        assertEquals(1f, applyHighlights(1f, 100f), tol)
    }

    // ════════════════════════════════════════════════════════════════
    //  Shadows
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `shadows ui 0 is no-op`() {
        assertEquals(0.5f, applyShadows(0.5f, 0f), tol)
    }

    @Test
    fun `negative shadows leaves bright pixels unchanged`() {
        // y > 0.625098 → masked out
        assertEquals(0.7f, applyShadows(0.7f, -100f), tol)
        assertEquals(0.9f, applyShadows(0.9f, -100f), tol)
    }

    @Test
    fun `negative shadows darkens dark mid pixels`() {
        // y=0.4 ≤ 0.625098 → y + 0.00301*(0.625098-0.4)*-100
        val expected = 0.4f + 0.00301f * (159.4f / 255f - 0.4f) * -100f
        assertEquals(expected, applyShadows(0.4f, -100f), tol)
    }

    @Test
    fun `positive shadows plateau region adds 0_28 over 255 times ui`() {
        // y=0.4 ≤ 0.50196 → plateau
        val expected = 0.4f + 0.28f * 100f / 255f
        assertEquals(expected, applyShadows(0.4f, 100f), tol)
    }

    @Test
    fun `positive shadows linear region attenuates near white`() {
        // y=0.6 > 0.50196 → y + 0.0022*(1-y)*ui
        val expected = 0.6f + 0.0022f * (1f - 0.6f) * 100f
        assertEquals(expected, applyShadows(0.6f, 100f), tol)
    }

    @Test
    fun `shadows ui clamps`() {
        assertEquals(applyShadows(0.4f, 100f), applyShadows(0.4f, 250f), 0f)
        assertEquals(applyShadows(0.4f, -100f), applyShadows(0.4f, -250f), 0f)
    }
}
