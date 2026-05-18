package com.example.photorecipe.ui

import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.photorecipe.editor.wbMultipliers
import com.example.photorecipe.gl.ImageRenderer

/**
 * Compose 안에 GLSurfaceView 를 띄우는 래퍼.
 *
 * @param bitmap 입력 비트맵 (텍스처로 업로드됨)
 * @param temperatureUi Temperature UI 값 [-100, 100]. 0 = 변화 없음.
 */
@Composable
fun ImageGLView(
    bitmap: Bitmap,
    temperatureUi: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val renderer = remember { ImageRenderer() }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            GLSurfaceView(context).apply {
                setEGLContextClientVersion(3)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            }
        },
        update = { view ->
            renderer.setBitmap(bitmap)
            renderer.setWbMultipliers(wbMultipliers(temperatureUi))
            view.requestRender()
        },
    )
}
