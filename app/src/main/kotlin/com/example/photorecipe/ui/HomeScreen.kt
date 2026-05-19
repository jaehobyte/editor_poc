package com.example.photorecipe.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.photorecipe.ui.theme.PhotoColors

/**
 * 메인 launcher. [FEATURES] 의 각 항목을 부유하는 비눗방울로 렌더링.
 * - 짧은 탭 → 해당 feature 진입
 * - 길게 누름 → 햅틱 피드백 후 드래그로 위치 이동 (in-memory 저장)
 */
@Composable
fun HomeScreen(onPick: (AppRoute) -> Unit, modifier: Modifier = Modifier) {
    // 사용자 드래그로 인한 추가 offset (route id → Offset).
    var dragOffsets by remember { mutableStateOf(mapOf<String, Offset>()) }
    var draggingId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PhotoColors.RunwayBlack)
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(64.dp))
        Text(
            text = "Photo Recipe\nGenerator",
            style = MaterialTheme.typography.displayLarge,
            color = PhotoColors.PureWhite,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Tap a feature. Long-press to move.",
            style = MaterialTheme.typography.bodyMedium,
            color = PhotoColors.MidSlate,
        )

        Box(modifier = Modifier.fillMaxSize()) {
            FEATURES.forEachIndexed { i, card ->
                val align = BUBBLE_POSITIONS[i % BUBBLE_POSITIONS.size]
                val id = card.route.toString()
                val extra = dragOffsets[id] ?: Offset.Zero
                FloatingBubble(
                    card = card,
                    phase = i,
                    isDragging = draggingId == id,
                    extraOffset = extra,
                    onClick = { onPick(card.route) },
                    onDragStart = { draggingId = id },
                    onDrag = { delta ->
                        val cur = dragOffsets[id] ?: Offset.Zero
                        dragOffsets = dragOffsets + (id to (cur + delta))
                    },
                    onDragEnd = { draggingId = null },
                    modifier = Modifier.align(align),
                )
            }
        }
    }
}

private val BUBBLE_POSITIONS: List<Alignment> = listOf(
    Alignment.Center,
    BiasAlignment(-0.6f, -0.4f),
    BiasAlignment(0.65f, 0.25f),
    BiasAlignment(-0.5f, 0.55f),
    BiasAlignment(0.55f, -0.55f),
)

@Composable
private fun FloatingBubble(
    card: FeatureCard,
    phase: Int,
    isDragging: Boolean,
    extraOffset: Offset,
    onClick: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "bubble-$phase")

    // 자동 부유 애니메이션 — 드래그 중에는 0 으로 고정.
    val yOffsetAnim by transition.animateFloat(
        initialValue = -10f, targetValue = 10f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000 + phase * 350, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "y",
    )
    val xOffsetAnim by transition.animateFloat(
        initialValue = -5f, targetValue = 5f,
        animationSpec = infiniteRepeatable(
            animation = tween(4500 + phase * 280, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "x",
    )
    val breathScaleAnim by transition.animateFloat(
        initialValue = 0.96f, targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600 + phase * 240, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scale",
    )

    val drift = if (isDragging) 0f else 1f
    val driftedX = xOffsetAnim * drift
    val driftedY = yOffsetAnim * drift
    val targetScale = if (isDragging) 1.12f else breathScaleAnim
    val scale by animateFloatAsState(targetValue = targetScale, label = "drag-scale")
    val borderAlpha = if (isDragging) 0.9f else 0.32f

    val haptic = LocalHapticFeedback.current

    Box(
        modifier = modifier
            .offset {
                IntOffset(
                    x = (driftedX.dp.toPx() + extraOffset.x).toInt(),
                    y = (driftedY.dp.toPx() + extraOffset.y).toInt(),
                )
            }
            .scale(scale)
            .size(160.dp)
            .clip(CircleShape)
            .background(Brush.radialGradient(colors = card.gradient, radius = 240f))
            .border(2.dp, Color.White.copy(alpha = borderAlpha), CircleShape)
            // 짧은 탭 → 진입
            .pointerInput(card.route) {
                detectTapGestures(onTap = { onClick() })
            }
            // 길게 누름 → 드래그로 이동 (이 detector 는 long-press 후 활성화되므로
            // 위의 detectTapGestures 와 충돌하지 않음).
            .pointerInput(card.route) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDragStart()
                    },
                    onDrag = { _, delta -> onDrag(delta) },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = card.name,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                lineHeight = 24.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = card.subtitle,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                letterSpacing = 0.4.sp,
            )
        }
    }
}
