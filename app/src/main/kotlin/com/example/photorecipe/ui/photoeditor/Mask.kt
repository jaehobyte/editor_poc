package com.example.photorecipe.ui.photoeditor

import android.graphics.Bitmap
import androidx.compose.runtime.Stable
import com.example.photorecipe.EditorParams

/**
 * 한 개의 영역 마스크 + 그 영역에 적용할 보정 파라미터.
 *
 * @param alphaBitmap 원본 이미지와 같은 비율의 grayscale 마스크. 픽셀의 R 채널이
 *                    그 위치의 mask 알파 (0 = 영향 없음, 255 = 완전 적용). ARGB_8888 이지만
 *                    R=G=B 동일 (grayscale).
 */
@Stable
class Mask(
    val id: String,
    var alphaBitmap: Bitmap,
    val params: EditorParams = EditorParams(),
    val label: String = id,
)
