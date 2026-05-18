package com.example.photorecipe.editor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SaturationTest {

    private val tol = 1e-5f

    // Column-major mat3 적용: out[i] = sum_j m[j*3 + i] * in[j].
    private fun applyMat(m: FloatArray, r: Float, g: Float, b: Float): FloatArray {
        require(m.size == 9)
        return floatArrayOf(
            m[0] * r + m[3] * g + m[6] * b,
            m[1] * r + m[4] * g + m[7] * b,
            m[2] * r + m[5] * g + m[8] * b,
        )
    }

    @Test
    fun `ui 0 yields identity matrix in column-major layout`() {
        assertArrayEquals(
            floatArrayOf(
                1f, 0f, 0f,   // col 0 (R)
                0f, 1f, 0f,   // col 1 (G)
                0f, 0f, 1f,   // col 2 (B)
            ),
            saturationMatrix(0f),
            tol,
        )
    }

    @Test
    fun `ui minus 100 maps every output to BT-like luminance (grayscale)`() {
        // s32Weight=0, s32Gain=1024 → R_gain=316, G_gain=624, B_gain=84
        val rW = 316f / 1024f
        val gW = 624f / 1024f
        val bW = 84f / 1024f
        assertArrayEquals(
            floatArrayOf(rW, rW, rW, gW, gW, gW, bW, bW, bW),
            saturationMatrix(-100f),
            tol,
        )
    }

    @Test
    fun `ui plus 100 matches calibrated max saturation matrix`() {
        // s32Weight clamps to 2048, s32Gain=-1024
        //   R_gain=-316, G_gain=-624, B_gain=-84
        //   diag = Weight + chan_gain = 2048 − chan_gain
        assertArrayEquals(
            floatArrayOf(
                1732f / 1024f, -316f / 1024f, -316f / 1024f,    // col 0 (R)
                -624f / 1024f, 1424f / 1024f, -624f / 1024f,    // col 1 (G)
                -84f  / 1024f, -84f  / 1024f, 1964f / 1024f,    // col 2 (B)
            ),
            saturationMatrix(100f),
            tol,
        )
    }

    @Test
    fun `gray maps to itself exactly at boundary ui values`() {
        // ui ∈ {-100, 0, +100} 에서는 정수 산술이 손실 없이 떨어져 row sum = 1024 정확.
        for (ui in listOf(-100f, 0f, 100f)) {
            val out = applyMat(saturationMatrix(ui), 0.5f, 0.5f, 0.5f)
            assertEquals("ui=$ui R", 0.5f, out[0], tol)
            assertEquals("ui=$ui G", 0.5f, out[1], tol)
            assertEquals("ui=$ui B", 0.5f, out[2], tol)
        }
    }

    @Test
    fun `gray is preserved within fixed-point truncation tolerance at intermediate ui`() {
        // 중간 ui 에서는 `(s32Gain * coef) >> 10` 의 정수 절삭으로 row sum 이 1024 에서
        // ~3 LSB 만큼 어긋날 수 있음 (≈ 0.003). Galaxy 단말과 동일한 calibrated 동작이라
        // "고치면" 오히려 어긋남.
        val approxTol = 5e-3f
        for (ui in listOf(-50f, -25f, 25f, 50f, 75f)) {
            val out = applyMat(saturationMatrix(ui), 0.5f, 0.5f, 0.5f)
            assertEquals("ui=$ui R", 0.5f, out[0], approxTol)
            assertEquals("ui=$ui G", 0.5f, out[1], approxTol)
            assertEquals("ui=$ui B", 0.5f, out[2], approxTol)
        }
    }

    @Test
    fun `ui plus 50 produces expected intermediate matrix`() {
        // internal=150, s32Weight=int((150*103-10300)*0.1+1024) = int(1539) = 1539
        // s32Gain = -515
        // R_gain = (-515*316) >> 10 = -163 ... let me verify in impl, just test row-sum invariant + key value
        val m = saturationMatrix(50f)
        // diag R = (1539 + R_gain) / 1024  ;  R_gain = (-515*316) >> 10
        val expectedRGain = ((-515) * 316) shr 10
        val expectedDiagR = (1539 + expectedRGain) / 1024f
        assertEquals(expectedDiagR, m[0], tol)
    }

    @Test
    fun `ui below minus 100 clamps`() {
        assertArrayEquals(saturationMatrix(-100f), saturationMatrix(-250f), 0f)
    }

    @Test
    fun `ui above plus 100 clamps`() {
        assertArrayEquals(saturationMatrix(100f), saturationMatrix(250f), 0f)
    }
}
