package com.example.photorecipe.editor

/**
 * NumPy의 `np.interp`와 동일한 1D 선형 보간.
 * x 가 xs 범위 밖이면 양 끝 y 값으로 클램프.
 *
 * @param xs 단조 증가하는 knot의 x 좌표
 * @param ys 같은 길이의 y 좌표
 */
fun interp(x: Float, xs: FloatArray, ys: FloatArray): Float {
    require(xs.isNotEmpty() && xs.size == ys.size) {
        "xs/ys must be non-empty and equal length (got xs=${xs.size}, ys=${ys.size})"
    }
    if (x <= xs[0]) return ys[0]
    val last = xs.size - 1
    if (x >= xs[last]) return ys[last]
    var i = 0
    while (i < last && xs[i + 1] < x) i++
    val t = (x - xs[i]) / (xs[i + 1] - xs[i])
    return ys[i] + t * (ys[i + 1] - ys[i])
}
