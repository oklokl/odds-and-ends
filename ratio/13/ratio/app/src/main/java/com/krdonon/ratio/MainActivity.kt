package com.krdonon.ratio

import android.Manifest
import android.content.ComponentCallbacks2
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.krdonon.ratio.ui.theme.RatioTheme
import kotlin.math.ceil
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val viewModel: RatioViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        setContent {
            RatioTheme {
                RatioApp(viewModel = viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onAppVisibilityChanged(true)
        // This app currently has no ads, Billing, or age-gated feature to configure.
        // If one is added later, read viewModel.ageSignalsState only at runtime for
        // age-appropriate experiences and legal compliance, never for ads or analytics.
        viewModel.refreshAgeSignalsIfNeeded(this)
    }

    override fun onStop() {
        // 화면에 보이지 않는 동안에는 큰 편집 Bitmap을 유지하지 않도록 합니다.
        viewModel.onAppVisibilityChanged(false)
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        @Suppress("DEPRECATION")
        val shouldTrim = (
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
            )

        if (shouldTrim) {
            viewModel.onTrimMemory(level)
        }
    }
}

private val ASPECT_RATIOS = listOf(
    AspectRatioOption("1:1", 1f),
    AspectRatioOption("4:3", 4f / 3f),
    AspectRatioOption("3:4", 3f / 4f),
    AspectRatioOption("16:9", 16f / 9f),
    AspectRatioOption("9:16", 9f / 16f),
    AspectRatioOption("3:2", 3f / 2f),
    AspectRatioOption("2:3", 2f / 3f),
)

@Composable
fun RatioApp(viewModel: RatioViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showQualityDialog by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is RatioUiEvent.ShowMessage -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        uri?.let { viewModel.selectImage(it) }
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.saveEditedImage()
        } else {
            Toast.makeText(
                context,
                "이미지를 저장하려면 저장소 쓰기 권한이 필요합니다",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    val onSaveClick = remember(context, viewModel, storagePermissionLauncher) {
        {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                viewModel.saveEditedImage()
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("비율 편집기") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        },
        bottomBar = {
            if (uiState.hasEdits && uiState.selectedImageUri != null && uiState.selectedImageFile != null) {
                Surface(
                    tonalElevation = 3.dp,
                    shadowElevation = 8.dp,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(
                                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                                ),
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Button(
                            onClick = onSaveClick,
                            enabled = uiState.canSave,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                            ),
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("저장", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        },
    ) { paddingValues ->
        val layoutDirection = LocalLayoutDirection.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = paddingValues.calculateTopPadding(),
                    start = paddingValues.calculateStartPadding(layoutDirection),
                    end = paddingValues.calculateEndPadding(layoutDirection),
                )
                .consumeWindowInsets(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(
                    start = 16.dp,
                    top = 16.dp,
                    end = 16.dp,
                    bottom = 16.dp + paddingValues.calculateBottomPadding(),
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = {
                    imagePickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                enabled = !uiState.isProcessing && !uiState.isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("이미지 선택")
            }

            if (uiState.isProcessing || uiState.isSaving) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    if (uiState.isSaving) "고화질 이미지 저장 중…" else "이미지 처리 중…",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            val displayBitmap = uiState.editedBitmap ?: uiState.originalBitmap
            if (displayBitmap != null) {
                val isEditedPreview = uiState.editedBitmap != null || uiState.currentRotation != 0f
                val showCheckerboard =
                    uiState.editedBitmap != null &&
                        uiState.selectedBackgroundColor == BackgroundColor.TRANSPARENT

                Text(
                    if (isEditedPreview) "편집된 이미지" else "원본 이미지",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(8.dp))

                val borderColor = if (isEditedPreview) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                }
                val imageShape = RoundedCornerShape(8.dp)
                val bitmapAspect = displayBitmap.width.toFloat() / displayBitmap.height.toFloat()

                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = displayBitmap.asImageBitmap(),
                        contentDescription = if (isEditedPreview) "편집된 이미지" else "원본 이미지",
                        modifier = Modifier
                            .heightIn(max = 400.dp)
                            .aspectRatio(bitmapAspect)
                            .clip(imageShape)
                            .then(
                                if (showCheckerboard) {
                                    Modifier.checkerboardBackground()
                                } else {
                                    Modifier
                                },
                            )
                            .border(2.dp, borderColor, imageShape),
                        contentScale = ContentScale.Fit,
                    )
                }
            }

            if (uiState.hasImage) {
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { viewModel.rotate90() },
                    enabled = !uiState.isProcessing && !uiState.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.RotateRight,
                        contentDescription = "90도 회전",
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("90도 회전")
                    if (uiState.currentRotation > 0f) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "(${uiState.currentRotation.toInt()}°)",
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    "비율 선택",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    ASPECT_RATIOS.forEach { ratio ->
                        RatioButton(
                            ratio = ratio,
                            isSelected = uiState.selectedRatio == ratio,
                            enabled = !uiState.isProcessing && !uiState.isSaving,
                            onClick = { viewModel.selectRatio(ratio) },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "저장 설정",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Text("배경색", style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            BackgroundColor.entries.forEach { bgColor ->
                                FilterChip(
                                    selected = uiState.selectedBackgroundColor == bgColor,
                                    enabled = !uiState.isProcessing && !uiState.isSaving,
                                    onClick = { viewModel.selectBackgroundColor(bgColor) },
                                    label = { Text(bgColor.displayName) },
                                    leadingIcon = if (uiState.selectedBackgroundColor == bgColor) {
                                        { Icon(Icons.Default.Check, contentDescription = null) }
                                    } else {
                                        null
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text("포맷", style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ImageFormat.entries.forEach { format ->
                                val supportedForBackground =
                                    uiState.selectedBackgroundColor != BackgroundColor.TRANSPARENT ||
                                        format.supportsTransparency
                                FilterChip(
                                    selected = uiState.selectedFormat == format,
                                    enabled = !uiState.isSaving && supportedForBackground,
                                    onClick = { viewModel.selectFormat(format) },
                                    label = { Text(format.label) },
                                    leadingIcon = if (uiState.selectedFormat == format) {
                                        { Icon(Icons.Default.Check, contentDescription = null) }
                                    } else {
                                        null
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }

                        if (uiState.selectedBackgroundColor == BackgroundColor.TRANSPARENT) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "투명 배경은 PNG 또는 WebP 포맷만 지원합니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        val qualityLabel = when {
                            !uiState.selectedFormat.supportsQuality -> "품질: 무손실 (PNG)"
                            uiState.selectedFormat == ImageFormat.WEBP &&
                                uiState.quality >= 100 &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> "품질: 100% (무손실)"
                            else -> "품질: ${uiState.quality}%"
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(qualityLabel)
                            Button(
                                onClick = { showQualityDialog = true },
                                enabled = !uiState.isSaving && uiState.selectedFormat.supportsQuality,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                ),
                            ) {
                                Text("변경")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showQualityDialog) {
        QualityDialog(
            currentQuality = uiState.quality,
            onDismiss = { showQualityDialog = false },
            onConfirm = { newQuality ->
                viewModel.updateQuality(newQuality)
                showQualityDialog = false
            },
        )
    }
}

private fun Modifier.checkerboardBackground(): Modifier = this.drawWithCache {
    val tilePx = 10.dp.toPx()
    val cols = ceil(size.width / tilePx).toInt()
    val rows = ceil(size.height / tilePx).toInt()
    val darkTiles = Path().apply {
        for (row in 0 until rows) {
            val top = row * tilePx
            val bottom = minOf(top + tilePx, size.height)
            val startCol = if (row % 2 == 0) 0 else 1
            var col = startCol
            while (col < cols) {
                val left = col * tilePx
                val right = minOf(left + tilePx, size.width)
                addRect(androidx.compose.ui.geometry.Rect(left, top, right, bottom))
                col += 2
            }
        }
    }
    val lightColor = Color(0xFFEEEEEE)
    val darkColor = Color(0xFFBDBDBD)

    onDrawBehind {
        drawRect(lightColor)
        drawPath(darkTiles, darkColor)
    }
}

@Composable
fun RatioButton(
    ratio: AspectRatioOption,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
            contentColor = if (isSelected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
        ),
        modifier = Modifier.height(48.dp),
    ) {
        Text(
            ratio.name,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
fun QualityDialog(
    currentQuality: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val sliderState = rememberSliderState(
        value = currentQuality.toFloat(),
        steps = 98,
        trackRange = 1f..100f,
    )
    val dialogQuality = sliderState.value.roundToInt().coerceIn(1, 100)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("품질 설정") },
        text = {
            Column {
                Text("품질: $dialogQuality%")
                Spacer(modifier = Modifier.height(16.dp))
                Slider(
                    state = sliderState,
                    onValueChange = { sliderState.value = it },
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(dialogQuality) }) {
                Text("확인")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        },
    )
}
