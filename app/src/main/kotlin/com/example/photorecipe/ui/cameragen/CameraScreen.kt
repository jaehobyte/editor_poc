package com.example.photorecipe.ui.cameragen

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.speech.RecognizerIntent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.photorecipe.ui.theme.PhotoColors

private const val TAG = "CameraScreen"

/**
 * Camera preview + 프롬프트 입력(텍스트/음성) + 셔터.
 *
 * @param onCapture 셔터 탭 → 회전 보정된 Bitmap 반환.
 */
@Composable
fun CameraScreen(
    prompt: String,
    onPromptChange: (String) -> Unit,
    onCapture: (Bitmap) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // 카메라 권한
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permLauncher.launch(Manifest.permission.CAMERA)
    }

    Box(modifier = modifier.fillMaxSize().background(PhotoColors.RunwayBlack)) {
        if (!hasCameraPermission) {
            PermissionRationale(onRetry = { permLauncher.launch(Manifest.permission.CAMERA) })
            // 상단 back 만 별도로 노출
            IconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            ) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Back", tint = PhotoColors.PureWhite)
            }
            return@Box
        }

        // ImageCapture use case — Compose 라이프사이클 동안 유지.
        val imageCapture = remember { ImageCapture.Builder().build() }
        val lifecycleOwner = LocalLifecycleOwner.current

        // 카메라 프리뷰 (풀스크린)
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                bindCameraToPreview(ctx, previewView, imageCapture, lifecycleOwner)
                previewView
            },
        )

        // 어두운 상단/하단 grad 영역 위에 컨트롤 배치
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            // 상단 바
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "Back", tint = PhotoColors.PureWhite)
                }
                Spacer(Modifier.fillMaxWidth(0.0f))
                Text(
                    text = "CAMERA PROMPT",
                    style = MaterialTheme.typography.labelSmall,
                    color = PhotoColors.MidSlate,
                )
            }

            Spacer(Modifier.weight(1f))

            // 하단 컨트롤
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PromptInputRow(
                    prompt = prompt,
                    onPromptChange = onPromptChange,
                )

                ShutterButton(
                    onTap = {
                        capturePhoto(imageCapture, context) { result ->
                            result.onSuccess(onCapture)
                            result.onFailure { Log.e(TAG, "capture failed", it) }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PromptInputRow(prompt: String, onPromptChange: (String) -> Unit) {
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            matches?.firstOrNull()?.let(onPromptChange)
        }
    }
    val context = LocalContext.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = prompt,
            onValueChange = onPromptChange,
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    "어떤 사진을 만들고 싶나요? (예: 매칭률 높은 데이팅 프로필)",
                    color = PhotoColors.MidSlate,
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = PhotoColors.DarkSurface,
                unfocusedContainerColor = PhotoColors.DarkSurface,
                focusedBorderColor = PhotoColors.PureWhite.copy(alpha = 0.4f),
                unfocusedBorderColor = PhotoColors.BorderDark,
                cursorColor = PhotoColors.PureWhite,
                focusedTextColor = PhotoColors.PureWhite,
                unfocusedTextColor = PhotoColors.PureWhite,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            maxLines = 3,
        )
        IconButton(
            onClick = {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "어떤 사진을 만들고 싶으세요?")
                }
                if (intent.resolveActivity(context.packageManager) != null) {
                    voiceLauncher.launch(intent)
                }
            },
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(PhotoColors.DarkSurface)
                .border(1.dp, PhotoColors.BorderDark, CircleShape),
        ) {
            Icon(Icons.Outlined.Mic, contentDescription = "Voice input", tint = PhotoColors.PureWhite)
        }
    }
}

@Composable
private fun ShutterButton(onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(CircleShape)
            .border(4.dp, PhotoColors.PureWhite, CircleShape)
            .padding(6.dp)
            .clip(CircleShape)
            .background(PhotoColors.PureWhite)
            .clickable(onClick = onTap),
    )
}

@Composable
private fun PermissionRationale(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "카메라 권한이 필요합니다",
            style = MaterialTheme.typography.titleLarge,
            color = PhotoColors.PureWhite,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "카메라로 사진을 찍어 Gemini 에 보내려면 권한을 허용해야 합니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = PhotoColors.MidSlate,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(
                containerColor = PhotoColors.PureWhite,
                contentColor = PhotoColors.RunwayBlack,
            ),
            shape = RoundedCornerShape(50),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
        ) {
            Text("권한 허용", style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ── CameraX 결선 헬퍼 ─────────────────────────────────────────────

private fun bindCameraToPreview(
    context: android.content.Context,
    previewView: PreviewView,
    imageCapture: ImageCapture,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
) {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    cameraProviderFuture.addListener({
        try {
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "bindCameraToPreview failed", t)
        }
    }, ContextCompat.getMainExecutor(context))
}

private fun capturePhoto(
    imageCapture: ImageCapture,
    context: android.content.Context,
    onResult: (Result<Bitmap>) -> Unit,
) {
    imageCapture.takePicture(
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val rotation = image.imageInfo.rotationDegrees
                    val raw = image.toBitmap()
                    val rotated = if (rotation != 0) rotateBitmap(raw, rotation) else raw
                    onResult(Result.success(rotated))
                } catch (t: Throwable) {
                    onResult(Result.failure(t))
                } finally {
                    image.close()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                onResult(Result.failure(exception))
            }
        },
    )
}

private fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
}
