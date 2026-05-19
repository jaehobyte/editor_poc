package com.example.photorecipe.ui.cameragen

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.example.photorecipe.gemini.GeminiImageClient
import com.example.photorecipe.ui.theme.PhotoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 카메라+생성형 시나리오의 단계. */
private sealed interface CamPhase {
    data object Capture : CamPhase
    data class Generating(val captured: Bitmap, val prompt: String) : CamPhase
    data class Result(val captured: Bitmap, val generated: Bitmap, val prompt: String) : CamPhase
}

/**
 * 카메라로 사진을 찍고, 사용자의 텍스트/음성 프롬프트와 함께
 * Gemini Nano Banana 로 보내 새 이미지를 생성하는 흐름.
 */
@Composable
fun CameraGenFlow(
    gemini: GeminiImageClient,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    var prompt by remember { mutableStateOf("") }
    var phase by remember { mutableStateOf<CamPhase>(CamPhase.Capture) }
    var error by remember { mutableStateOf<String?>(null) }

    Box(modifier = modifier.fillMaxSize().background(PhotoColors.RunwayBlack)) {
        when (val p = phase) {
            CamPhase.Capture -> {
                BackHandler { onExit() }
                CameraScreen(
                    prompt = prompt,
                    onPromptChange = { prompt = it },
                    onBack = onExit,
                    onCapture = { bitmap ->
                        val trimmed = prompt.trim()
                        if (trimmed.isEmpty()) {
                            error = "프롬프트를 입력하거나 발화해주세요."
                            return@CameraScreen
                        }
                        error = null
                        phase = CamPhase.Generating(bitmap, trimmed)
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    gemini.generate(image = bitmap, userPrompt = trimmed)
                                }
                            }.fold(
                                onSuccess = { generated ->
                                    phase = CamPhase.Result(bitmap, generated, trimmed)
                                },
                                onFailure = {
                                    error = "Gemini 생성 실패: ${it.message}"
                                    phase = CamPhase.Capture
                                },
                            )
                        }
                    },
                )
            }
            is CamPhase.Generating -> {
                BackHandler { /* swallow during generation */ }
                GeneratingOverlay(captured = p.captured, prompt = p.prompt)
            }
            is CamPhase.Result -> {
                BackHandler { phase = CamPhase.Capture }
                ResultScreen(
                    captured = p.captured,
                    generated = p.generated,
                    prompt = p.prompt,
                    onRetake = { phase = CamPhase.Capture },
                    onExit = onExit,
                )
            }
        }

        error?.let { msg ->
            Box(
                modifier = Modifier.fillMaxSize().padding(bottom = 96.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Text(
                    text = msg,
                    color = PhotoColors.PureWhite,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .background(PhotoColors.DarkSurface, RoundedCornerShape(50))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun GeneratingOverlay(captured: Bitmap, prompt: String) {
    Box(
        modifier = Modifier.fillMaxSize().background(PhotoColors.RunwayBlack),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                bitmap = captured.asImageBitmap(),
                contentDescription = "Captured photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(220.dp)
                    .clip(RoundedCornerShape(20.dp)),
            )
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator(color = PhotoColors.PureWhite)
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Generating…",
                style = MaterialTheme.typography.labelLarge,
                color = PhotoColors.PureWhite,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "“$prompt”",
                style = MaterialTheme.typography.bodySmall,
                color = PhotoColors.MidSlate,
                modifier = Modifier.padding(horizontal = 40.dp),
            )
        }
    }
}
