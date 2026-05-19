package com.example.photorecipe.ui.cameragen

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.photorecipe.ui.theme.PhotoColors
import com.example.photorecipe.util.saveBitmapToGallery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Gemini 가 생성한 이미지를 표시. 우측 상단에 원본(촬영) 썸네일.
 * 사용자는 저장 / 재촬영 / Home 으로 선택.
 */
@Composable
fun ResultScreen(
    captured: Bitmap,
    generated: Bitmap,
    prompt: String,
    onRetake: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    toast?.let {
        LaunchedEffect(it) { delay(3000); toast = null }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PhotoColors.RunwayBlack),
    ) {
        // 상단 바
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onExit) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Back", tint = PhotoColors.PureWhite)
            }
            Spacer(Modifier.fillMaxWidth(0f))
            Text(
                text = if (saving) "SAVING…" else "RESULT",
                style = MaterialTheme.typography.labelSmall,
                color = PhotoColors.MidSlate,
            )
            Spacer(Modifier.fillMaxWidth(0f))
            IconButton(
                onClick = {
                    if (saving) return@IconButton
                    saving = true
                    scope.launch {
                        val r = runCatching {
                            withContext(Dispatchers.IO) {
                                saveBitmapToGallery(context, generated)
                            }
                        }
                        saving = false
                        toast = r.fold(
                            onSuccess = { "Saved to gallery" },
                            onFailure = { "Save failed: ${it.message}" },
                        )
                    }
                },
            ) {
                Icon(Icons.Outlined.Download, contentDescription = "Save", tint = PhotoColors.PureWhite)
            }
        }

        // 결과 이미지 — 풀블리드, 우상단에 원본 썸네일
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(PhotoColors.Canvas),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = generated.asImageBitmap(),
                contentDescription = "Generated photo",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            // 원본 썸네일
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    text = "ORIGINAL",
                    color = PhotoColors.PureWhite,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
                Spacer(Modifier.height(4.dp))
                Image(
                    bitmap = captured.asImageBitmap(),
                    contentDescription = "Captured photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, PhotoColors.PureWhite.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
                )
            }
        }

        // 프롬프트 + 재촬영 버튼
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(PhotoColors.DeepBlack)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "PROMPT",
                style = MaterialTheme.typography.labelSmall,
                color = PhotoColors.CoolSilver,
            )
            Text(
                text = prompt,
                style = MaterialTheme.typography.bodyMedium,
                color = PhotoColors.PureWhite,
                fontFamily = FontFamily.Default,
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onRetake,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PhotoColors.DarkSurface,
                    contentColor = PhotoColors.PureWhite,
                ),
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.CameraAlt, contentDescription = null, tint = PhotoColors.PureWhite)
                Spacer(Modifier.fillMaxWidth(0f))
                Text("  Retake / new prompt", style = MaterialTheme.typography.labelLarge)
            }
        }
    }

    // 토스트
    toast?.let { msg ->
        Box(
            modifier = Modifier.fillMaxSize().padding(bottom = 96.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Text(
                text = msg,
                color = PhotoColors.PureWhite,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .background(PhotoColors.DarkSurface, RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}
