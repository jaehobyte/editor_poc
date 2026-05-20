package com.example.photorecipe.ui.photoeditor

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.FlipCameraAndroid
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.photorecipe.EditorParams
import com.example.photorecipe.editor.applyRecipeMasked
import com.example.photorecipe.segmentation.SegmentationEngine
import com.example.photorecipe.ui.ImageGLView
import com.example.photorecipe.ui.theme.PhotoColors
import com.example.photorecipe.util.decodeBitmapForExport
import com.example.photorecipe.util.saveBitmapToGallery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class EditorTab(val label: String, val icon: ImageVector) {
    ADJUST("ADJUST", Icons.Outlined.Tune),
    COLOR("COLOR", Icons.Outlined.Palette),
    FILTERS("FILTERS", Icons.Outlined.Photo),
    CROP("CROP", Icons.Outlined.Crop),
}

/**
 * PhotoEditor 메인:
 * - 한 장 사진에 4탭 보정 + 객체 단위 마스크 보정 지원.
 * - 비프리뷰 영역을 길게 누르면 그 지점에 InteractiveSegmenter 가 마스크 생성.
 * - 마스크 칩 (Global / Mask 1 / Mask 2 / ...) 으로 슬라이더 대상 전환.
 */
@Composable
fun PhotoEditorScreen(
    originalUri: Uri,
    previewBitmap: Bitmap,
    segmenter: SegmentationEngine,
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
    var segmenting by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }

    // Crop 적용된 프리뷰 + 마스크
    var croppedPreview by remember { mutableStateOf(previewBitmap) }
    LaunchedEffect(previewBitmap, crop) {
        croppedPreview = withContext(Dispatchers.IO) { applyCropTransform(previewBitmap, crop) }
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
                                val scaled = Bitmap.createScaledBitmap(
                                    m.alphaBitmap, cropped.width, cropped.height, true,
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
                .background(PhotoColors.Canvas)
                .onSizeChanged { previewSize = it }
                .pointerInput(croppedPreview) {
                    detectTapGestures(
                        onLongPress = { pos ->
                            // 좌표 → 정규화 좌표 (이미지 영역 letterbox 고려)
                            val (nx, ny) = mapToImageNorm(
                                pos, previewSize, croppedPreview.width, croppedPreview.height,
                            ) ?: return@detectTapGestures
                            segmenting = true
                            scope.launch {
                                val r = runCatching {
                                    withContext(Dispatchers.IO) {
                                        segmenter.segment(croppedPreview, nx, ny)
                                    }
                                }
                                segmenting = false
                                r.fold(
                                    onSuccess = { alpha ->
                                        // 새 마스크의 파라미터는 현재 global 값 복사로 시작 — 사용자가
                                        // 슬라이더로 마스크 파라미터를 바꾸자마자 해당 영역에 변화가 보이도록.
                                        // (모두 0 으로 시작하면 global 효과까지 사라져서 슬라이더가 동작하지
                                        // 않는 것처럼 보임.)
                                        val maskParams = EditorParams().apply {
                                            copyValuesFrom(globalParams)
                                        }
                                        val newMask = Mask(
                                            id = "mask-${System.currentTimeMillis()}",
                                            alphaBitmap = alpha,
                                            params = maskParams,
                                            label = "Mask ${masks.size + 1}",
                                        )
                                        masks = masks + newMask
                                        selectedMaskId = newMask.id
                                    },
                                    onFailure = { toast = "Segmentation failed: ${it.message}" },
                                )
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            ImageGLView(
                bitmap = croppedPreview,
                global = globalParams,
                maskBitmap = activeMask?.alphaBitmap,
                mask = activeMask?.params,
                modifier = Modifier.fillMaxSize(),
            )

            // 선택된 마스크의 영역을 색 오버레이로 표시. 생성 직후엔 살짝 더 진하게
            // (flash), 잠시 후 옅어져서 보정 결과를 가리지 않도록.
            activeMask?.let { m ->
                var flashing by remember(m.id) { mutableStateOf(true) }
                LaunchedEffect(m.id) {
                    flashing = true
                    delay(700)
                    flashing = false
                }
                val overlayAlpha by animateFloatAsState(
                    targetValue = if (flashing) 0.55f else 0.22f,
                    animationSpec = tween(durationMillis = 700),
                    label = "mask-overlay-alpha",
                )
                Image(
                    bitmap = m.alphaBitmap.asImageBitmap(),
                    contentDescription = "Selected mask region",
                    contentScale = ContentScale.Fit,
                    colorFilter = ColorFilter.tint(Color(0xFFFF8A3D), BlendMode.SrcIn),
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(overlayAlpha),
                )
            }

            if (segmenting) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = PhotoColors.PureWhite)
                }
            }
            // 힌트 텍스트: 마스크가 없을 때만
            if (masks.isEmpty() && !segmenting) {
                Text(
                    text = "Long-press the image to mask an object",
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
            MaskSelectorRow(
                masks = masks,
                selectedId = selectedMaskId,
                onSelect = { selectedMaskId = it },
                onDelete = { id ->
                    masks = masks.filter { it.id != id }
                    if (selectedMaskId == id) selectedMaskId = null
                },
            )
            TabStrip(tab = tab, onTabChange = { tab = it })
            Spacer(Modifier.height(8.dp))
            when (tab) {
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

/**
 * Compose 영역 좌표 (px) → 이미지 정규화 좌표 [0,1] x [0,1].
 * ImageGLView 가 letterbox 로 그리므로 viewport 외 영역은 null 반환.
 */
private fun mapToImageNorm(
    pos: Offset,
    viewSize: IntSize,
    imageW: Int,
    imageH: Int,
): Pair<Float, Float>? {
    if (viewSize.width == 0 || viewSize.height == 0) return null
    val viewAspect = viewSize.width.toFloat() / viewSize.height
    val imageAspect = imageW.toFloat() / imageH
    val (vw, vh) = if (imageAspect > viewAspect) {
        viewSize.width to (viewSize.width / imageAspect).toInt()
    } else {
        (viewSize.height * imageAspect).toInt() to viewSize.height
    }
    val vx = (viewSize.width - vw) / 2
    val vy = (viewSize.height - vh) / 2
    val lx = pos.x - vx
    val ly = pos.y - vy
    if (lx < 0f || ly < 0f || lx > vw || ly > vh) return null
    return (lx / vw).coerceIn(0f, 1f) to (ly / vh).coerceIn(0f, 1f)
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

// ─── Mask selector row ────────────────────────────────────────────

@Composable
private fun MaskSelectorRow(
    masks: List<Mask>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onDelete: (String) -> Unit,
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
        // "+ New" 안내 (실제 추가는 프리뷰 영역 long-press)
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(PhotoColors.DarkSurface)
                .border(1.dp, PhotoColors.BorderDark, RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null, tint = PhotoColors.MidSlate, modifier = Modifier.size(14.dp))
            Spacer(Modifier.size(4.dp))
            Text(
                text = "Long-press to add",
                color = PhotoColors.MidSlate,
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
    onDelete: (() -> Unit)? = null,
) {
    val bg = if (selected) PhotoColors.PureWhite else PhotoColors.DarkSurface
    val fg = if (selected) PhotoColors.RunwayBlack else PhotoColors.PureWhite
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, PhotoColors.BorderDark, RoundedCornerShape(50))
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
