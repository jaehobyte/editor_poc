package com.example.photorecipe.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.photorecipe.ui.theme.PhotoColors

/**
 * 첫 화면: 두 사진(Reference, Input) 선택 → "Generate" 진행.
 *
 * @param canGenerate 두 사진이 모두 선택되었고 추론이 진행 가능한 상태
 * @param isGenerating 추론 실행 중 (Generate 버튼 비활성/스피너)
 */
@Composable
fun PickerScreen(
    referenceUri: Uri?,
    inputUri: Uri?,
    isGenerating: Boolean,
    isStylizing: Boolean,
    onPickReference: (Uri) -> Unit,
    onPickInput: (Uri) -> Unit,
    onGenerate: () -> Unit,
    onStylizeAndGenerate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pickRef = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) onPickReference(uri) }
    val pickIn = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) onPickInput(uri) }

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
            text = "Pick a reference and your photo.\nWe'll match the look.",
            style = MaterialTheme.typography.bodyMedium,
            color = PhotoColors.MidSlate,
        )

        Spacer(Modifier.height(40.dp))

        PickerCard(
            label = "REFERENCE",
            hint = "Tap to pick the look you want",
            uri = referenceUri,
            onClick = {
                pickRef.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
        )
        Spacer(Modifier.height(16.dp))
        PickerCard(
            label = "INPUT",
            hint = "Tap to pick the photo to edit",
            uri = inputUri,
            onClick = {
                pickIn.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
        )

        Spacer(Modifier.weight(1f))

        val busy = isGenerating || isStylizing
        val ready = referenceUri != null && inputUri != null

        Button(
            onClick = onGenerate,
            enabled = ready && !busy,
            colors = ButtonDefaults.buttonColors(
                containerColor = PhotoColors.PureWhite,
                contentColor = PhotoColors.RunwayBlack,
                disabledContainerColor = PhotoColors.DarkSurface,
                disabledContentColor = PhotoColors.MidSlate,
            ),
            shape = RoundedCornerShape(50),
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 18.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = if (isGenerating) "Generating…" else "Generate recipe",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            )
        }
        Spacer(Modifier.height(12.dp))
        // 보조 CTA: Gemini Nano Banana 로 stylized 이미지를 reference 로 사용.
        Button(
            onClick = onStylizeAndGenerate,
            enabled = ready && !busy,
            colors = ButtonDefaults.buttonColors(
                containerColor = PhotoColors.DarkSurface,
                contentColor = PhotoColors.PureWhite,
                disabledContainerColor = PhotoColors.DeepBlack,
                disabledContentColor = PhotoColors.MidSlate,
            ),
            shape = RoundedCornerShape(50),
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 18.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = when {
                    isStylizing -> "Stylizing with Gemini…"
                    else -> "Generate stylized image and recipe"
                },
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            )
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun PickerCard(label: String, hint: String, uri: Uri?, onClick: () -> Unit) {
    val borderColor = if (uri != null) PhotoColors.PureWhite.copy(alpha = 0.4f) else PhotoColors.BorderDark
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(2.4f)
            .clip(RoundedCornerShape(20.dp))
            .background(PhotoColors.DarkSurface)
            .border(1.dp, borderColor, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
    ) {
        Box(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp))) {
            if (uri != null) {
                AsyncImage(
                    model = uri,
                    contentDescription = label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                // Selected check chip
                Box(
                    modifier = Modifier
                        .padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .align(Alignment.TopEnd),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = PhotoColors.PureWhite,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = label,
                            color = PhotoColors.PureWhite,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.8.sp,
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.Start,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AddPhotoAlternate,
                        contentDescription = null,
                        tint = PhotoColors.MutedGray,
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = label,
                        color = PhotoColors.CoolSilver,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.8.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = hint,
                        color = PhotoColors.MidSlate,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Start,
                    )
                }
            }
        }
    }
}
