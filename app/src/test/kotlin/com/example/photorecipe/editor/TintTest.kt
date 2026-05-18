package com.example.photorecipe.editor

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class TintTest {

    private val tol = 1e-5f

    private fun apply(r: Float, g: Float, b: Float, tint: Float): FloatArray =
        applyTint(r, g, b, tint)

    // ── tint == 0: 모든 픽셀 변화 없음 ──────────────────────────────

    @Test
    fun `zero tint preserves any pixel`() {
        assertArrayEquals(floatArrayOf(0.5f, 0.5f, 0.5f), apply(0.5f, 0.5f, 0.5f, 0f), tol)
        assertArrayEquals(floatArrayOf(0.1f, 0.7f, 0.3f), apply(0.1f, 0.7f, 0.3f, 0f), tol)
    }

    // ── fVar = 0 경계 (white / black) ──────────────────────────────

    @Test
    fun `white pixel has fVar zero so any tint is no-op`() {
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), apply(1f, 1f, 1f, 100f), tol)
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), apply(1f, 1f, 1f, -100f), tol)
    }

    @Test
    fun `black pixel stays black for any tint`() {
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), apply(0f, 0f, 0f, 100f), tol)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), apply(0f, 0f, 0f, -100f), tol)
    }

    // ── tint > 0 on mid gray (L=0.5, L >= 51/255) ──────────────────
    // L = 0.5 → lower branch → fVar = (1.5*0.5)^2 = 0.5625
    // tint=+50: R*1.09, B*1.095625, G unchanged

    @Test
    fun `positive tint on mid gray boosts R and B but not G`() {
        val (r, g, b) = apply(0.5f, 0.5f, 0.5f, 50f)
        assertArrayEquals(floatArrayOf(0.545f, 0.5f, 0.5478125f), floatArrayOf(r, g, b), tol)
    }

    // ── tint < 0 on mid gray: G boosted, R/B unchanged ─────────────

    @Test
    fun `negative tint on mid gray boosts G only`() {
        val (r, g, b) = apply(0.5f, 0.5f, 0.5f, -50f)
        // G * (1 + 0.5625 * 0.0034 * 50) = 0.5 * 1.095625 = 0.5478125
        assertArrayEquals(floatArrayOf(0.5f, 0.5478125f, 0.5f), floatArrayOf(r, g, b), tol)
    }

    // ── tint > 0 on dark pixel (L < 51/255 = 0.2): G also nudged ───
    // R=G=B=0.1 → L=0.1, fVar = (1.5*0.1)^2 = 0.0225

    @Test
    fun `positive tint on dark pixel also nudges G`() {
        val (r, g, b) = apply(0.1f, 0.1f, 0.1f, 50f)
        // R * (1 + 0.0225 * 0.0032 * 50) = 0.1 * 1.0036  = 0.10036
        // B * (1 + 0.0225 * 0.0034 * 50) = 0.1 * 1.003825 = 0.1003825
        // G * (1 + 0.0225 * 0.0005 * 50) = 0.1 * 1.0005625 = 0.10005625
        assertArrayEquals(floatArrayOf(0.10036f, 0.10005625f, 0.1003825f), floatArrayOf(r, g, b), tol)
    }

    // ── upper branch (L >= 128/255) ────────────────────────────────
    // R=1.0, G=B=0.5 → L = 0.2126 + 0.5*(0.7152 + 0.0722) = 0.60630
    // fVar = (1.5 * (1 - 0.60630))^2 = (1.5 * 0.39370)^2 = 0.59055^2 = 0.34875

    @Test
    fun `clamps when boost would exceed 1`() {
        val (r, g, b) = apply(1f, 0.5f, 0.5f, 100f)
        // newR = 1 * (1 + 0.34875 * 0.0032 * 100) ≈ 1.1116 → clamped to 1
        // newB = 0.5 * (1 + 0.34875 * 0.0034 * 100) = 0.5 * 1.118575 = 0.5592875
        // G unchanged (L > 51/255)
        assertArrayEquals(floatArrayOf(1f, 0.5f, 0.5592875f), floatArrayOf(r, g, b), 1e-4f)
    }

    // ── clamping of tint UI value ──────────────────────────────────

    @Test
    fun `tint above plus 100 clamps to plus 100`() {
        assertArrayEquals(apply(0.5f, 0.5f, 0.5f, 100f), apply(0.5f, 0.5f, 0.5f, 250f), 0f)
    }

    @Test
    fun `tint below minus 100 clamps to minus 100`() {
        assertArrayEquals(apply(0.5f, 0.5f, 0.5f, -100f), apply(0.5f, 0.5f, 0.5f, -250f), 0f)
    }
}
