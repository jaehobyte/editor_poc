package com.example.photorecipe.ui

import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.photorecipe.gl.ImageRenderer

@Composable
fun ImageGLView(bitmap: Bitmap, modifier: Modifier = Modifier) {
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
            view.requestRender()
        },
    )
}
