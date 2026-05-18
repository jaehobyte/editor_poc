package com.example.photorecipe.ui

import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.photorecipe.editor.contrastCurve
import com.example.photorecipe.editor.wbMultipliers
import com.example.photorecipe.gl.ImageRenderer

/**
 * Compose 안에 GLSurfaceView 를 띄우는 래퍼.
 *
 * @param bitmap 입력 비트맵
 * @param temperatureUi [-100, 100], 0 = identity
 * @param contrastUi    [-100, 100], 0 = identity
 */
@Composable
fun ImageGLView(
    bitmap: Bitmap,
    temperatureUi: Float = 0f,
    contrastUi: Float = 0f,
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
            renderer.setContrastCurve(contrastCurve(contrastUi))
            view.requestRender()
        },
    )
}
