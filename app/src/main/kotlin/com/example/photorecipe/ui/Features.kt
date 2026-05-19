package com.example.photorecipe.ui

import androidx.compose.ui.graphics.Color

/** 최상위 화면 라우팅 — 비눗방울 홈에서 어느 feature 에 들어왔는지 식별. */
sealed interface AppRoute {
    data object Home : AppRoute
    data object PhotoRecipe : AppRoute
    data object CameraGen : AppRoute
}

/** Home 화면에 비눗방울로 띄울 feature 메타데이터. */
data class FeatureCard(
    val route: AppRoute,
    val name: String,
    val subtitle: String,
    val gradient: List<Color>,
)

/**
 * 등록된 feature 목록. 새 feature 추가 시 여기에 한 줄만 더 넣고
 * `MainActivity` 의 `when (route)` 분기에 1개 case 추가하면 끝.
 */
val FEATURES: List<FeatureCard> = listOf(
    FeatureCard(
        route = AppRoute.PhotoRecipe,
        name = "Photo\nRecipe",
        subtitle = "AI style transfer",
        gradient = listOf(
            Color(0xFFFFD27A),
            Color(0xFFFF6B6B),
            Color(0xFFB26BFF),
        ),
    ),
    FeatureCard(
        route = AppRoute.CameraGen,
        name = "Camera\nPrompt",
        subtitle = "Shoot, ask, generate",
        gradient = listOf(
            Color(0xFF6BE3FF),
            Color(0xFF6B7BFF),
            Color(0xFF9D6BFF),
        ),
    ),
)
