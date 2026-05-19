package com.example.photorecipe

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.photorecipe.tflite.RecipeGenerator
import com.example.photorecipe.ui.PickerScreen
import com.example.photorecipe.ui.EditorScreen
import com.example.photorecipe.ui.theme.NewCamTheme
import com.example.photorecipe.ui.theme.PhotoColors
import com.example.photorecipe.util.downscaleForGL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NewCamTheme {
                Scaffold(containerColor = PhotoColors.RunwayBlack) { padding ->
                    AppRoot(modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()))
                }
            }
        }
    }
}

@Composable
private fun AppRoot(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val generator = remember { RecipeGenerator(context) }

    val pick = remember { PickState() }
    val params = remember { EditorParams() }

    var phase by remember { mutableStateOf<AppPhase>(AppPhase.Picker) }
    var generating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        // RecipeGenerator 는 Activity 라이프사이클 동안 유지.
    }

    Box(modifier = modifier.background(PhotoColors.RunwayBlack)) {
        when (val p = phase) {
            AppPhase.Picker -> PickerScreen(
                referenceUri = pick.reference,
                inputUri = pick.input,
                isGenerating = generating,
                onPickReference = { pick.reference = it },
                onPickInput = { pick.input = it },
                onGenerate = {
                    val refUri = pick.reference ?: return@PickerScreen
                    val inUri = pick.input ?: return@PickerScreen
                    generating = true
                    errorMessage = null
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                val refBmp = context.contentResolver.openInputStream(refUri)!!.use {
                                    BitmapFactory.decodeStream(it)
                                }
                                val inBmp = context.contentResolver.openInputStream(inUri)!!.use {
                                    BitmapFactory.decodeStream(it)
                                }
                                // 데모와 동일: content (편집 대상) 가 args_0, reference 가 args_1.
                                val inferred = generator.infer(content = inBmp, reference = refBmp)
                                inferred to downscaleForGL(inBmp)
                            }
                        }.fold(
                            onSuccess = { (inferred, downBmp) ->
                                params.reset()
                                phase = AppPhase.Editing(downBmp, inferred)
                            },
                            onFailure = { errorMessage = it.message ?: "Inference failed" },
                        )
                        generating = false
                    }
                },
            )
            is AppPhase.Editing -> EditorScreen(
                inputBitmap = p.inputBitmap,
                inferred = p.params,
                params = params,
                onBack = { phase = AppPhase.Picker },
            )
        }
        if (generating) {
            Box(
                modifier = Modifier.fillMaxSize().background(PhotoColors.RunwayBlack.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = PhotoColors.PureWhite)
            }
        }
        errorMessage?.let { msg ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Text(
                    text = msg,
                    color = PhotoColors.PureWhite,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
    }
}
