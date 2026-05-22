package com.example.photorecipe.ui.photoeditor

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.FlipCameraAndroid
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.photorecipe.EditorParams
import com.example.photorecipe.editor.applyRecipeMasked
import com.example.photorecipe.segmentation.SegFormerSceneryEngine
import com.example.photorecipe.segmentation.SegmentationEngine
import com.example.photorecipe.segmentation.SegmentationEngine.DetectedInstance
import com.example.photorecipe.tflite.RecipeGenerator
import com.example.photorecipe.ui.ImageGLView
import com.example.photorecipe.ui.theme.PhotoColors
import com.example.photorecipe.util.decodeBitmapForExport
import com.example.photorecipe.util.decodeBitmapWithOrientation
import com.example.photorecipe.util.saveBitmapToGallery
import com.example.photorecipe.vibe.VibeClient
import com.example.photorecipe.vibe.VibeEdit
import com.example.photorecipe.vibe.VibeTurn
import com.example.photorecipe.vibe.rememberSpeechSession

import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.draw.scale
import androidx.core.content.ContextCompat

private enum class EditorTab(val label: String, val icon: ImageVector) {
    VIBE("VIBE", Icons.Outlined.AutoAwesome),
    RECIPE("RECIPE", Icons.Outlined.AutoFixHigh),
    ADJUST("ADJUST", Icons.Outlined.Tune),
    COLOR("COLOR", Icons.Outlined.Palette),
    FILTERS("FILTERS", Icons.Outlined.Photo),
    CROP("CROP", Icons.Outlined.Crop),
}

/** 멀티턴 vibe 대화에서 모델에 보낼 최대 turn 수. 토큰 폭주 방지용 상한. */
private const val VIBE_HISTORY_MAX = 6

/**
 * 캔버스 좌표 ([tap]) → 이미지 픽셀 좌표. ContentScale.Fit (= GL viewport 의
 * letterbox 와 동일) 을 가정. 탭이 letterbox 영역에 있으면 null.
 */
private fun viewportToImage(
    tap: Offset,
    boxSize: IntSize,
    imgW: Int,
    imgH: Int,
): Offset? {
    val boxW = boxSize.width.toFloat()
    val boxH = boxSize.height.toFloat()
    if (boxW <= 0f || boxH <= 0f || imgW <= 0 || imgH <= 0) return null
    val imgAspect = imgW.toFloat() / imgH.toFloat()
    val boxAspect = boxW / boxH
    val drawnW: Float
    val drawnH: Float
    if (imgAspect > boxAspect) {
        drawnW = boxW
        drawnH = boxW / imgAspect
    } else {
        drawnW = boxH * imgAspect
        drawnH = boxH
    }
    val xOffset = (boxW - drawnW) / 2f
    val yOffset = (boxH - drawnH) / 2f
    val relX = (tap.x - xOffset) / drawnW
    val relY = (tap.y - yOffset) / drawnH
    if (relX < 0f || relX > 1f || relY < 0f || relY > 1f) return null
    return Offset(relX * imgW, relY * imgH)
}

/** Global 모드에서 슬라이더 멈춘 뒤 합성본 재계산까지 기다리는 시간 (ms). */
private const val COMPOSITE_DEBOUNCE_MS = 300L

/**
 * PhotoEditor 메인:
 * - 한 장 사진에 4탭 보정 + 객체 단위 마스크 보정 지원.
 * - 사진 로드 시 SegmentationEngine 이 ObjectDetector + DeepLab v3 로 자동 인스턴스
 *   검출. 검출된 객체 칩을 탭하면 그 인스턴스의 마스크가 편집 대상으로 추가됨.
 * - 마스크 칩 (Global / Person / Dog / ...) 으로 슬라이더 대상 전환.
 */
@Composable
fun PhotoEditorScreen(
    originalUri: Uri,
    previewBitmap: Bitmap,
    segmenter: SegmentationEngine,
    vibeClient: VibeClient,
    generator: RecipeGenerator,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val globalParams = remember { EditorParams() }
    var masks by remember { mutableStateOf<List<Mask>>(emptyList()) }
    var selectedMaskId by remember { mutableStateOf<String?>(null) } // null = global
    var crop by remember { mutableStateOf(CropTransform()) }
    var tab by remember { mutableStateOf(EditorTab.ADJUST) }

    var saving by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var detectedInstances by remember { mutableStateOf<List<DetectedInstance>>(emptyList()) }
    var sceneryAnalysis by remember { mutableStateOf<SegFormerSceneryEngine.Analysis?>(null) }
    var detecting by remember { mutableStateOf(false) }
    // Tap-segment 모드 — 켜진 상태에서 캔버스 단일 탭 → 그 픽셀의 ADE20K 클래스를
    // 마스크로 추가. 자동 검출 칩이 놓친 영역 보완용.
    var tapSegmentMode by remember { mutableStateOf(false) }

    // Vibe (Gemini 자연어 편집) 상태.
    var vibePrompt by remember { mutableStateOf("") }
    var vibeBusy by remember { mutableStateOf(false) }
    var vibeStatus by remember { mutableStateOf<String?>(null) }
    // 멀티턴 대화 히스토리. "조금 더" / "취소" 같은 후속 명령용.
    var vibeHistory by remember { mutableStateOf<List<VibeTurn>>(emptyList()) }
    // Vibe 입력 옆 갤러리 버튼으로 첨부한 reference 사진 (Gemini 가 recipe transfer
    // 함수를 호출할지 판단하는 입력).
    var vibeAttachment by remember { mutableStateOf<Bitmap?>(null) }

    // Recipe 탭 — 직접 reference 를 골라 recipe_generator.tflite 로 톤 추론 → 현재
    // 선택된 target params 에 바로 적용. LLM 우회 경로.
    var recipeReference by remember { mutableStateOf<Bitmap?>(null) }
    var recipeBusy by remember { mutableStateOf(false) }
    var recipeStatus by remember { mutableStateOf<String?>(null) }

    // Photo picker 두 개 — Vibe 첨부용, Recipe 탭 reference 용. URI → IO 에서 decode.
    val vibeAttachmentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching { decodeBitmapWithOrientation(context, uri) }.getOrNull()
            }
            vibeAttachment = bmp
        }
    }
    val recipeReferencePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching { decodeBitmapWithOrientation(context, uri) }.getOrNull()
            }
            recipeReference = bmp
            recipeStatus = null
        }
    }

    // Global 모드에서 보여줄 합성본. 마스크가 하나도 없으면 GL 만 쓰고, 마스크가
    // 있을 때만 CPU `applyRecipeMasked` 로 모두 합쳐서 보여준다.
    var previewComposite by remember { mutableStateOf<Bitmap?>(null) }
    var previewing by remember { mutableStateOf(false) }
    val isGlobalMode = selectedMaskId == null
    val showComposite = isGlobalMode && masks.isNotEmpty()

    // 슬라이더가 움직일 때마다 LaunchedEffect 가 재발화되도록, global + 모든 마스크의
    // 파라미터를 합친 시그니처를 derivedStateOf 로 만든다. 값이 안 바뀌면 컴포지트
    // 재계산 트리거도 안 됨.
    val compositeKey by remember {
        derivedStateOf {
            buildList {
                add(globalParams.snapshotKey())
                for (m in masks) {
                    add(m.id)
                    add(m.params.snapshotKey())
                }
            }
        }
    }

    // Crop 적용된 프리뷰 + 마스크
    var croppedPreview by remember { mutableStateOf(previewBitmap) }
    LaunchedEffect(previewBitmap, crop) {
        // 크롭이 바뀌면 기존 마스크 알파는 pre-crop 좌표계라 더 이상 유효하지 않다.
        // 검출도 새 croppedPreview 에 다시 돌아갈 거니까 stale 인스턴스 칩도 제거.
        // (LaunchedEffect 가 첫 composition 에서도 한 번 발화하지만, masks 와
        // detectedInstances 가 처음부터 emptyList 라 no-op.)
        masks = emptyList()
        selectedMaskId = null
        detectedInstances = emptyList()
        sceneryAnalysis = null
        tapSegmentMode = false
        croppedPreview = withContext(Dispatchers.IO) { applyCropTransform(previewBitmap, crop) }
    }

    LaunchedEffect(showComposite, croppedPreview, compositeKey) {
        if (!showComposite) {
            previewComposite?.takeIf { !it.isRecycled }?.recycle()
            previewComposite = null
            previewing = false
            return@LaunchedEffect
        }
        // 슬라이더가 빠르게 움직이는 동안에는 매번 재계산하지 말고, 멈춘 뒤 잠깐 기다림.
        // key 가 바뀌면 LaunchedEffect 가 자동으로 cancel/restart 라서 자연스럽게 debounce.
        previewing = true
        delay(COMPOSITE_DEBOUNCE_MS)
        val newComposite = withContext(Dispatchers.IO) {
            val scaledMasks = masks.map { m ->
                val scaled = Bitmap.createScaledBitmap(
                    m.featheredAlphaBitmap,
                    croppedPreview.width,
                    croppedPreview.height,
                    true,
                )
                m to scaled
            }
            try {
                applyRecipeMasked(croppedPreview, globalParams, scaledMasks)
            } finally {
                for ((m, scaled) in scaledMasks) {
                    if (scaled !== m.featheredAlphaBitmap && !scaled.isRecycled) scaled.recycle()
                }
            }
        }
        previewComposite?.takeIf { it !== newComposite && !it.isRecycled }?.recycle()
        previewComposite = newComposite
        previewing = false
    }

    // 이미지가 들어오거나 crop 이 바뀔 때마다 인스턴스 자동 검출.
    LaunchedEffect(croppedPreview) {
        detecting = true
        // 크롭 effect 에서 이미 비웠지만, croppedPreview 만 바뀌는 시나리오 대비 안전망.
        detectedInstances = emptyList()
        sceneryAnalysis = null
        val result = withContext(Dispatchers.IO) {
            runCatching { segmenter.detect(croppedPreview) }.getOrNull()
        }
        detectedInstances = result?.instances.orEmpty()
        sceneryAnalysis = result?.sceneryAnalysis
        detecting = false
    }

    // 슬라이더가 편집할 대상 (global or selected mask)
    val targetParams by remember {
        derivedStateOf {
            selectedMaskId?.let { id -> masks.firstOrNull { it.id == id }?.params } ?: globalParams
        }
    }
    val activeMask: Mask? = masks.firstOrNull { it.id == selectedMaskId }

    toast?.let { LaunchedEffect(it) { delay(3000); toast = null } }

    Column(
        modifier = modifier.fillMaxSize().background(PhotoColors.RunwayBlack),
    ) {
        TopBar(
            saving = saving,
            onBack = onBack,
            onReset = {
                globalParams.reset()
                masks = emptyList()
                selectedMaskId = null
                crop = CropTransform()
                vibeHistory = emptyList()
                vibeStatus = null
                vibeAttachment = null
                recipeReference = null
                recipeStatus = null
                tapSegmentMode = false
            },
            onSave = {
                if (saving) return@TopBar
                saving = true
                scope.launch {
                    val r = runCatching {
                        withContext(Dispatchers.IO) {
                            val full = decodeBitmapForExport(context, originalUri)
                            val cropped = applyCropTransform(full, crop)
                            // 마스크 비트맵은 프리뷰 해상도라 풀해상도에 맞춰 스케일.
                            val scaledMasks = masks.map { m ->
                                // 페더된 알파를 사용해 저장본도 프리뷰처럼 부드러운 경계를 갖도록.
                                val scaled = Bitmap.createScaledBitmap(
                                    m.featheredAlphaBitmap, cropped.width, cropped.height, true,
                                )
                                m to scaled
                            }
                            val rendered = applyRecipeMasked(cropped, globalParams, scaledMasks)
                            saveBitmapToGallery(context, rendered)
                        }
                    }
                    saving = false
                    toast = r.fold(
                        onSuccess = { "Saved to gallery" },
                        onFailure = { "Save failed: ${it.message}" },
                    )
                }
            },
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .background(PhotoColors.Canvas),
            contentAlignment = Alignment.Center,
        ) {
            // 두 가지 캔버스 동작이 한 pointerInput 에 같이 묶여 있다:
            //   - 단일 탭 + tapSegmentMode ON → 그 픽셀의 ADE20K 클래스로 마스크 추가
            //   - 길게 누름 (Global 모드만) → 원본 비교 fade
            var showingOriginal by remember { mutableStateOf(false) }
            val originalAlpha by animateFloatAsState(
                targetValue = if (showingOriginal) 1f else 0f,
                animationSpec = tween(durationMillis = 180),
                label = "global-compare-fade",
            )
            val haptic = LocalHapticFeedback.current
            // tap-segment 결과로 추가될 (이미지 좌표) — pointerInput 안에서 set,
            // LaunchedEffect 가 받아서 무거운 마스크 빌드 작업 수행.
            var pendingTapImg by remember { mutableStateOf<Offset?>(null) }
            // 탭 위치 ripple 애니메이션용 — 캔버스 좌표 + 진행값.
            var tapRipplePoint by remember { mutableStateOf<Offset?>(null) }
            val rippleProgress = remember { Animatable(0f) }
            LaunchedEffect(tapRipplePoint) {
                if (tapRipplePoint == null) return@LaunchedEffect
                rippleProgress.snapTo(0f)
                rippleProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 520),
                )
                tapRipplePoint = null
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(
                        tapSegmentMode, isGlobalMode, sceneryAnalysis,
                        croppedPreview.width, croppedPreview.height,
                    ) {
                        detectTapGestures(
                            onPress = { downOffset ->
                                val boxSize = size
                                val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                    tryAwaitRelease()
                                    true
                                }
                                if (released == true) {
                                    // 짧은 탭: tap-segment 활성화 시에만 의미.
                                    if (tapSegmentMode && sceneryAnalysis != null) {
                                        val img = viewportToImage(
                                            downOffset,
                                            boxSize,
                                            croppedPreview.width,
                                            croppedPreview.height,
                                        )
                                        if (img != null) {
                                            pendingTapImg = img
                                            tapRipplePoint = downOffset
                                        }
                                    }
                                } else {
                                    // 길게 누름: Global 모드에서만 원본 보기.
                                    if (isGlobalMode) {
                                        showingOriginal = true
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        tryAwaitRelease()
                                        showingOriginal = false
                                    }
                                }
                            },
                        )
                    },
            ) {
                if (isGlobalMode && showComposite) {
                    previewComposite?.let { composite ->
                        Image(
                            bitmap = composite.asImageBitmap(),
                            contentDescription = "Composite",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else {
                    ImageGLView(
                        bitmap = croppedPreview,
                        global = globalParams,
                        maskBitmap = if (isGlobalMode) null else activeMask?.featheredAlphaBitmap,
                        mask = if (isGlobalMode) null else activeMask?.params,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (isGlobalMode && originalAlpha > 0.001f) {
                    Image(
                        bitmap = croppedPreview.asImageBitmap(),
                        contentDescription = "Original photo (long-press to compare)",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(originalAlpha),
                    )
                }
            }
            if (isGlobalMode && originalAlpha > 0.001f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)
                        .alpha(originalAlpha)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                ) {
                    Text(
                        text = "ORIGINAL",
                        color = PhotoColors.PureWhite,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.2.sp,
                    )
                }
            }
            if (isGlobalMode && showComposite && previewing) {
                // 미세한 합성 진행 인디케이터 — 우상단 작은 점.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF6BE3FF).copy(alpha = 0.85f)),
                )
            }

            // Tap ripple — 탭한 위치에 시안색 펄스. 마스크 빌드/뷰 전환 동안 잠깐 visible.
            tapRipplePoint?.let { pt ->
                val progress = rippleProgress.value
                val alpha = (1f - progress).coerceIn(0f, 1f)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val maxR = 56.dp.toPx()
                    drawCircle(
                        color = Color(0xFF6BE3FF).copy(alpha = alpha * 0.35f),
                        radius = progress * maxR,
                        center = pt,
                    )
                    drawCircle(
                        color = Color(0xFF6BE3FF).copy(alpha = alpha),
                        radius = 4.dp.toPx() + (1f - progress) * 4.dp.toPx(),
                        center = pt,
                    )
                }
            }

            // Tap-segment 모드 안내 — 하단 가운데 작은 알약.
            if (tapSegmentMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                        .background(Color(0xFF6BE3FF).copy(alpha = 0.92f), RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "사진을 탭해 영역을 추가하세요",
                        color = PhotoColors.RunwayBlack,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // pointerInput 에서 detect 된 탭 좌표를 받아 실제 마스크 생성. IO 에서 빌드.
            LaunchedEffect(pendingTapImg) {
                val coord = pendingTapImg ?: return@LaunchedEffect
                val analysis = sceneryAnalysis
                if (analysis == null) {
                    pendingTapImg = null
                    return@LaunchedEffect
                }
                val classId = segmenter.sceneryClassAt(analysis, coord.x, coord.y)
                if (classId in SegFormerSceneryEngine.ADE20K_LABELS.indices) {
                    val rawLabel = SegFormerSceneryEngine.ADE20K_LABELS[classId]
                    val maskBitmap = withContext(Dispatchers.IO) {
                        segmenter.buildSceneryClassMask(
                            analysis,
                            classId,
                            croppedPreview.width,
                            croppedPreview.height,
                        )
                    }
                    val newMask = Mask(
                        id = "mask-${System.currentTimeMillis()}-tap-$classId",
                        alphaBitmap = maskBitmap,
                        params = EditorParams().apply { copyValuesFrom(globalParams) },
                        label = rawLabel.replaceFirstChar { it.uppercase() },
                    )
                    masks = masks + newMask
                    selectedMaskId = newMask.id
                    toast = "Added \"$rawLabel\""
                } else {
                    toast = "No class detected at that point"
                }
                tapSegmentMode = false
                pendingTapImg = null
            }

            // Lightroom 식 "활성 마스크 포커스": 마스크 *바깥* (원본 영역) 을 어둡게 깔아서
            // 어느 부분이 편집 대상인지 한눈에 보이게 한다. 마스크 안쪽은 투명이라
            // 슬라이더로 바뀌는 결과가 그대로 노출됨.
            activeMask?.let { m ->
                var flashing by remember(m.id) { mutableStateOf(true) }
                LaunchedEffect(m.id) {
                    flashing = true
                    delay(700)
                    flashing = false
                }
                val dimStrength by animateFloatAsState(
                    targetValue = if (flashing) 1.0f else 0.65f,
                    animationSpec = tween(durationMillis = 700),
                    label = "mask-dim-strength",
                )
                Image(
                    bitmap = m.dimOverlayBitmap.asImageBitmap(),
                    contentDescription = "Inactive area dimmed",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(dimStrength),
                )
                // 추가: 마스크 경계선을 밝은 청록색으로 그려서 어느 영역이 편집 중인지 명확히.
                Image(
                    bitmap = m.boundaryBitmap.asImageBitmap(),
                    contentDescription = "Mask boundary",
                    contentScale = ContentScale.Fit,
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                        Color(0xFF6BE3FF),
                        androidx.compose.ui.graphics.BlendMode.SrcIn,
                    ),
                    modifier = Modifier.fillMaxSize().alpha(0.95f),
                )
            }

            // 힌트: 마스크가 없을 때만, 인스턴스 칩에서 선택하라고 안내.
            if (masks.isEmpty() && !detecting && detectedInstances.isNotEmpty()) {
                Text(
                    text = "Tap a detected object below to edit it",
                    color = PhotoColors.PureWhite.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().background(PhotoColors.DeepBlack),
        ) {
            DetectedInstancesRow(
                detecting = detecting,
                instances = detectedInstances,
                onTap = { inst ->
                    // alphaBitmap 은 detect 단계에서 이미 계산되어 있어서 IO 호출 불필요.
                    val maskParams = EditorParams().apply { copyValuesFrom(globalParams) }
                    val newMask = Mask(
                        id = "mask-${System.currentTimeMillis()}",
                        alphaBitmap = inst.alphaBitmap,
                        params = maskParams,
                        label = inst.label.replaceFirstChar { it.uppercase() },
                    )
                    masks = masks + newMask
                    selectedMaskId = newMask.id
                },
            )
            MaskSelectorRow(
                masks = masks,
                selectedId = selectedMaskId,
                tapSegmentMode = tapSegmentMode,
                tapSegmentEnabled = sceneryAnalysis != null,
                onSelect = { selectedMaskId = it },
                onDelete = { id ->
                    masks = masks.filter { it.id != id }
                    if (selectedMaskId == id) selectedMaskId = null
                },
                onToggleTapSegment = { tapSegmentMode = !tapSegmentMode },
            )
            TabStrip(tab = tab, onTabChange = { tab = it })
            Spacer(Modifier.height(8.dp))
            when (tab) {
                EditorTab.VIBE -> VibeControls(
                    prompt = vibePrompt,
                    onPromptChange = { vibePrompt = it },
                    busy = vibeBusy,
                    status = vibeStatus,
                    attachment = vibeAttachment,
                    onPickAttachment = {
                        vibeAttachmentPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onClearAttachment = { vibeAttachment = null },
                    onSubmit = {
                        val text = vibePrompt.trim()
                        if (text.isBlank() || vibeBusy) return@VibeControls
                        vibeBusy = true
                        vibeStatus = null
                        scope.launch {
                            val focus = activeMask?.label?.lowercase() ?: "global"
                            val result = applyVibePrompt(
                                vibeClient = vibeClient,
                                generator = generator,
                                prompt = text,
                                history = vibeHistory,
                                referenceImage = vibeAttachment,
                                content = croppedPreview,
                                detectedInstances = detectedInstances,
                                masks = masks,
                                globalParams = globalParams,
                                currentFocus = focus,
                            )
                            masks = result.newMasks
                            if (result.newSelectedId != null) selectedMaskId = result.newSelectedId
                            vibeStatus = result.status
                            // 다음 follow-up 때 모델이 직전 결과를 알 수 있도록 history 갱신.
                            // 최근 N 턴만 유지 — context 길이 폭주 방지.
                            vibeHistory = (vibeHistory + VibeTurn(
                                userPrompt = text,
                                summary = result.summary,
                            )).takeLast(VIBE_HISTORY_MAX)
                            vibePrompt = ""
                            vibeBusy = false
                        }
                    },
                )
                EditorTab.RECIPE -> RecipeControls(
                    reference = recipeReference,
                    busy = recipeBusy,
                    status = recipeStatus,
                    targetLabel = activeMask?.label ?: "Global",
                    onPickReference = {
                        recipeReferencePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onClearReference = {
                        recipeReference = null
                        recipeStatus = null
                    },
                    onApply = {
                        val ref = recipeReference ?: return@RecipeControls
                        if (recipeBusy) return@RecipeControls
                        recipeBusy = true
                        recipeStatus = null
                        scope.launch {
                            val r = runCatching {
                                withContext(Dispatchers.IO) {
                                    generator.infer(content = croppedPreview, reference = ref)
                                }
                            }
                            r.fold(
                                onSuccess = { params29 ->
                                    targetParams.applyInferred(params29)
                                    recipeStatus = "Applied recipe to ${activeMask?.label ?: "Global"}"
                                },
                                onFailure = { e ->
                                    recipeStatus = "Failed: ${e.message?.take(120)}"
                                },
                            )
                            recipeBusy = false
                        }
                    },
                )
                EditorTab.ADJUST -> AdjustControls(targetParams)
                EditorTab.COLOR -> ColorControls(targetParams)
                EditorTab.FILTERS -> FiltersRow(targetParams)
                EditorTab.CROP -> CropControls(crop, onChange = { crop = it })
            }
        }
    }

    toast?.let { msg ->
        Box(modifier = Modifier.fillMaxSize().padding(bottom = 96.dp), contentAlignment = Alignment.BottomCenter) {
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

@Composable
private fun TopBar(saving: Boolean, onBack: () -> Unit, onReset: () -> Unit, onSave: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Outlined.ArrowBack, contentDescription = "Back", tint = PhotoColors.PureWhite)
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = if (saving) "SAVING…" else "EDITING",
            style = MaterialTheme.typography.labelSmall,
            color = PhotoColors.MidSlate,
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onReset) {
            Icon(Icons.Outlined.RestartAlt, contentDescription = "Reset", tint = PhotoColors.PureWhite)
        }
        IconButton(onClick = onSave) {
            Icon(Icons.Outlined.Download, contentDescription = "Save", tint = PhotoColors.PureWhite)
        }
    }
}

// ─── Detected instances (auto) row ───────────────────────────────

private val INSTANCE_PALETTE = listOf(
    Color(0xFFFF6B6B), Color(0xFFFFB347), Color(0xFFFDD835),
    Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFF9C27B0),
    Color(0xFF00BCD4), Color(0xFFE91E63),
)

@Composable
private fun DetectedInstancesRow(
    detecting: Boolean,
    instances: List<DetectedInstance>,
    onTap: (DetectedInstance) -> Unit,
) {
    if (!detecting && instances.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(
            text = if (detecting) "DETECTING INSTANCES…" else "DETECTED",
            style = MaterialTheme.typography.labelSmall,
            color = PhotoColors.CoolSilver,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        if (detecting) {
            Box(
                modifier = Modifier.fillMaxWidth().height(36.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                CircularProgressIndicator(
                    color = PhotoColors.PureWhite,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                instances.forEachIndexed { i, inst ->
                    InstanceChip(
                        label = inst.label,
                        score = inst.score,
                        accent = INSTANCE_PALETTE[i % INSTANCE_PALETTE.size],
                        onClick = { onTap(inst) },
                    )
                }
            }
        }
    }
}

@Composable
private fun InstanceChip(label: String, score: Float, accent: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(PhotoColors.DarkSurface)
            .border(1.dp, accent.copy(alpha = 0.8f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(accent),
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = label.replaceFirstChar { it.uppercase() },
            color = PhotoColors.PureWhite,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = "${(score * 100).toInt()}%",
            color = PhotoColors.MidSlate,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

// ─── Mask selector row ────────────────────────────────────────────

@Composable
private fun MaskSelectorRow(
    masks: List<Mask>,
    selectedId: String?,
    tapSegmentMode: Boolean,
    tapSegmentEnabled: Boolean,
    onSelect: (String?) -> Unit,
    onDelete: (String) -> Unit,
    onToggleTapSegment: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TargetChip(label = "Global", selected = selectedId == null, onClick = { onSelect(null) })
        for (m in masks) {
            TargetChip(label = m.label, selected = selectedId == m.id, onClick = { onSelect(m.id) }) {
                onDelete(m.id)
            }
        }
        // Tap-to-segment 토글 — 켜진 동안 캔버스 단일 탭이 마스크 추가로 해석됨.
        val accent = Color(0xFF6BE3FF)
        val active = tapSegmentMode && tapSegmentEnabled
        val bg = when {
            active -> accent
            tapSegmentEnabled -> PhotoColors.DarkSurface
            else -> PhotoColors.DarkSurface
        }
        val fg = when {
            active -> PhotoColors.RunwayBlack
            tapSegmentEnabled -> PhotoColors.PureWhite
            else -> PhotoColors.MidSlate
        }
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(bg)
                .border(
                    1.dp,
                    if (active) accent else PhotoColors.BorderDark,
                    RoundedCornerShape(50),
                )
                .let { if (tapSegmentEnabled) it.clickable(onClick = onToggleTapSegment) else it }
                .padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
            Spacer(Modifier.size(4.dp))
            Text(
                text = when {
                    active -> "Tap on photo…"
                    tapSegmentEnabled -> "Tap to add"
                    else -> "Tap to add (no analysis)"
                },
                color = fg,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun TargetChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    accent: Color? = null,
    onDelete: (() -> Unit)? = null,
) {
    val bg = when {
        selected && accent != null -> accent
        selected -> PhotoColors.PureWhite
        else -> PhotoColors.DarkSurface
    }
    val fg = if (selected) PhotoColors.RunwayBlack else PhotoColors.PureWhite
    val borderColor = if (accent != null && !selected) accent.copy(alpha = 0.6f) else PhotoColors.BorderDark
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (onDelete != null) {
            Spacer(Modifier.size(6.dp))
            Box(
                modifier = Modifier.size(16.dp).clickable(onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Remove mask",
                    tint = fg,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

// ─── Tabs ─────────────────────────────────────────────────────────

@Composable
private fun TabStrip(tab: EditorTab, onTabChange: (EditorTab) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        for (t in EditorTab.entries) {
            val color = if (t == tab) PhotoColors.PureWhite else PhotoColors.MidSlate
            Column(
                modifier = Modifier.clickable { onTabChange(t) }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(t.icon, contentDescription = t.label, tint = color, modifier = Modifier.size(20.dp))
                Spacer(Modifier.height(4.dp))
                Text(
                    text = t.label,
                    color = color,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp,
                )
            }
        }
    }
}

@Composable
private fun AdjustControls(p: EditorParams) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SliderRow("Temperature", p.temperature) { p.temperature = it }
        SliderRow("Contrast", p.contrast) { p.contrast = it }
        SliderRow("Tint", p.tint) { p.tint = it }
        SliderRow("Saturation", p.saturation) { p.saturation = it }
        SliderRow("Brightness", p.brightness) { p.brightness = it }
        SliderRow("Exposure", p.exposure) { p.exposure = it }
        SliderRow("Highlights", p.highlights) { p.highlights = it }
        SliderRow("Shadows", p.shadows) { p.shadows = it }
    }
}

private val COLOR_NAMES = listOf("Red", "Orange", "Yellow", "Green", "Blue", "Navy", "Purple")
private val COLOR_SWATCHES = listOf(
    Color(0xFFE53935), Color(0xFFFB8C00), Color(0xFFFDD835),
    Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFF3949AB), Color(0xFF8E24AA),
)

@Composable
private fun ColorControls(p: EditorParams) {
    var selectedColor by remember { mutableStateOf(0) }
    val baseIdx = selectedColor * 3
    fun updateAt(offset: Int): (Float) -> Unit = { v ->
        val arr = p.colorTuning.copyOf()
        arr[baseIdx + offset] = v
        p.colorTuning = arr
        p.colorTuningEnabled = true
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("COLOR TUNING", style = MaterialTheme.typography.labelSmall, color = PhotoColors.CoolSilver)
            Spacer(Modifier.weight(1f))
            Switch(
                checked = p.colorTuningEnabled,
                onCheckedChange = { p.colorTuningEnabled = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = PhotoColors.RunwayBlack,
                    checkedTrackColor = PhotoColors.PureWhite,
                    uncheckedThumbColor = PhotoColors.MidSlate,
                    uncheckedTrackColor = PhotoColors.DarkSurface,
                    uncheckedBorderColor = PhotoColors.BorderDark,
                ),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (i in 0 until 7) {
                val selected = selectedColor == i
                Box(
                    modifier = Modifier
                        .size(30.dp).clip(CircleShape).background(COLOR_SWATCHES[i])
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) PhotoColors.PureWhite else PhotoColors.BorderDark,
                            shape = CircleShape,
                        )
                        .clickable { selectedColor = i },
                )
            }
        }
        Text(COLOR_NAMES[selectedColor], style = MaterialTheme.typography.titleLarge, color = PhotoColors.PureWhite)
        SliderRow("Hue", p.colorTuning[baseIdx], updateAt(0))
        SliderRow("Saturation", p.colorTuning[baseIdx + 1], updateAt(1))
        SliderRow("Luminance", p.colorTuning[baseIdx + 2], updateAt(2))
    }
}

@Composable
private fun FiltersRow(p: EditorParams) {
    Column(
        modifier = Modifier.fillMaxWidth().height(200.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("PRESETS", style = MaterialTheme.typography.labelSmall, color = PhotoColors.CoolSilver, modifier = Modifier.padding(start = 8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for (preset in FILTER_PRESETS) {
                FilterChip(preset = preset, onClick = { preset.applyTo(p) })
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = "Filter applies to the selected target (Global or active mask).",
            style = MaterialTheme.typography.bodySmall,
            color = PhotoColors.MidSlate,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun FilterChip(preset: FilterPreset, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.size(width = 80.dp, height = 100.dp),
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(preset.chipColor)
                .border(1.dp, PhotoColors.BorderDark, RoundedCornerShape(14.dp))
                .clickable(onClick = onClick),
        )
        Spacer(Modifier.height(6.dp))
        Text(preset.name, color = PhotoColors.PureWhite, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun CropControls(crop: CropTransform, onChange: (CropTransform) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().height(200.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("ASPECT RATIO", style = MaterialTheme.typography.labelSmall, color = PhotoColors.CoolSilver, modifier = Modifier.padding(start = 8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val current = AspectPreset.fromRatio(crop.aspectRatio)
            for (preset in AspectPreset.entries) {
                AspectChip(label = preset.label, selected = preset == current, onClick = {
                    onChange(crop.copy(aspectRatio = preset.ratio))
                })
            }
        }
        Text("ROTATE / FLIP", style = MaterialTheme.typography.labelSmall, color = PhotoColors.CoolSilver, modifier = Modifier.padding(start = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionChip(Icons.Outlined.RotateRight, "90°") { onChange(crop.copy(rotation = (crop.rotation + 90) % 360)) }
            ActionChip(Icons.Outlined.Flip, "Flip H") { onChange(crop.copy(flipH = !crop.flipH)) }
            ActionChip(Icons.Outlined.FlipCameraAndroid, "Flip V") { onChange(crop.copy(flipV = !crop.flipV)) }
            ActionChip(Icons.Outlined.RestartAlt, "Reset") { onChange(CropTransform()) }
        }
    }
}

@Composable
private fun AspectChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) PhotoColors.PureWhite else PhotoColors.DarkSurface
    val fg = if (selected) PhotoColors.RunwayBlack else PhotoColors.PureWhite
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50)).background(bg)
            .border(1.dp, PhotoColors.BorderDark, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ActionChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50)).background(PhotoColors.DarkSurface)
            .border(1.dp, PhotoColors.BorderDark, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = PhotoColors.PureWhite, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(6.dp))
        Text(label, color = PhotoColors.PureWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SliderRow(label: String, value: Float, onChange: (Float) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = PhotoColors.PureWhite)
            Spacer(Modifier.weight(1f))
            Text(
                text = "%+.0f".format(value),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = if (kotlin.math.abs(value) < 0.5f) PhotoColors.MidSlate else PhotoColors.PureWhite,
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = -100f..100f,
            colors = SliderDefaults.colors(
                thumbColor = PhotoColors.PureWhite,
                activeTrackColor = PhotoColors.PureWhite,
                inactiveTrackColor = PhotoColors.BorderDark,
            ),
        )
    }
}

// ─── Vibe (Gemini 자연어 편집) ────────────────────────────────────

@Composable
private fun VibeControls(
    prompt: String,
    onPromptChange: (String) -> Unit,
    busy: Boolean,
    status: String?,
    attachment: Bitmap?,
    onPickAttachment: () -> Unit,
    onClearAttachment: () -> Unit,
    onSubmit: () -> Unit,
) {
    val context = LocalContext.current
    val session = rememberSpeechSession()
    val haptic = LocalHapticFeedback.current

    // RECORD_AUDIO 권한 요청. 첫 press 에 grant 가 안 되어 있으면 한 번 다이얼로그.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 권한 결과는 다음 press 때 자연스럽게 반영. */ }

    // 부분 인식 텍스트를 실시간으로 prompt 필드에 흘려보냄.
    LaunchedEffect(session.recording, session.partial) {
        if (session.recording) onPromptChange(session.partial)
    }

    // 부드러운 펄스 — recording 중에는 rms 기반으로 확대.
    val pulseScale by animateFloatAsState(
        targetValue = if (session.recording) 1f + session.rms * 0.4f else 1f,
        animationSpec = tween(durationMillis = 80),
        label = "mic-pulse",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "VIBE — describe an edit",
            color = PhotoColors.CoolSilver,
            style = MaterialTheme.typography.labelSmall,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = prompt,
                onValueChange = onPromptChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        if (session.recording) "듣고 있어요…" else "예: 하늘을 좀 더 따뜻한 색으로",
                        color = PhotoColors.MidSlate,
                        fontSize = 13.sp,
                    )
                },
                enabled = !busy && !session.recording,
                singleLine = false,
                maxLines = 3,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = PhotoColors.PureWhite,
                    unfocusedTextColor = PhotoColors.PureWhite,
                    focusedBorderColor = PhotoColors.PureWhite,
                    unfocusedBorderColor = PhotoColors.BorderDark,
                    cursorColor = PhotoColors.PureWhite,
                    disabledTextColor = PhotoColors.PureWhite,
                    disabledBorderColor = Color(0xFFFF6B6B),
                ),
                shape = RoundedCornerShape(12.dp),
            )
            // Push-to-talk 마이크. 꾹 누르고 있으면 듣기 시작, 떼면 종료 + 자동 submit.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(
                        if (session.recording) Color(0xFFFF6B6B) else PhotoColors.DarkSurface,
                    )
                    .border(
                        1.dp,
                        if (session.recording) Color(0xFFFF6B6B) else PhotoColors.BorderDark,
                        CircleShape,
                    )
                    .pointerInput(busy) {
                        if (busy) return@pointerInput
                        detectTapGestures(
                            onPress = {
                                val granted = ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.RECORD_AUDIO,
                                ) == PackageManager.PERMISSION_GRANTED
                                if (!granted) {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    return@detectTapGestures
                                }
                                onPromptChange("")
                                val started = session.start { finalText ->
                                    onPromptChange(finalText)
                                    onSubmit()
                                }
                                if (started) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                                tryAwaitRelease()
                                if (session.recording) session.stop()
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Mic,
                    contentDescription = "Push-to-talk",
                    tint = if (session.recording) PhotoColors.PureWhite else PhotoColors.PureWhite,
                    modifier = Modifier.size(22.dp),
                )
            }
            // Gallery 첨부 — 참조 사진을 붙여 "이 사진처럼" 같은 발화에 쓴다.
            IconButton(
                onClick = onPickAttachment,
                enabled = !busy && !session.recording,
            ) {
                Icon(
                    Icons.Outlined.PhotoLibrary,
                    contentDescription = "Attach reference photo",
                    tint = if (attachment != null) Color(0xFF6BE3FF) else PhotoColors.PureWhite,
                )
            }
            IconButton(onClick = onSubmit, enabled = !busy && prompt.isNotBlank()) {
                if (busy) {
                    CircularProgressIndicator(
                        color = PhotoColors.PureWhite,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Icon(Icons.Outlined.Send, "Send", tint = PhotoColors.PureWhite)
                }
            }
        }
        // 첨부된 reference 가 있으면 작은 썸네일 + 제거 X 버튼.
        if (attachment != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFF6BE3FF).copy(alpha = 0.6f), RoundedCornerShape(8.dp)),
                ) {
                    Image(
                        bitmap = attachment.asImageBitmap(),
                        contentDescription = "Attached reference",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Text(
                    text = "Reference attached · 이 사진처럼 / 이 느낌으로 라고 말해보세요",
                    color = PhotoColors.CoolSilver,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClearAttachment, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Remove reference",
                        tint = PhotoColors.MidSlate,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        when {
            session.recording -> {
                Text(
                    text = "듣고 있어요… 손을 떼면 전송",
                    color = Color(0xFFFF8C8C),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            session.error != null -> {
                Text(
                    text = session.error.orEmpty(),
                    color = Color(0xFFFF8C8C),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            status != null -> {
                Text(
                    text = status,
                    color = PhotoColors.CoolSilver,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

// ─── Recipe (TFLite 직접 추론) ────────────────────────────────────

@Composable
private fun RecipeControls(
    reference: Bitmap?,
    busy: Boolean,
    status: String?,
    targetLabel: String,
    onPickReference: () -> Unit,
    onClearReference: () -> Unit,
    onApply: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "RECIPE — transfer a reference look",
            color = PhotoColors.CoolSilver,
            style = MaterialTheme.typography.labelSmall,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(PhotoColors.DarkSurface)
                    .border(1.dp, PhotoColors.BorderDark, RoundedCornerShape(10.dp))
                    .clickable(onClick = onPickReference),
                contentAlignment = Alignment.Center,
            ) {
                if (reference != null) {
                    Image(
                        bitmap = reference.asImageBitmap(),
                        contentDescription = "Reference photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        Icons.Outlined.PhotoLibrary,
                        contentDescription = "Pick reference",
                        tint = PhotoColors.MidSlate,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = if (reference != null) {
                        "Reference 색감을 분석해서 $targetLabel 에 적용합니다."
                    } else {
                        "갤러리에서 참조 사진을 골라주세요. 톤·색조가 $targetLabel 로 옮겨집니다."
                    },
                    color = if (reference != null) PhotoColors.PureWhite else PhotoColors.CoolSilver,
                    fontSize = 12.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = onApply,
                        enabled = !busy && reference != null,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PhotoColors.PureWhite,
                            contentColor = PhotoColors.RunwayBlack,
                            disabledContainerColor = PhotoColors.DarkSurface,
                            disabledContentColor = PhotoColors.MidSlate,
                        ),
                        shape = RoundedCornerShape(50),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                color = PhotoColors.RunwayBlack,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(14.dp),
                            )
                        } else {
                            Text("Apply", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (reference != null && !busy) {
                        TextButton(onClick = onClearReference) {
                            Text(
                                "Clear",
                                color = PhotoColors.MidSlate,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
        if (status != null) {
            Text(
                text = status,
                color = PhotoColors.CoolSilver,
                fontSize = 12.sp,
            )
        }
    }
}

/** Vibe prompt 한 번 처리 결과. */
private data class VibeApplyResult(
    val newMasks: List<Mask>,
    val newSelectedId: String?,
    /** 사용자에게 보여줄 상태 라인. */
    val status: String,
    /**
     * 다음 turn 때 Gemini history 에 model 응답으로 넘길 요약. 보통 [status] 와
     * 동일하지만, history-친화적 압축 형태로 따로 두기 위해 분리.
     */
    val summary: String,
)

/**
 * Gemini 에게 자연어 프롬프트를 보내 [VibeEdit] 들을 받아오고, 각 edit 을
 * 현재 마스크/global 상태에 반영한다.
 *
 * Gemini 가 "sky" 같은 라벨을 골랐는데 아직 마스크가 만들어져있지 않으면,
 * 자동 검출된 [DetectedInstance] 에서 매칭되는 첫 번째 인스턴스로 새 마스크를
 * 생성해서 추가. 매칭 인스턴스가 없으면 그 edit 은 무시.
 */
private suspend fun applyVibePrompt(
    vibeClient: VibeClient,
    generator: RecipeGenerator,
    prompt: String,
    history: List<VibeTurn>,
    referenceImage: Bitmap?,
    content: Bitmap,
    detectedInstances: List<DetectedInstance>,
    masks: List<Mask>,
    globalParams: EditorParams,
    /**
     * UI 에서 현재 사용자가 선택해둔 편집 target. 마스크가 선택돼 있으면 그 라벨,
     * Global 이면 "global". 모델이 region-ambiguous 한 발화 ("더 따뜻하게") 를 받았을
     * 때 여기에 default 함.
     */
    currentFocus: String,
): VibeApplyResult {
    // ── 1. 후보 라벨 목록 & 현재 값 직렬화 ─────────────────────────
    val detectionLabels = detectedInstances.map { it.label.lowercase() }.distinct()
    val maskLabels = masks.map { it.label.lowercase() }
    val candidateLabels = (maskLabels + detectionLabels).distinct()
    val targets = listOf("global") + candidateLabels

    val currentValues = buildMap<String, EditorParams> {
        put("global", globalParams)
        for (m in masks) put(m.label.lowercase(), m.params)
    }

    // ── 2. Gemini 호출 ────────────────────────────────────────────
    val response = runCatching {
        vibeClient.proposeEdits(
            userPrompt = prompt,
            availableTargets = targets,
            currentValues = currentValues,
            history = history,
            referenceImage = referenceImage,
            currentFocus = currentFocus,
        )
    }.getOrElse { e ->
        val msg = "Gemini error: ${e.message?.take(120)}"
        return VibeApplyResult(
            newMasks = masks,
            newSelectedId = null,
            status = msg,
            summary = "error: ${e.message?.take(80)}",
        )
    }
    val edits = response.edits
    if (edits.isEmpty()) {
        // Tool call 이 없으면 모델이 텍스트로 답한 경우가 많음 (거부 / 추가 질문).
        // 그 텍스트를 그대로 보여줘서 왜 안됐는지 알 수 있게.
        val refusal = response.message?.take(220)
        val status = refusal?.let { "Gemini: $it" } ?: "No edits proposed for: \"$prompt\""
        return VibeApplyResult(
            newMasks = masks,
            newSelectedId = null,
            status = status,
            // 모델이 응답한 텍스트를 그대로 history 에 — 다음 턴에 "왜 그랬어?" 류
            // 질문이 오더라도 직전 응답을 추적 가능.
            summary = refusal?.let { "(no edit) said: $it" } ?: "(no edit, ambiguous prompt)",
        )
    }

    // ── 3. 각 edit 을 적용 ────────────────────────────────────────
    val mutableMasks = masks.toMutableList()
    var firstAffectedId: String? = null
    val appliedSummary = StringBuilder()
    var skipped = 0

    for (edit in edits) {
        val target = edit.target
        val params: EditorParams? = if (target == "global") {
            globalParams
        } else {
            val existing = mutableMasks.firstOrNull { it.label.equals(target, ignoreCase = true) }
            if (existing != null) {
                existing.params
            } else {
                val inst = detectedInstances.firstOrNull {
                    it.label.equals(target, ignoreCase = true)
                }
                if (inst == null) {
                    skipped++
                    null
                } else {
                    val baseline = EditorParams().apply { copyValuesFrom(globalParams) }
                    val newMask = Mask(
                        id = "mask-${System.currentTimeMillis()}-${target}",
                        alphaBitmap = inst.alphaBitmap,
                        params = baseline,
                        label = inst.label.replaceFirstChar { it.uppercase() },
                    )
                    mutableMasks += newMask
                    if (firstAffectedId == null) firstAffectedId = newMask.id
                    baseline
                }
            }
        }
        if (params == null) continue

        val recipe = edit.recipeTransfer
        if (recipe != null) {
            if (referenceImage == null) {
                skipped++
                continue
            }
            val inferResult = runCatching {
                withContext(Dispatchers.IO) {
                    generator.infer(content = content, reference = referenceImage)
                }
            }
            val params29 = inferResult.getOrNull()
            if (params29 == null) {
                if (appliedSummary.isNotEmpty()) appliedSummary.append(" · ")
                appliedSummary.append(
                    "$target: recipe failed (${inferResult.exceptionOrNull()?.message?.take(60)})",
                )
                continue
            }
            params.applyInferred(
                model29 = params29,
                toneFactor = recipe.toneFactor,
                colorFactor = recipe.colorFactor,
            )

            if (target != "global" && firstAffectedId == null) {
                firstAffectedId = mutableMasks.firstOrNull { it.label.equals(target, ignoreCase = true) }?.id
            }

            if (appliedSummary.isNotEmpty()) appliedSummary.append(" · ")
            appliedSummary.append("$target: recipe transfer (tone=${recipe.toneFactor}, color=${recipe.colorFactor})")
            continue
        }

        applyChanges(params, edit.changes)
        applyColorTuning(params, edit.colorTuning)

        if (target != "global" && firstAffectedId == null) {
            firstAffectedId = mutableMasks.firstOrNull { it.label.equals(target, ignoreCase = true) }?.id
        }

        if (appliedSummary.isNotEmpty()) appliedSummary.append(" · ")
        appliedSummary.append(target).append(": ")
        val toneStr = edit.changes.entries.joinToString(", ") { (k, v) -> "$k=${v.toInt()}" }
        val tuneStr = edit.colorTuning.entries.joinToString(", ") { (band, channels) ->
            "$band(" + channels.entries.joinToString(",") { (c, v) -> "${c.first()}=${v.toInt()}" } + ")"
        }
        appliedSummary.append(
            listOf(toneStr, tuneStr).filter { it.isNotEmpty() }.joinToString(", "),
        )
    }

    val isEmpty = appliedSummary.isEmpty()
    val status = if (isEmpty) {
        "Skipped — no matching region for: \"$prompt\""
    } else {
        val tail = if (skipped > 0) " ($skipped skipped)" else ""
        "Applied: $appliedSummary$tail"
    }
    val summary = if (isEmpty) {
        "(no edit applied: no matching region)"
    } else {
        // history 용 — 모델이 다음 턴에 '직전에 뭐 했는지' 읽기 쉽도록.
        "applied $appliedSummary"
    }

    return VibeApplyResult(
        newMasks = mutableMasks,
        newSelectedId = firstAffectedId,
        status = status,
        summary = summary,
    )
}

private fun applyChanges(p: EditorParams, changes: Map<String, Float>) {
    for ((k, v) in changes) {
        when (k) {
            "temperature" -> p.temperature = v
            "contrast" -> p.contrast = v
            "tint" -> p.tint = v
            "saturation" -> p.saturation = v
            "brightness" -> p.brightness = v
            "exposure" -> p.exposure = v
            "highlights" -> p.highlights = v
            "shadows" -> p.shadows = v
        }
    }
}

private fun applyColorTuning(p: EditorParams, tuning: Map<String, Map<String, Float>>) {
    if (tuning.isEmpty()) return
    val next = p.colorTuning.copyOf()
    var changed = false
    for ((bandName, channels) in tuning) {
        val bi = com.example.photorecipe.vibe.VibeClient.BAND_INDEX[bandName] ?: continue
        for ((channelName, value) in channels) {
            val co = com.example.photorecipe.vibe.VibeClient.CHANNEL_OFFSET[channelName] ?: continue
            next[bi * 3 + co] = value.coerceIn(-100f, 100f)
            changed = true
        }
    }
    if (changed) {
        p.colorTuning = next
        p.colorTuningEnabled = true
    }
}
