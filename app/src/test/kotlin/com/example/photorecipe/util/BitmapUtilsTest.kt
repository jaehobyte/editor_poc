package com.example.photorecipe.util

import org.junit.Assert.assertEquals
import org.junit.Test

class BitmapUtilsTest {

    @Test
    fun `dimensions within maxDim are returned unchanged`() {
        assertEquals(1024 to 768, scaledDimensions(1024, 768, 2048))
    }

    @Test
    fun `exactly at maxDim is returned unchanged`() {
        assertEquals(2048 to 1024, scaledDimensions(2048, 1024, 2048))
    }

    @Test
    fun `wider than tall scales width to maxDim and height proportionally`() {
        assertEquals(2048 to 1024, scaledDimensions(4000, 2000, 2048))
    }

    @Test
    fun `taller than wide scales height to maxDim and width proportionally`() {
        assertEquals(1024 to 2048, scaledDimensions(2000, 4000, 2048))
    }

    @Test
    fun `square exceeding maxDim becomes maxDim x maxDim`() {
        assertEquals(2048 to 2048, scaledDimensions(4096, 4096, 2048))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero width throws`() {
        scaledDimensions(0, 100, 2048)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative height throws`() {
        scaledDimensions(100, -1, 2048)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non-positive maxDim throws`() {
        scaledDimensions(100, 100, 0)
    }
}
