package com.example.photorecipe.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.photorecipe.ui.theme.PhotoColors

/**
 * 메인 launcher. [FEATURES] 의 각 항목을 부유하는 비눗방울로 렌더링,
 * 탭하면 해당 feature 의 [AppRoute] 로 진입.
 */
@Composable
fun HomeScreen(onPick: (AppRoute) -> Unit, modifier: Modifier = Modifier) {
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
            text = "Pick a feature.",
            style = MaterialTheme.typography.bodyMedium,
            color = PhotoColors.MidSlate,
        )

        Box(
            modifier = Modifier
                .fillMaxSize(),
        ) {
            FEATURES.forEachIndexed { i, card ->
                val align = BUBBLE_POSITIONS[i % BUBBLE_POSITIONS.size]
                FloatingBubble(
                    card = card,
                    phase = i,
                    modifier = Modifier.align(align),
                    onClick = { onPick(card.route) },
                )
            }
        }
    }
}

/**
 * Feature 가 늘어날 때를 대비해 미리 비대칭 위치를 5개 준비.
 * (Center, 좌상, 우중, 좌하, 우상) — 시각적으로 흩어진 느낌이 들도록.
 */
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "bubble-$phase")

    // 각 버블마다 다른 주기/위상을 줘서 동기화되지 않도록 — 진짜 비눗방울처럼.
    val yOffset by transition.animateFloat(
        initialValue = -10f, targetValue = 10f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000 + phase * 350, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "y",
    )
    val xOffset by transition.animateFloat(
        initialValue = -5f, targetValue = 5f,
        animationSpec = infiniteRepeatable(
            animation = tween(4500 + phase * 280, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "x",
    )
    val breathScale by transition.animateFloat(
        initialValue = 0.96f, targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600 + phase * 240, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scale",
    )

    Box(
        modifier = modifier
            .offset(x = xOffset.dp, y = yOffset.dp)
            .scale(breathScale)
            .size(160.dp)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = card.gradient,
                    radius = 240f,
                ),
            )
            .border(2.dp, Color.White.copy(alpha = 0.32f), CircleShape)
            .clickable(onClick = onClick),
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
