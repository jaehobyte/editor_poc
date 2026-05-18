package com.example.photorecipe.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

class ColorTuningTest {

    private val tol = 1e-4f

    // ── LUT pack accessors ──────────────────────────────────────────
    private fun dH(lut: FloatArray, hue: Int): Float = lut[hue * 4 + 0]
    private fun dS(lut: FloatArray, hue: Int): Float = lut[hue * 4 + 1]
    private fun dL(lut: FloatArray, hue: Int): Float = lut[hue * 4 + 2]

    private fun zeroParams() = FloatArray(21)
    private fun paramsWith(idx: Int, value: Float) = FloatArray(21).apply { this[idx] = value }

    // ── Size / identity ─────────────────────────────────────────────

    @Test
    fun `LUT has 361 entries packed RGBA`() {
        val lut = buildColorTuningLut(zeroParams())
        assertEquals(361 * 4, lut.size)
    }

    @Test
    fun `zero params yields all-zero LUT`() {
        val lut = buildColorTuningLut(zeroParams())
        for (v in lut) assertEquals(0f, v, 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `wrong-size params throws`() {
        buildColorTuningLut(FloatArray(20))
    }

    // ── Red hue shift: peaks at hue 0 ────────────────────────────────

    @Test
    fun `red hue plus 100 peaks at hue 0 with 45 degrees`() {
        // Params layout: [Red.H, Red.S, Red.L, Orange.H, ...]
        val lut = buildColorTuningLut(paramsWith(0, 100f))
        // h_val = 100 * 0.450 = 45 ; w(d=0) = 1
        assertEquals(45f, dH(lut, 0), tol)
        // No saturation / luminance change
        assertEquals(0f, dS(lut, 0), tol)
        assertEquals(0f, dL(lut, 0), tol)
    }

    @Test
    fun `red hue gaussian decays with sigma 22_2 degrees`() {
        val lut = buildColorTuningLut(paramsWith(0, 100f))
        // At d=22 degrees: w = exp(-22^2 / (2*22.2^2)) ≈ 0.6225
        val expected22 = 45f * exp(-(22f * 22f) / (2f * 22.2f * 22.2f).toDouble()).toFloat()
        assertEquals(expected22, dH(lut, 22), tol)
        // At d=180 (opposite side): weight ≈ 0
        assertTrue("dH at hue 180 should be ~0, was ${dH(lut, 180)}", dH(lut, 180) < 1e-10f)
    }

    @Test
    fun `red hue wraps around the circle - hue 358 close to red center`() {
        val lut = buildColorTuningLut(paramsWith(0, 100f))
        // d for hue=358, center=0 → circular distance = 2
        // w = exp(-4 / 2*22.2^2) ≈ 0.9960
        val expected358 = 45f * exp(-4.0 / (2.0 * 22.2 * 22.2)).toFloat()
        assertEquals(expected358, dH(lut, 358), tol)
    }

    // ── Saturation / Luminance gains ────────────────────────────────

    @Test
    fun `red sat plus 100 yields 0_582 at hue 0`() {
        // s_val = 100 * 0.582 / 100 = 0.582
        val lut = buildColorTuningLut(paramsWith(1, 100f))
        assertEquals(0.582f, dS(lut, 0), tol)
        assertEquals(0f, dH(lut, 0), tol)
        assertEquals(0f, dL(lut, 0), tol)
    }

    @Test
    fun `red lum plus 100 yields 0_212 at hue 0`() {
        // l_val = 100 * 0.212 / 100 = 0.212
        val lut = buildColorTuningLut(paramsWith(2, 100f))
        assertEquals(0.212f, dL(lut, 0), tol)
    }

    // ── Multi-color accumulation ────────────────────────────────────

    @Test
    fun `red and orange hue shifts both contribute at hue 20`() {
        // Red center=0, Orange center=40, halfway is hue=20
        val p = FloatArray(21).apply {
            this[0] = 100f   // Red.H
            this[3] = 100f   // Orange.H
        }
        val lut = buildColorTuningLut(p)
        // At hue 20: d=20 from red, d=20 from orange — same weight from each
        val w = exp(-(20f * 20f) / (2f * 22.2f * 22.2f).toDouble()).toFloat()
        val expected = 45f * w + 45f * w
        assertEquals(expected, dH(lut, 20), tol)
    }

    // ── Center hue value at each of the 7 colors ────────────────────

    @Test
    fun `each color peaks at its own center hue`() {
        // Centers: Red=0, Orange=40, Yellow=60, Green=120, Blue=180, Navy=240, Purple=300
        val centers = intArrayOf(0, 40, 60, 120, 180, 240, 300)
        for ((idx, center) in centers.withIndex()) {
            val p = paramsWith(idx * 3, 100f) // hue param for color `idx`
            val lut = buildColorTuningLut(p)
            assertEquals(
                "Color $idx center=$center should have dH=45 at hue=$center",
                45f, dH(lut, center), tol,
            )
        }
    }
}
