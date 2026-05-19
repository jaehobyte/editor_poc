package com.example.photorecipe.ui

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
import com.example.photorecipe.AppPhase
import com.example.photorecipe.EditorParams
import com.example.photorecipe.PickState
import com.example.photorecipe.gemini.GeminiImageClient
import com.example.photorecipe.tflite.RecipeGenerator
import com.example.photorecipe.ui.theme.PhotoColors
import com.example.photorecipe.util.decodeBitmapWithOrientation
import com.example.photorecipe.util.downscaleForGL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Photo Recipe feature 의 전체 흐름(Picker → Editor) 을 한 컴포저블로 감쌈.
 *
 * @param generator TFLite 추론 엔진 — Home 라우터에서 lazy 하게 보관, feature
 *                  진입/이탈 사이에 모델이 재로딩되지 않도록 외부 주입.
 * @param gemini Nano Banana 클라이언트 — 동일 이유로 외부 주입.
 * @param onExit 사용자가 Picker 에서 back/system back → Home 으로 복귀.
 */
@Composable
fun PhotoRecipeFlow(
    generator: RecipeGenerator,
    gemini: GeminiImageClient,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val pick = remember { PickState() }
    val params = remember { EditorParams() }

    var phase by remember { mutableStateOf<AppPhase>(AppPhase.Picker) }
    var generating by remember { mutableStateOf(false) }
    var stylizing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Box(modifier = modifier.fillMaxSize().background(PhotoColors.RunwayBlack)) {
        when (val p = phase) {
            AppPhase.Picker -> {
                // Picker 단계에서 system back → Home.
                BackHandler { onExit() }
                PickerScreen(
                    referenceUri = pick.reference,
                    inputUri = pick.input,
                    isGenerating = generating,
                    isStylizing = stylizing,
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
                                    val refBmp = decodeBitmapWithOrientation(context, refUri)
                                    val inBmp = decodeBitmapWithOrientation(context, inUri)
                                    val inferred = generator.infer(content = inBmp, reference = refBmp)
                                    inferred to downscaleForGL(inBmp)
                                }
                            }.fold(
                                onSuccess = { (inferred, downBmp) ->
                                    params.reset()
                                    phase = AppPhase.Editing(downBmp, inUri, inferred)
                                },
                                onFailure = { errorMessage = it.message ?: "Inference failed" },
                            )
                            generating = false
                        }
                    },
                    onStylizeAndGenerate = {
                        val refUri = pick.reference ?: return@PickerScreen
                        val inUri = pick.input ?: return@PickerScreen
                        stylizing = true
                        errorMessage = null
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    val refBmp = decodeBitmapWithOrientation(context, refUri)
                                    val inBmp = decodeBitmapWithOrientation(context, inUri)
                                    val stylized = gemini.stylize(reference = refBmp, content = inBmp)
                                    val inferred = generator.infer(content = inBmp, reference = stylized)
                                    Triple(inferred, downscaleForGL(inBmp), stylized)
                                }
                            }.fold(
                                onSuccess = { (inferred, downBmp, stylized) ->
                                    params.reset()
                                    phase = AppPhase.Editing(downBmp, inUri, inferred, stylizedReference = stylized)
                                },
                                onFailure = { errorMessage = it.message ?: "Stylize failed" },
                            )
                            stylizing = false
                        }
                    },
                )
            }

            is AppPhase.Editing -> EditorScreen(
                inputBitmap = p.inputBitmap,
                inputUri = p.inputUri,
                inferred = p.params,
                params = params,
                stylizedReference = p.stylizedReference,
                onBack = { phase = AppPhase.Picker },
            )
        }

        if (generating || stylizing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(PhotoColors.RunwayBlack.copy(alpha = 0.7f)),
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
