package com.example.photorecipe.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Palette
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.photorecipe.EditorParams
import com.example.photorecipe.editor.colorAnimationFactor
import com.example.photorecipe.editor.toneAnimationFactor
import com.example.photorecipe.ui.theme.PhotoColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class EditorTab { TONE, COLOR }

@Composable
fun EditorScreen(
    inputBitmap: Bitmap,
    inferred: FloatArray,
    params: EditorParams,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    stylizedReference: Bitmap? = null,
) {
    val scope = rememberCoroutineScope()
    var animJob: Job? by remember { mutableStateOf(null) }
    var compareDown by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(EditorTab.TONE) }

    // 초기 진입 시 자동으로 1회 애니메이션 적용 — "magic moment".
    LaunchedEffect(Unit) {
        animJob = scope.launch { runRecipeAnimation(params, inferred) }
    }

    // 시스템 뒤로가기 (제스처/하드웨어) → Picker 로 복귀.
    // PickState 는 MainActivity 의 remember 로 유지되므로 Reference/Input 그대로 표시됨.
    BackHandler {
        animJob?.cancel()
        onBack()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PhotoColors.RunwayBlack),
    ) {
        TopBar(
            onBack = {
                animJob?.cancel()
                onBack()
            },
            onReplay = {
                animJob?.cancel()
                animJob = scope.launch { runRecipeAnimation(params, inferred) }
            },
            onReset = {
                animJob?.cancel()
                params.reset()
            },
        )

        // 이미지 영역 — 풀블리드, hold 으로 원본 비교.
        val showOriginal = compareDown
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(PhotoColors.Canvas)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            compareDown = true
                            tryAwaitRelease()
                            compareDown = false
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            ImageGLView(
                bitmap = inputBitmap,
                temperatureUi = if (showOriginal) 0f else params.temperature,
                contrastUi    = if (showOriginal) 0f else params.contrast,
                tintUi        = if (showOriginal) 0f else params.tint,
                saturationUi  = if (showOriginal) 0f else params.saturation,
                brightnessUi  = if (showOriginal) 0f else params.brightness,
                exposureUi    = if (showOriginal) 0f else params.exposure,
                highlightsUi  = if (showOriginal) 0f else params.highlights,
                shadowsUi     = if (showOriginal) 0f else params.shadows,
                colorTuningParams21 = params.colorTuning,
                colorTuningOn = params.colorTuningEnabled && !showOriginal,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(inputBitmap.width.toFloat() / inputBitmap.height),
            )
            if (showOriginal) {
                CompareBadge()
            }
            // Gemini 가 생성한 stylized reference 가 있으면 우상단에 작은 썸네일.
            if (stylizedReference != null) {
                StylizedThumbnail(
                    bitmap = stylizedReference,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp),
                )
            }
        }

        EditorControls(
            tab = tab,
            onTabChange = { tab = it },
            params = params,
            onUserEdit = { animJob?.cancel() },
        )
    }
}

private suspend fun runRecipeAnimation(params: EditorParams, inferred: FloatArray) {
    params.colorTuningEnabled = true
    Animatable(0f).animateTo(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 3000, easing = LinearEasing),
    ) {
        val tf = toneAnimationFactor(value)
        val cf = colorAnimationFactor(value)
        params.applyInferred(inferred, toneFactor = tf, colorFactor = cf)
    }
}

@Composable
private fun TopBar(onBack: () -> Unit, onReplay: () -> Unit, onReset: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopIcon(icon = Icons.Outlined.ArrowBack, label = "Back", onClick = onBack)
        Spacer(Modifier.weight(1f))
        Text(
            text = "EDITING",
            style = MaterialTheme.typography.labelSmall,
            color = PhotoColors.MidSlate,
        )
        Spacer(Modifier.weight(1f))
        TopIcon(icon = Icons.Outlined.RestartAlt, label = "Reset", onClick = onReset)
        TopIcon(icon = Icons.Outlined.Replay, label = "Replay", onClick = onReplay)
    }
}

@Composable
private fun TopIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(imageVector = icon, contentDescription = label, tint = PhotoColors.PureWhite)
    }
}

@Composable
private fun StylizedThumbnail(bitmap: Bitmap, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
    ) {
        Text(
            text = "AI REFERENCE",
            color = PhotoColors.PureWhite,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(50))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
        Spacer(Modifier.size(4.dp))
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Stylized reference from Gemini",
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, PhotoColors.PureWhite.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
        )
    }
}

@Composable
private fun CompareBadge() {
    Row(
        modifier = Modifier
            .padding(16.dp)
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Compare,
            contentDescription = null,
            tint = PhotoColors.PureWhite,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = "ORIGINAL",
            color = PhotoColors.PureWhite,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
        )
    }
}

@Composable
private fun EditorControls(
    tab: EditorTab,
    onTabChange: (EditorTab) -> Unit,
    params: EditorParams,
    onUserEdit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PhotoColors.DeepBlack)
            .padding(top = 8.dp),
    ) {
        TabStrip(tab = tab, onTabChange = onTabChange)
        Spacer(Modifier.height(8.dp))
        when (tab) {
            EditorTab.TONE -> ToneControls(params, onUserEdit)
            EditorTab.COLOR -> ColorControls(params, onUserEdit)
        }
    }
}

@Composable
private fun TabStrip(tab: EditorTab, onTabChange: (EditorTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        TabItem(label = "TONE", icon = Icons.Outlined.Tune, active = tab == EditorTab.TONE) {
            onTabChange(EditorTab.TONE)
        }
        TabItem(label = "COLOR", icon = Icons.Outlined.Palette, active = tab == EditorTab.COLOR) {
            onTabChange(EditorTab.COLOR)
        }
    }
}

@Composable
private fun TabItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    onClick: () -> Unit,
) {
    val color = if (active) PhotoColors.PureWhite else PhotoColors.MidSlate
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
        )
    }
}

@Composable
private fun ToneControls(p: EditorParams, onUserEdit: () -> Unit) {
    fun wrap(setter: (Float) -> Unit): (Float) -> Unit = { v ->
        onUserEdit()
        setter(v)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SliderRow("Temperature", p.temperature, wrap { p.temperature = it })
        SliderRow("Contrast", p.contrast, wrap { p.contrast = it })
        SliderRow("Tint", p.tint, wrap { p.tint = it })
        SliderRow("Saturation", p.saturation, wrap { p.saturation = it })
        SliderRow("Brightness", p.brightness, wrap { p.brightness = it })
        SliderRow("Exposure", p.exposure, wrap { p.exposure = it })
        SliderRow("Highlights", p.highlights, wrap { p.highlights = it })
        SliderRow("Shadows", p.shadows, wrap { p.shadows = it })
    }
}

@Composable
private fun ColorControls(p: EditorParams, onUserEdit: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "COLOR TUNING",
                style = MaterialTheme.typography.labelSmall,
                color = PhotoColors.CoolSilver,
            )
            Spacer(Modifier.weight(1f))
            Switch(
                checked = p.colorTuningEnabled,
                onCheckedChange = {
                    onUserEdit()
                    p.colorTuningEnabled = it
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = PhotoColors.RunwayBlack,
                    checkedTrackColor = PhotoColors.PureWhite,
                    uncheckedThumbColor = PhotoColors.MidSlate,
                    uncheckedTrackColor = PhotoColors.DarkSurface,
                    uncheckedBorderColor = PhotoColors.BorderDark,
                ),
            )
        }
        Text(
            text = "Per-color HSL shifts inferred from the reference. Toggle off to compare against tone-only.",
            style = MaterialTheme.typography.bodySmall,
            color = PhotoColors.MidSlate,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "INFERRED SHIFTS",
            style = MaterialTheme.typography.labelSmall,
            color = PhotoColors.CoolSilver,
        )
        ColorShiftGrid(p.colorTuning)
    }
}

private val COLOR_NAMES = listOf("Red", "Orange", "Yellow", "Green", "Blue", "Navy", "Purple")
private val COLOR_SWATCHES = listOf(
    Color(0xFFE53935), Color(0xFFFB8C00), Color(0xFFFDD835),
    Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFF3949AB), Color(0xFF8E24AA),
)

@Composable
private fun ColorShiftGrid(colorTuning: FloatArray) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0 until 7) {
            val h = colorTuning[i * 3]
            val s = colorTuning[i * 3 + 1]
            val l = colorTuning[i * 3 + 2]
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(RoundedCornerShape(50))
                        .background(COLOR_SWATCHES[i]),
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    text = COLOR_NAMES[i],
                    style = MaterialTheme.typography.bodyMedium,
                    color = PhotoColors.PureWhite,
                    modifier = Modifier.widthIn(min = 64.dp),
                )
                Spacer(Modifier.weight(1f))
                NumChip("H", h)
                Spacer(Modifier.size(4.dp))
                NumChip("S", s)
                Spacer(Modifier.size(4.dp))
                NumChip("L", l)
            }
        }
    }
}

@Composable
private fun NumChip(label: String, value: Float) {
    val muted = kotlin.math.abs(value) < 0.5f
    val textColor = if (muted) PhotoColors.MidSlate else PhotoColors.PureWhite
    Row(
        modifier = Modifier
            .background(PhotoColors.DarkSurface, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = PhotoColors.MidSlate,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = "%+.0f".format(value),
            color = textColor,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun SliderRow(label: String, value: Float, onChange: (Float) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = PhotoColors.PureWhite,
            )
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

