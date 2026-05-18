package com.example.photorecipe.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimationTest {

    private val tol = 1e-5f

    // ── tone factor ────────────────────────────────────────────────

    @Test
    fun `tone factor 0 at start`() {
        assertEquals(0f, toneAnimationFactor(0f), tol)
    }

    @Test
    fun `tone factor 1 at the split point and beyond`() {
        assertEquals(1f, toneAnimationFactor(0.5f), tol)
        assertEquals(1f, toneAnimationFactor(0.75f), tol)
        assertEquals(1f, toneAnimationFactor(1f), tol)
    }

    @Test
    fun `tone factor at quarter of phase is smoothstep 0_25`() {
        // t=0.125, split=0.5 → toneRaw=0.25
        // smoothstep(0.25) = 0.25 * 0.25 * (3 - 2*0.25) = 0.0625 * 2.5 = 0.15625
        assertEquals(0.15625f, toneAnimationFactor(0.125f), tol)
    }

    @Test
    fun `tone factor clamps below 0 and above 1`() {
        assertEquals(0f, toneAnimationFactor(-0.5f), tol)
        assertEquals(1f, toneAnimationFactor(1.5f), tol)
    }

    @Test
    fun `tone factor monotonically non-decreasing`() {
        var prev = 0f
        for (i in 0..100) {
            val v = toneAnimationFactor(i / 100f)
            assertTrue("tone at $i (=$v) should be ≥ prev ($prev)", v >= prev - 1e-6f)
            prev = v
        }
    }

    // ── color factor ───────────────────────────────────────────────

    @Test
    fun `color factor 0 before the split`() {
        assertEquals(0f, colorAnimationFactor(0f), tol)
        assertEquals(0f, colorAnimationFactor(0.25f), tol)
        assertEquals(0f, colorAnimationFactor(0.5f), tol)
    }

    @Test
    fun `color factor 1 at end`() {
        assertEquals(1f, colorAnimationFactor(1f), tol)
    }

    @Test
    fun `color factor at quarter of its phase is smoothstep 0_25`() {
        // split=0.5, t=0.625 → colorRaw = (0.625 - 0.5) / 0.5 = 0.25
        assertEquals(0.15625f, colorAnimationFactor(0.625f), tol)
    }

    @Test
    fun `color factor monotonically non-decreasing`() {
        var prev = 0f
        for (i in 0..100) {
            val v = colorAnimationFactor(i / 100f)
            assertTrue("color at $i (=$v) should be ≥ prev ($prev)", v >= prev - 1e-6f)
            prev = v
        }
    }

    // ── custom split ───────────────────────────────────────────────

    @Test
    fun `custom split shifts the boundary`() {
        // split=0.3 — tone phase ends earlier, color phase is longer
        assertEquals(1f, toneAnimationFactor(0.3f, split = 0.3f), tol)
        assertEquals(0f, colorAnimationFactor(0.3f, split = 0.3f), tol)
        // halfway through color phase: t = 0.3 + 0.5*0.7 = 0.65
        assertEquals(0.5f, colorAnimationFactor(0.65f, split = 0.3f), tol)
    }
}
