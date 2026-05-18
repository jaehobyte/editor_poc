package com.example.photorecipe

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.photorecipe.tflite.RecipeGenerator
import com.example.photorecipe.ui.ImageGLView
import com.example.photorecipe.ui.theme.NewCamTheme
import com.example.photorecipe.util.downscaleForGL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NewCamTheme {
                Scaffold { padding ->
                    InferencePoc(modifier = Modifier.padding(padding))
                }
            }
        }
    }
}

@Composable
private fun InferencePoc(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var referenceUri by remember { mutableStateOf<Uri?>(null) }
    var inputUri by remember { mutableStateOf<Uri?>(null) }
    var status by remember { mutableStateOf("Reference, Input 사진을 모두 선택하세요.") }
    var params by remember { mutableStateOf<FloatArray?>(null) }
    var inputBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var temperatureUi by remember { mutableStateOf(0f) }
    var contrastUi by remember { mutableStateOf(0f) }
    var tintUi by remember { mutableStateOf(0f) }
    var saturationUi by remember { mutableStateOf(0f) }

    val pickRef = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) referenceUri = uri
    }
    val pickInput = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) inputUri = uri
    }

    val generator = remember { RecipeGenerator(context) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("newCam — TFLite Inference PoC", style = MaterialTheme.typography.titleLarge)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                pickRef.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) {
                Text(if (referenceUri == null) "Reference 선택" else "Reference ✓")
            }
            Button(onClick = {
                pickInput.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) {
                Text(if (inputUri == null) "Input 선택" else "Input ✓")
            }
        }

        Button(
            enabled = referenceUri != null && inputUri != null,
            onClick = {
                val refUri = referenceUri ?: return@Button
                val inUri = inputUri ?: return@Button
                status = "추론 중..."
                params = null
                inputBitmap = null
                scope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) {
                            val ref = context.contentResolver.openInputStream(refUri)!!.use {
                                BitmapFactory.decodeStream(it)
                            }
                            val inp = context.contentResolver.openInputStream(inUri)!!.use {
                                BitmapFactory.decodeStream(it)
                            }
                            val p = generator.infer(ref, inp)
                            p to downscaleForGL(inp)
                        }
                    }
                    result.fold(
                        onSuccess = { (p, bmp) ->
                            params = p
                            inputBitmap = bmp
                            status = "완료. 29개 파라미터:"
                        },
                        onFailure = { status = "에러: ${it.message}" },
                    )
                }
            }
        ) { Text("추론 실행") }

        Text(status)

        inputBitmap?.let { bmp ->
            ImageGLView(
                bitmap = bmp,
                temperatureUi = temperatureUi,
                contrastUi = contrastUi,
                tintUi = tintUi,
                saturationUi = saturationUi,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(bmp.width.toFloat() / bmp.height),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    temperatureUi = 0f
                    contrastUi = 0f
                    tintUi = 0f
                    saturationUi = 0f
                }) { Text("Reset all") }
                params?.let { p ->
                    Button(onClick = {
                        temperatureUi = p[0] * 100f
                        contrastUi = p[1] * 100f
                        tintUi = p[2] * 100f
                        saturationUi = p[3] * 100f
                    }) { Text("Apply inferred") }
                }
            }

            EffectSlider("Temperature", temperatureUi) { temperatureUi = it }
            EffectSlider("Contrast", contrastUi) { contrastUi = it }
            EffectSlider("Tint", tintUi) { tintUi = it }
            EffectSlider("Saturation", saturationUi) { saturationUi = it }
        }

        params?.let { p ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                p.forEachIndexed { i, v ->
                    Text(
                        text = "${PARAM_NAMES[i]}: %+.4f".format(v),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}


@Composable
private fun EffectSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Text("$label: %+.1f".format(value), fontFamily = FontFamily.Monospace)
    Slider(value = value, onValueChange = onChange, valueRange = -100f..100f)
}

private val PARAM_NAMES: List<String> = buildList {
    addAll(listOf("Temperature", "Contrast", "Tint", "Saturation",
                  "Brightness", "Exposure", "Highlights", "Shadows"))
    for (color in listOf("Red", "Orange", "Yellow", "Green", "Blue", "Navy", "Purple")) {
        add("$color.Hue")
        add("$color.Sat")
        add("$color.Lum")
    }
}
