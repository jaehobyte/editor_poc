package com.example.photorecipe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.photorecipe.gemini.GeminiImageClient
import com.example.photorecipe.tflite.RecipeGenerator
import com.example.photorecipe.ui.AppRoute
import com.example.photorecipe.ui.HomeScreen
import com.example.photorecipe.ui.PhotoRecipeFlow
import com.example.photorecipe.ui.cameragen.CameraGenFlow
import com.example.photorecipe.ui.theme.NewCamTheme
import com.example.photorecipe.ui.theme.PhotoColors

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            NewCamTheme {
                Scaffold(containerColor = PhotoColors.RunwayBlack) { padding ->
                    AppRoot(modifier = Modifier.fillMaxSize().padding(padding))
                }
            }
        }
    }
}

/**
 * 최상위 라우터: Home(비눗방울) ↔ Feature.
 *
 * RecipeGenerator / GeminiImageClient 은 여기서 한 번 만들어서 보관 — feature
 * 진입/이탈 사이에 300MB 모델 재로딩이 일어나지 않도록.
 */
@Composable
private fun AppRoot(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val generator = remember { RecipeGenerator(context) }
    val gemini = remember { GeminiImageClient(BuildConfig.GEMINI_KEY) }

    var route by remember { mutableStateOf<AppRoute>(AppRoute.Home) }

    Box(modifier = modifier.background(PhotoColors.RunwayBlack)) {
        when (route) {
            AppRoute.Home -> HomeScreen(
                onPick = { route = it },
                modifier = Modifier.fillMaxSize(),
            )
            AppRoute.PhotoRecipe -> PhotoRecipeFlow(
                generator = generator,
                gemini = gemini,
                onExit = { route = AppRoute.Home },
                modifier = Modifier.fillMaxSize(),
            )
            AppRoute.CameraGen -> CameraGenFlow(
                gemini = gemini,
                onExit = { route = AppRoute.Home },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
