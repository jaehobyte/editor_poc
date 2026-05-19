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
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.photorecipe.ui.theme.PhotoColors

private const val TAG = "CameraScreen"

@Composable
fun CameraScreen(
    prompt: String,
    onPromptChange: (String) -> Unit,
    onCapture: (Bitmap) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 권한
    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCamera = granted }
    LaunchedEffect(Unit) {
        if (!hasCamera) permLauncher.launch(Manifest.permission.CAMERA)
    }

    Box(modifier = modifier.fillMaxSize().background(PhotoColors.RunwayBlack)) {
        if (!hasCamera) {
            PermissionRationale(onRetry = { permLauncher.launch(Manifest.permission.CAMERA) })
            IconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            ) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Back", tint = PhotoColors.PureWhite)
            }
            return@Box
        }

        // 카메라 상태
        var settings by remember { mutableStateOf(CameraSettings()) }
        var bound by remember { mutableStateOf<Camera?>(null) }
        var previewView by remember { mutableStateOf<PreviewView?>(null) }
        val imageCapture = remember {
            ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
        }

        // PreviewView 가 준비됐고 lens/portrait 가 바뀔 때마다 rebind.
        LaunchedEffect(previewView, settings.lens, settings.portraitMode) {
            val pv = previewView ?: return@LaunchedEffect
            bound = bindCamera(
                context = context,
                lifecycleOwner = lifecycleOwner,
                previewView = pv,
                imageCapture = imageCapture,
                lens = settings.lens,
                portraitMode = settings.portraitMode,
            )
            // bind 직후 현재 설정 즉시 반영
            bound?.let { applyRuntimeSettings(it, imageCapture, settings) }
        }

        // 다른 설정 변경은 runtime 적용
        LaunchedEffect(
            bound, settings.flash, settings.zoomRatio, settings.evIndex,
            settings.wb, settings.manualMode, settings.isoIndex, settings.shutterIndex,
        ) {
            bound?.let { applyRuntimeSettings(it, imageCapture, settings) }
        }

        // 줌 / 포커스 인디케이터
        var showZoomBadge by remember { mutableStateOf(false) }
        var focusPoint by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }

        // 프리뷰 (풀스크린) — 핀치 줌 + 탭 포커스
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(bound) {
                    val cam = bound ?: return@pointerInput
                    val info = cam.cameraInfo.zoomState.value
                    val minZ = info?.minZoomRatio ?: 1f
                    val maxZ = info?.maxZoomRatio ?: 4f
                    detectTransformGestures { _, _, zoomChange, _ ->
                        if (zoomChange == 1f) return@detectTransformGestures
                        val current = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                        val next = (current * zoomChange).coerceIn(minZ, maxZ)
                        settings = settings.copy(zoomRatio = next)
                        showZoomBadge = true
                    }
                }
                .pointerInput(bound, previewView) {
                    val cam = bound ?: return@pointerInput
                    val pv = previewView ?: return@pointerInput
                    detectTapGestures(
                        onTap = { offset ->
                            focusPoint = offset
                            val factory = pv.meteringPointFactory
                            val point = factory.createPoint(offset.x, offset.y)
                            val action = FocusMeteringAction.Builder(point).build()
                            runCatching { cam.cameraControl.startFocusAndMetering(action) }
                        },
                    )
                },
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    previewView = this
                }
            },
        )

        // 포커스 표시 (탭한 위치에 잠시 원 그리기)
        focusPoint?.let { offset ->
            LaunchedEffect(offset) {
                kotlinx.coroutines.delay(700)
                focusPoint = null
            }
            Box(
                modifier = Modifier
                    .offset { androidx.compose.ui.unit.IntOffset(offset.x.toInt() - 32, offset.y.toInt() - 32) }
                    .size(64.dp)
                    .border(2.dp, PhotoColors.PureWhite, CircleShape),
            )
        }

        // 줌 배지 (잠시 표시 후 사라짐)
        if (showZoomBadge) {
            LaunchedEffect(settings.zoomRatio) {
                kotlinx.coroutines.delay(1200)
                showZoomBadge = false
            }
            Box(
                modifier = Modifier.fillMaxSize().padding(top = 80.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                ZoomBadge(ratio = settings.zoomRatio)
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            CameraTopBar(
                settings = settings,
                onSettingsChange = { settings = it },
                onBack = onBack,
            )

            Spacer(Modifier.weight(1f))

            // EV / Manual 컨트롤
            val evRange = bound?.cameraInfo?.exposureState?.exposureCompensationRange
                ?.let { it.lower..it.upper }
                ?: (-6..6)
            AdvancedControls(
                settings = settings,
                onSettingsChange = { settings = it },
                evRange = evRange,
            )

            // 프롬프트 + 셔터
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PromptInputRow(prompt, onPromptChange)
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

// ──────────────────────────────────────────────────────────────────
//  Sub-UI
// ──────────────────────────────────────────────────────────────────

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
                    "어떤 사진을 만들고 싶나요?",
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

// ──────────────────────────────────────────────────────────────────
//  Capture
// ──────────────────────────────────────────────────────────────────

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
