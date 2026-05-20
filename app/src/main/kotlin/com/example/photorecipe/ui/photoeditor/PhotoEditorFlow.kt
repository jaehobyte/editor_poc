package com.example.photorecipe.ui.photoeditor

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.photorecipe.segmentation.SegmentationEngine
import com.example.photorecipe.tflite.RecipeGenerator
import com.example.photorecipe.ui.theme.PhotoColors
import com.example.photorecipe.vibe.VibeClient
import com.example.photorecipe.util.decodeBitmapWithOrientation
import com.example.photorecipe.util.downscaleForGL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface PePhase {
    data object Picker : PePhase
    data class Editing(val uri: Uri, val preview: Bitmap) : PePhase
}

@Composable
fun PhotoEditorFlow(
    segmenter: SegmentationEngine,
    vibeClient: VibeClient,
    generator: RecipeGenerator,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var phase by remember { mutableStateOf<PePhase>(PePhase.Picker) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Box(modifier = modifier.fillMaxSize().background(PhotoColors.RunwayBlack)) {
        when (val p = phase) {
            PePhase.Picker -> {
                BackHandler { onExit() }
                EditorPickerScreen(
                    pickedUri = pickedUri,
                    onPick = { pickedUri = it },
                    onBack = onExit,
                    onContinue = {
                        val uri = pickedUri ?: return@EditorPickerScreen
                        loading = true
                        error = null
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    val full = decodeBitmapWithOrientation(context, uri)
                                    downscaleForGL(full)
                                }
                            }.fold(
                                onSuccess = { preview ->
                                    phase = PePhase.Editing(uri, preview)
                                },
                                onFailure = { error = "Cannot open image: ${it.message}" },
                            )
                            loading = false
                        }
                    },
                )
            }
            is PePhase.Editing -> {
                BackHandler { phase = PePhase.Picker }
                PhotoEditorScreen(
                    originalUri = p.uri,
                    previewBitmap = p.preview,
                    segmenter = segmenter,
                    vibeClient = vibeClient,
                    generator = generator,
                    onBack = { phase = PePhase.Picker },
                )
            }
        }

        if (loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(PhotoColors.RunwayBlack.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = PhotoColors.PureWhite)
            }
        }
        error?.let { msg ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Text(text = msg, color = PhotoColors.PureWhite, modifier = Modifier.padding(24.dp))
            }
        }
    }
}
