package com.example.photorecipe.editor

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class TemperatureTest {

    private val tol = 1e-4f

    @Test
    fun `ui 0 is identity multiplier`() {
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), wbMultipliers(0f), tol)
    }

    @Test
    fun `ui minus 100 matches calibrated values`() {
        // From galaxy_editor_python.py:
        //   ui=-100 → wb_r=1.0000, wb_g=1.6718, wb_b=4.7097
        assertArrayEquals(
            floatArrayOf(1.0000f, 1.6718f, 4.7097f),
            wbMultipliers(-100f),
            tol,
        )
    }

    @Test
    fun `ui plus 100 matches calibrated values`() {
        // ui=+100 → wb_r=1.2466, wb_g=1.1603, wb_b=1.0000
        assertArrayEquals(
            floatArrayOf(1.2466f, 1.1603f, 1.0000f),
            wbMultipliers(100f),
            tol,
        )
    }

    @Test
    fun `ui plus 50 matches calibrated table value`() {
        // ui=+50 (table knot) → 1.1343, 1.1000, 1.0000
        assertArrayEquals(
            floatArrayOf(1.1343f, 1.1000f, 1.0000f),
            wbMultipliers(50f),
            tol,
        )
    }

    @Test
    fun `ui below minus 100 clamps`() {
        assertArrayEquals(wbMultipliers(-100f), wbMultipliers(-150f), 0f)
    }

    @Test
    fun `ui above plus 100 clamps`() {
        assertArrayEquals(wbMultipliers(100f), wbMultipliers(250f), 0f)
    }

    @Test
    fun `mid-knot interpolates linearly between table values`() {
        // Halfway between ui=+50 and ui=+75:
        //   wb_r: (1.1343 + 1.1985) / 2 = 1.1664
        //   wb_g: (1.1000 + 1.1295) / 2 = 1.11475
        //   wb_b: (1.0000 + 1.0000) / 2 = 1.0000
        assertArrayEquals(
            floatArrayOf(1.1664f, 1.11475f, 1.0000f),
            wbMultipliers(62.5f),
            tol,
        )
    }
}
