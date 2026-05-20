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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.FlipCameraAndroid
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.photorecipe.EditorParams
import com.example.photorecipe.editor.applyRecipe
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
 * 단일 사진을 4탭 (Adjust/Color/Filters/Crop) 으로 편집.
 * GPU 프리뷰는 기존 ImageGLView 9-stage 셰이더를 재사용. 저장은 풀해상도 디코딩 →
 * Crop transform → CPU 레시피 → MediaStore.
 */
@Composable
fun PhotoEditorScreen(
    originalUri: Uri,
    previewBitmap: Bitmap,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val params = remember { EditorParams() }
    var crop by remember { mutableStateOf(CropTransform()) }
    var tab by remember { mutableStateOf(EditorTab.ADJUST) }
    var saving by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    // Crop 적용 결과 (프리뷰용) — crop 변경 시 백그라운드로 갱신
    var croppedPreview by remember { mutableStateOf(previewBitmap) }
    LaunchedEffect(previewBitmap, crop) {
        croppedPreview = withContext(Dispatchers.IO) {
            applyCropTransform(previewBitmap, crop)
        }
    }
    toast?.let { LaunchedEffect(it) { delay(3000); toast = null } }

    Column(
        modifier = modifier.fillMaxSize().background(PhotoColors.RunwayBlack),
    ) {
        TopBar(
            saving = saving,
            onBack = onBack,
            onReset = {
                params.reset()
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
                            val rendered = applyRecipe(cropped, params)
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
            ImageGLView(
                bitmap = croppedPreview,
                temperatureUi = params.temperature,
                contrastUi = params.contrast,
                tintUi = params.tint,
                saturationUi = params.saturation,
                brightnessUi = params.brightness,
                exposureUi = params.exposure,
                highlightsUi = params.highlights,
                shadowsUi = params.shadows,
                colorTuningParams21 = params.colorTuning,
                colorTuningOn = params.colorTuningEnabled,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(PhotoColors.DeepBlack)
                .padding(top = 8.dp),
        ) {
            TabStrip(tab = tab, onTabChange = { tab = it })
            Spacer(Modifier.height(8.dp))
            when (tab) {
                EditorTab.ADJUST -> AdjustControls(params)
                EditorTab.COLOR -> ColorControls(params)
                EditorTab.FILTERS -> FiltersRow(params)
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

// ─── Top bar ──────────────────────────────────────────────────────

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

// ─── Tab strip ────────────────────────────────────────────────────

@Composable
private fun TabStrip(tab: EditorTab, onTabChange: (EditorTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp),
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

// ─── Adjust (tone sliders) ────────────────────────────────────────

@Composable
private fun AdjustControls(p: EditorParams) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
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

// ─── Color (per-color HSL) ────────────────────────────────────────

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
            .height(260.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "COLOR TUNING",
                style = MaterialTheme.typography.labelSmall,
                color = PhotoColors.CoolSilver,
            )
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
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (i in 0 until 7) {
                val selected = selectedColor == i
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(COLOR_SWATCHES[i])
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) PhotoColors.PureWhite else PhotoColors.BorderDark,
                            shape = CircleShape,
                        )
                        .clickable { selectedColor = i },
                )
            }
        }
        Text(
            text = COLOR_NAMES[selectedColor],
            style = MaterialTheme.typography.titleLarge,
            color = PhotoColors.PureWhite,
        )
        SliderRow("Hue", p.colorTuning[baseIdx], updateAt(0))
        SliderRow("Saturation", p.colorTuning[baseIdx + 1], updateAt(1))
        SliderRow("Luminance", p.colorTuning[baseIdx + 2], updateAt(2))
    }
}

// ─── Filters (preset row) ─────────────────────────────────────────

@Composable
private fun FiltersRow(p: EditorParams) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "PRESETS",
            style = MaterialTheme.typography.labelSmall,
            color = PhotoColors.CoolSilver,
            modifier = Modifier.padding(start = 8.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for (preset in FILTER_PRESETS) {
                FilterChip(preset = preset, onClick = { preset.applyTo(p) })
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = "Tap a preset to apply. Open ADJUST or COLOR to fine-tune.",
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
        modifier = Modifier.widthIn(min = 76.dp),
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
        Text(
            text = preset.name,
            color = PhotoColors.PureWhite,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ─── Crop ─────────────────────────────────────────────────────────

@Composable
private fun CropControls(crop: CropTransform, onChange: (CropTransform) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "ASPECT RATIO",
            style = MaterialTheme.typography.labelSmall,
            color = PhotoColors.CoolSilver,
            modifier = Modifier.padding(start = 8.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val current = AspectPreset.fromRatio(crop.aspectRatio)
            for (preset in AspectPreset.entries) {
                AspectChip(
                    label = preset.label,
                    selected = preset == current,
                    onClick = { onChange(crop.copy(aspectRatio = preset.ratio)) },
                )
            }
        }

        Text(
            text = "ROTATE / FLIP",
            style = MaterialTheme.typography.labelSmall,
            color = PhotoColors.CoolSilver,
            modifier = Modifier.padding(start = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionChip(icon = Icons.Outlined.RotateRight, label = "90°") {
                onChange(crop.copy(rotation = (crop.rotation + 90) % 360))
            }
            ActionChip(icon = Icons.Outlined.Flip, label = "Flip H") {
                onChange(crop.copy(flipH = !crop.flipH))
            }
            ActionChip(icon = Icons.Outlined.FlipCameraAndroid, label = "Flip V") {
                onChange(crop.copy(flipV = !crop.flipV))
            }
            ActionChip(icon = Icons.Outlined.RestartAlt, label = "Reset") {
                onChange(CropTransform())
            }
        }
    }
}

@Composable
private fun AspectChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) PhotoColors.PureWhite else PhotoColors.DarkSurface
    val fg = if (selected) PhotoColors.RunwayBlack else PhotoColors.PureWhite
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, PhotoColors.BorderDark, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun ActionChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(PhotoColors.DarkSurface)
            .border(1.dp, PhotoColors.BorderDark, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = PhotoColors.PureWhite, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(6.dp))
        Text(
            text = label,
            color = PhotoColors.PureWhite,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ─── Shared SliderRow ─────────────────────────────────────────────

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
