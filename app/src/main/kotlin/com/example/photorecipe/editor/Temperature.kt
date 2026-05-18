package com.example.photorecipe.editor

/**
 * 실제 Galaxy 단말에서 측정한 8-point WB 캘리브레이션 (galaxy_editor_python.py).
 * ui = 0 일 때 곱셈 = (1, 1, 1) identity.
 *
 * !! DO NOT MODIFY — 변경 시 Galaxy 단말 보정 결과와 어긋남.
 */
private val WB_UI = floatArrayOf(-100f, -75f, -50f, -25f, 0f, 25f, 50f, 75f, 100f)
private val WB_R  = floatArrayOf(1.0000f, 1.0000f, 1.0000f, 1.0000f, 1.0000f, 1.0631f, 1.1343f, 1.1985f, 1.2466f)
private val WB_G  = floatArrayOf(1.6718f, 1.3373f, 1.1709f, 1.0812f, 1.0000f, 1.0588f, 1.1000f, 1.1295f, 1.1603f)
private val WB_B  = floatArrayOf(4.7097f, 2.0169f, 1.3960f, 1.1550f, 1.0000f, 1.0000f, 1.0000f, 1.0000f, 1.0000f)

/**
 * UI 값 [-100, 100] → (wbR, wbG, wbB) 채널 곱셈 계수.
 * Temperature 효과: 각 픽셀의 RGB 채널을 이 3-element 벡터로 element-wise 곱.
 */
fun wbMultipliers(ui: Float): FloatArray {
    val u = ui.coerceIn(-100f, 100f)
    return floatArrayOf(
        interp(u, WB_UI, WB_R),
        interp(u, WB_UI, WB_G),
        interp(u, WB_UI, WB_B),
    )
}
