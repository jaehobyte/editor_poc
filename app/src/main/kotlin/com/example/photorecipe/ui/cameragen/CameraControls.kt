package com.example.photorecipe.ui.cameragen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.FlashAuto
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.Portrait
import androidx.compose.material.icons.outlined.WbAuto
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.photorecipe.ui.theme.PhotoColors

@Composable
fun CameraTopBar(
    settings: CameraSettings,
    onSettingsChange: (CameraSettings) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var wbMenuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.Outlined.ArrowBack,
                contentDescription = "Back",
                tint = PhotoColors.PureWhite,
            )
        }
        Spacer(Modifier.weight(1f))

        // Flash cycle
        val flashIcon: ImageVector = when (settings.flash) {
            FlashSetting.OFF -> Icons.Outlined.FlashOff
            FlashSetting.AUTO -> Icons.Outlined.FlashAuto
            FlashSetting.ON -> Icons.Outlined.FlashOn
        }
        IconChip(
            icon = flashIcon,
            label = settings.flash.label,
            active = settings.flash != FlashSetting.OFF,
            onClick = {
                val next = when (settings.flash) {
                    FlashSetting.OFF -> FlashSetting.AUTO
                    FlashSetting.AUTO -> FlashSetting.ON
                    FlashSetting.ON -> FlashSetting.OFF
                }
                onSettingsChange(settings.copy(flash = next))
            },
        )

        // White balance dropdown
        Box {
            IconChip(
                icon = if (settings.wb == WbPreset.AUTO) Icons.Outlined.WbAuto else Icons.Outlined.WbSunny,
                label = settings.wb.label,
                active = settings.wb != WbPreset.AUTO,
                onClick = { wbMenuOpen = true },
            )
            DropdownMenu(
                expanded = wbMenuOpen,
                onDismissRequest = { wbMenuOpen = false },
                modifier = Modifier.background(PhotoColors.DarkSurface),
            ) {
                WbPreset.entries.forEach { wb ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                wb.label,
                                color = if (wb == settings.wb) PhotoColors.PureWhite else PhotoColors.CoolSilver,
                            )
                        },
                        onClick = {
                            onSettingsChange(settings.copy(wb = wb))
                            wbMenuOpen = false
                        },
                    )
                }
            }
        }

        // Portrait mode toggle
        IconChip(
            icon = Icons.Outlined.Portrait,
            label = "Portrait",
            active = settings.portraitMode,
            onClick = {
                onSettingsChange(settings.copy(portraitMode = !settings.portraitMode))
            },
        )

        // Lens flip
        IconButton(
            onClick = { onSettingsChange(settings.copy(lens = settings.lens.toggle())) },
        ) {
            Icon(
                imageVector = Icons.Outlined.Cameraswitch,
                contentDescription = "Flip camera",
                tint = PhotoColors.PureWhite,
            )
        }
    }
}

@Composable
private fun IconChip(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (active) PhotoColors.PureWhite.copy(alpha = 0.18f) else Color.Transparent
    val tint = if (active) PhotoColors.PureWhite else PhotoColors.CoolSilver
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, PhotoColors.PureWhite.copy(alpha = if (active) 0.4f else 0.1f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(4.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp,
        )
    }
}

/** 줌 배율 인디케이터 (핀치 줌 동안 화면 상단에 잠시 표시). */
@Composable
fun ZoomBadge(ratio: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = "%.1fx".format(ratio),
            color = PhotoColors.PureWhite,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * EV (노출 보정) + Manual 토글 + Manual ON 일 때 ISO/Shutter 슬라이더.
 *
 * @param evRange Camera 가 알려주는 보정 인덱스 범위. 없으면 [-6..+6] (1/3 스텝 가정).
 */
@Composable
fun AdvancedControls(
    settings: CameraSettings,
    onSettingsChange: (CameraSettings) -> Unit,
    evRange: IntRange,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // EV slider
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "EV",
                color = PhotoColors.CoolSilver,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.size(36.dp).padding(top = 4.dp),
            )
            Slider(
                value = settings.evIndex.toFloat(),
                onValueChange = { onSettingsChange(settings.copy(evIndex = it.toInt())) },
                valueRange = evRange.first.toFloat()..evRange.last.toFloat(),
                steps = (evRange.last - evRange.first - 1).coerceAtLeast(0),
                colors = sliderColors(),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "%+d".format(settings.evIndex),
                color = PhotoColors.PureWhite,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.size(36.dp).padding(top = 4.dp),
            )
        }

        // Manual toggle pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "MANUAL",
                style = MaterialTheme.typography.labelSmall,
                color = if (settings.manualMode) PhotoColors.PureWhite else PhotoColors.MidSlate,
            )
            Spacer(Modifier.weight(1f))
            val onBg = if (settings.manualMode) PhotoColors.PureWhite else PhotoColors.DarkSurface
            val onFg = if (settings.manualMode) PhotoColors.RunwayBlack else PhotoColors.CoolSilver
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(onBg)
                    .clickable {
                        onSettingsChange(settings.copy(manualMode = !settings.manualMode))
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = if (settings.manualMode) "ON" else "OFF",
                    color = onFg,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp,
                )
            }
        }

        if (settings.manualMode) {
            // ISO
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "ISO",
                    color = PhotoColors.CoolSilver,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.size(36.dp).padding(top = 4.dp),
                )
                Slider(
                    value = settings.isoIndex.toFloat(),
                    onValueChange = { onSettingsChange(settings.copy(isoIndex = it.toInt())) },
                    valueRange = 0f..(ISO_PRESETS.size - 1).toFloat(),
                    steps = (ISO_PRESETS.size - 2).coerceAtLeast(0),
                    colors = sliderColors(),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "%d".format(settings.iso),
                    color = PhotoColors.PureWhite,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.size(48.dp).padding(top = 4.dp),
                )
            }
            // Shutter speed
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "SHTR",
                    color = PhotoColors.CoolSilver,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.size(36.dp).padding(top = 4.dp),
                )
                Slider(
                    value = settings.shutterIndex.toFloat(),
                    onValueChange = { onSettingsChange(settings.copy(shutterIndex = it.toInt())) },
                    valueRange = 0f..(ShutterSpeed.PRESETS.size - 1).toFloat(),
                    steps = (ShutterSpeed.PRESETS.size - 2).coerceAtLeast(0),
                    colors = sliderColors(),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = settings.shutter.label,
                    color = PhotoColors.PureWhite,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.size(48.dp).padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = PhotoColors.PureWhite,
    activeTrackColor = PhotoColors.PureWhite,
    inactiveTrackColor = PhotoColors.BorderDark,
)
