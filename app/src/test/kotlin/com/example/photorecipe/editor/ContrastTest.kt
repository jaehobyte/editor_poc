package com.example.photorecipe.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class ContrastTest {

    private val tol = 1e-5f

    // ── contrastCurve ───────────────────────────────────────────────

    @Test
    fun `ui 0 yields identity curve 1_0`() {
        assertEquals(1.0f, contrastCurve(0f), tol)
    }

    @Test
    fun `ui plus 100 yields max curve`() {
        // internal = 200, fv = 500/400 = 1.25, curve = 1.5625
        assertEquals(1.5625f, contrastCurve(100f), tol)
    }

    @Test
    fun `ui minus 100 yields min curve`() {
        // internal = 0, fv = 300/400 = 0.75, curve = 0.5625
        assertEquals(0.5625f, contrastCurve(-100f), tol)
    }

    @Test
    fun `ui plus 50 matches formula`() {
        // internal = 150, fv = 450/400 = 1.125, curve = 1.265625
        assertEquals(1.265625f, contrastCurve(50f), tol)
    }

    @Test
    fun `ui below minus 100 clamps`() {
        assertEquals(contrastCurve(-100f), contrastCurve(-300f), 0f)
    }

    @Test
    fun `ui above plus 100 clamps`() {
        assertEquals(contrastCurve(100f), contrastCurve(300f), 0f)
    }

    // ── applyContrast (in [0,1] domain) ─────────────────────────────

    @Test
    fun `midpoint is preserved at any curve`() {
        assertEquals(0.5f, applyContrast(0.5f, 1.0f), tol)
        assertEquals(0.5f, applyContrast(0.5f, 1.5625f), tol)
        assertEquals(0.5f, applyContrast(0.5f, 0.5625f), tol)
    }

    @Test
    fun `identity curve is no-op`() {
        assertEquals(0.0f, applyContrast(0.0f, 1.0f), tol)
        assertEquals(0.25f, applyContrast(0.25f, 1.0f), tol)
        assertEquals(0.75f, applyContrast(0.75f, 1.0f), tol)
        assertEquals(1.0f, applyContrast(1.0f, 1.0f), tol)
    }

    @Test
    fun `high curve pushes values away from midpoint and clamps`() {
        // curve=1.5625, x=0.0  → 0.5 + 1.5625 * (-0.5) = -0.28125 → clamp 0
        assertEquals(0.0f, applyContrast(0.0f, 1.5625f), tol)
        // curve=1.5625, x=1.0  → 0.5 + 1.5625 * (0.5) = 1.28125 → clamp 1
        assertEquals(1.0f, applyContrast(1.0f, 1.5625f), tol)
        // curve=1.5625, x=0.75 → 0.5 + 1.5625 * 0.25 = 0.890625
        assertEquals(0.890625f, applyContrast(0.75f, 1.5625f), tol)
    }

    @Test
    fun `low curve squashes values toward midpoint`() {
        // curve=0.5625, x=0.0  → 0.5 + 0.5625 * (-0.5) = 0.21875
        assertEquals(0.21875f, applyContrast(0.0f, 0.5625f), tol)
        // curve=0.5625, x=1.0  → 0.5 + 0.5625 * 0.5 = 0.78125
        assertEquals(0.78125f, applyContrast(1.0f, 0.5625f), tol)
    }
}
