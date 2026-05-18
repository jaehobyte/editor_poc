package com.example.photorecipe.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class InterpTest {

    private val xs = floatArrayOf(0f, 10f, 20f)
    private val ys = floatArrayOf(0f, 5f, 15f)

    @Test
    fun `at known point returns exact y`() {
        assertEquals(0f, interp(0f, xs, ys), 0f)
        assertEquals(5f, interp(10f, xs, ys), 0f)
        assertEquals(15f, interp(20f, xs, ys), 0f)
    }

    @Test
    fun `midpoint between known points is linearly interpolated`() {
        assertEquals(2.5f, interp(5f, xs, ys), 1e-6f)
        assertEquals(10f, interp(15f, xs, ys), 1e-6f)
    }

    @Test
    fun `quarter and three quarter points`() {
        assertEquals(1.25f, interp(2.5f, xs, ys), 1e-6f)
        assertEquals(12.5f, interp(17.5f, xs, ys), 1e-6f)
    }

    @Test
    fun `below first knot clamps to first y`() {
        assertEquals(0f, interp(-5f, xs, ys), 0f)
        assertEquals(0f, interp(-1000f, xs, ys), 0f)
    }

    @Test
    fun `above last knot clamps to last y`() {
        assertEquals(15f, interp(25f, xs, ys), 0f)
        assertEquals(15f, interp(1000f, xs, ys), 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `mismatched array lengths throw`() {
        interp(0f, floatArrayOf(0f, 1f), floatArrayOf(0f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty arrays throw`() {
        interp(0f, floatArrayOf(), floatArrayOf())
    }
}
