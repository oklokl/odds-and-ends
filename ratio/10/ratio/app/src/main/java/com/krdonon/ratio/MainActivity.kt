package com.krdonon.ratio

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

private const val PREVIEW_MAX_DIMENSION = 2048
private const val BYTES_PER_PIXEL = 4L
private const val MIN_EXPORT_MEMORY_BUDGET = 48L * 1024L * 1024L
private const val MAX_EXPORT_MEMORY_BUDGET = 160L * 1024L * 1024L
private const val EXPORT_MEMORY_FRACTION = 0.30

class MainActivity : ComponentActivity() {
    private var isAppVisible by mutableStateOf(true)
    private var memoryTrimSignal by mutableIntStateOf(0)

    // Runtime-only value. Age Signals data is intentionally not persisted.
    private var userAgeCategory: UserAgeCategory = UserAgeCategory.UNKNOWN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RatioApp(
                isAppVisible = isAppVisible,
                memoryTrimSignal = memoryTrimSignal
            )
        }

        AgeSignalsCompliance.checkUserAge(this) { category ->
            userAgeCategory = category
            // This app currently has no ads, Billing, or age-gated feature to configure.
            // If one is added later, use this runtime category only for age-appropriate
            // experiences and legal compliance, never for ads, marketing, profiling, or analytics.
        }
    }

    override fun onStart() {
        super.onStart()
        isAppVisible = true
    }

    override fun onStop() {
        // 화면에 보이지 않는 동안에는 큰 편집 Bitmap을 유지하지 않도록 합니다.
        isAppVisible = false
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        // Google Play bitmap-memory 지침에 맞춰 UI가 숨겨지거나 메모리 압박이 오면
        // Composable 쪽에 transient bitmap 해제 신호를 전달합니다.
        @Suppress("DEPRECATION")
        val shouldTrim = level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL

        if (shouldTrim) {
            memoryTrimSignal++
        }
    }
}

data class AspectRatioOption(
    val name: String,
    val ratio: Float
)

enum class ImageFormat {
    JPG, PNG
}

enum class BackgroundColor(val colorValue: Int, val displayName: String) {
    WHITE(android.graphics.Color.WHITE, "흰색"),
    BLACK(android.graphics.Color.BLACK, "검은색"),
    TRANSPARENT(android.graphics.Color.TRANSPARENT, "투명")
}

private data class PreparedImage(
    val sourceFile: File,
    val previewBitmap: Bitmap
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RatioApp(
    isAppVisible: Boolean,
    memoryTrimSignal: Int
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var selectedImageFile by remember { mutableStateOf<File?>(null) }
    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var editedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selectedRatio by remember { mutableStateOf<AspectRatioOption?>(null) }
    var selectedFormat by remember { mutableStateOf(ImageFormat.JPG) }
    var selectedBackgroundColor by remember { mutableStateOf(BackgroundColor.WHITE) }
    var quality by remember { mutableIntStateOf(100) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var currentRotation by remember { mutableFloatStateOf(0f) }
    var isProcessing by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var operationId by remember { mutableIntStateOf(0) }

    val aspectRatios = remember {
        listOf(
            AspectRatioOption("1:1", 1f),
            AspectRatioOption("4:3", 4f / 3f),
            AspectRatioOption("3:4", 3f / 4f),
            AspectRatioOption("16:9", 16f / 9f),
            AspectRatioOption("9:16", 9f / 16f),
            AspectRatioOption("3:2", 3f / 2f),
            AspectRatioOption("2:3", 2f / 3f),
        )
    }

    fun rebuildEditedBitmap(
        ratio: AspectRatioOption,
        background: BackgroundColor
    ) {
        val source = originalBitmap ?: return
        val requestId = ++operationId

        scope.launch {
            isProcessing = true
            try {
                val result = withContext(Dispatchers.Default) {
                    resizeToAspectRatio(source, ratio.ratio, background.colorValue)
                }

                if (
                    requestId == operationId &&
                    isAppVisible &&
                    originalBitmap === source &&
                    selectedRatio == ratio &&
                    selectedBackgroundColor == background
                ) {
                    editedBitmap = result
                } else if (!result.isRecycled) {
                    // 화면에 한 번도 표시되지 않은 stale 결과이므로 즉시 반환합니다.
                    result.recycle()
                }
            } catch (e: Exception) {
                if (requestId == operationId && isAppVisible) {
                    Toast.makeText(
                        context,
                        "이미지 편집 중 오류가 발생했습니다: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } finally {
                if (requestId == operationId) {
                    isProcessing = false
                }
            }
        }
    }

    // 앱이 보이지 않으면 큰 편집 결과를 버리고, 다시 보일 때 작은 preview 원본으로 재구성합니다.
    LaunchedEffect(isAppVisible) {
        if (!isAppVisible) {
            operationId++
            editedBitmap = null
            isProcessing = false
        } else if (
            editedBitmap == null &&
            originalBitmap != null &&
            selectedRatio != null
        ) {
            rebuildEditedBitmap(selectedRatio!!, selectedBackgroundColor)
        }
    }

    // 시스템 메모리 정리 신호가 오면 transient bitmap을 즉시 해제합니다.
    LaunchedEffect(memoryTrimSignal) {
        if (memoryTrimSignal > 0) {
            operationId++
            editedBitmap = null
            isProcessing = false
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult

        selectedImageUri = uri
        selectedRatio = null
        originalBitmap = null
        editedBitmap = null
        currentRotation = 0f
        selectedImageFile?.delete()
        selectedImageFile = null
        val requestId = ++operationId

        scope.launch {
            isProcessing = true
            try {
                val prepared = withContext(Dispatchers.IO) {
                    val cachedFile = copySelectedImageToCache(context, uri)
                    try {
                        val preview = loadBitmapWithOrientation(
                            sourceFile = cachedFile,
                            maxDimension = PREVIEW_MAX_DIMENSION
                        )
                        PreparedImage(cachedFile, preview)
                    } catch (e: Exception) {
                        cachedFile.delete()
                        throw e
                    }
                }

                if (requestId == operationId && selectedImageUri == uri) {
                    selectedImageFile = prepared.sourceFile
                    originalBitmap = prepared.previewBitmap
                } else {
                    if (!prepared.previewBitmap.isRecycled) {
                        prepared.previewBitmap.recycle()
                    }
                    prepared.sourceFile.delete()
                }
            } catch (e: Exception) {
                if (requestId == operationId) {
                    Toast.makeText(
                        context,
                        "이미지를 불러올 수 없습니다: ${e.message ?: "알 수 없는 오류"}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } finally {
                if (requestId == operationId) {
                    isProcessing = false
                }
            }
        }
    }

    val latestSelectedImageFile by rememberUpdatedState(selectedImageFile)
    DisposableEffect(Unit) {
        onDispose {
            latestSelectedImageFile?.delete()
        }
    }

    fun rotateImage() {
        val source = originalBitmap ?: return
        val ratio = selectedRatio
        val background = selectedBackgroundColor
        val requestId = ++operationId

        scope.launch {
            isProcessing = true
            try {
                val (rotated, ratioResult) = withContext(Dispatchers.Default) {
                    val newOriginal = rotateBitmap(source, 90f)
                    val newEdited = ratio?.let {
                        resizeToAspectRatio(newOriginal, it.ratio, background.colorValue)
                    }
                    newOriginal to newEdited
                }

                if (requestId == operationId && originalBitmap === source && isAppVisible) {
                    originalBitmap = rotated
                    editedBitmap = ratioResult
                    currentRotation = (currentRotation + 90f) % 360f
                } else {
                    if (!rotated.isRecycled) rotated.recycle()
                    if (ratioResult != null && !ratioResult.isRecycled) ratioResult.recycle()
                }
            } catch (e: Exception) {
                if (requestId == operationId) {
                    Toast.makeText(
                        context,
                        "이미지 회전 중 오류가 발생했습니다: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } finally {
                if (requestId == operationId) {
                    isProcessing = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("비율 편집기") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = {
                    imagePickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                enabled = !isProcessing && !isSaving,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("이미지 선택")
            }

            if (isProcessing || isSaving) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    if (isSaving) "고화질 이미지 저장 중…" else "이미지 처리 중…",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (editedBitmap != null) {
                Text(
                    "편집된 이미지",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Image(
                    bitmap = editedBitmap!!.asImageBitmap(),
                    contentDescription = "편집된 이미지",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Fit
                )
            } else if (originalBitmap != null) {
                Text(
                    "원본 이미지",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Image(
                    bitmap = originalBitmap!!.asImageBitmap(),
                    contentDescription = "원본 이미지",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(2.dp, Color.Gray, RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Fit
                )
            }

            if (originalBitmap != null) {
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { rotateImage() },
                    enabled = !isProcessing && !isSaving,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "90도 회전",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("90도 회전")
                    if (currentRotation > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "(${currentRotation.toInt()}°)",
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    "비율 선택",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(aspectRatios) { ratio ->
                        RatioButton(
                            ratio = ratio,
                            isSelected = selectedRatio == ratio,
                            enabled = !isProcessing && !isSaving,
                            onClick = {
                                selectedRatio = ratio
                                rebuildEditedBitmap(ratio, selectedBackgroundColor)
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "저장 설정",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Text("배경색", style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            BackgroundColor.entries.forEach { bgColor ->
                                FilterChip(
                                    selected = selectedBackgroundColor == bgColor,
                                    enabled = !isProcessing && !isSaving,
                                    onClick = {
                                        selectedBackgroundColor = bgColor
                                        selectedRatio?.let { ratio ->
                                            rebuildEditedBitmap(ratio, bgColor)
                                        }
                                    },
                                    label = { Text(bgColor.displayName) },
                                    leadingIcon = if (selectedBackgroundColor == bgColor) {
                                        { Icon(Icons.Default.Check, contentDescription = null) }
                                    } else null
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text("포맷", style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            FilterChip(
                                selected = selectedFormat == ImageFormat.JPG,
                                enabled = !isSaving,
                                onClick = { selectedFormat = ImageFormat.JPG },
                                label = { Text("JPG") },
                                leadingIcon = if (selectedFormat == ImageFormat.JPG) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else null
                            )
                            FilterChip(
                                selected = selectedFormat == ImageFormat.PNG,
                                enabled = !isSaving,
                                onClick = { selectedFormat = ImageFormat.PNG },
                                label = { Text("PNG") },
                                leadingIcon = if (selectedFormat == ImageFormat.PNG) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else null
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("품질: ${quality}%")
                            Button(
                                onClick = { showQualityDialog = true },
                                enabled = !isSaving,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            ) {
                                Text("변경")
                            }
                        }
                    }
                }

                if (editedBitmap != null && selectedRatio != null && selectedImageUri != null && selectedImageFile != null) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = {
                            val uri = selectedImageUri ?: return@Button
                            val sourceFile = selectedImageFile ?: return@Button
                            val ratio = selectedRatio ?: return@Button
                            val background = selectedBackgroundColor
                            val rotation = currentRotation
                            val format = selectedFormat
                            val saveQuality = quality

                            scope.launch {
                                isSaving = true
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        exportEditedImage(
                                            context = context,
                                            sourceFile = sourceFile,
                                            originalUri = uri,
                                            targetRatio = ratio.ratio,
                                            backgroundColor = background.colorValue,
                                            additionalRotation = rotation,
                                            format = format,
                                            quality = saveQuality
                                        )
                                    }
                                }

                                result.fold(
                                    onSuccess = {
                                        Toast.makeText(
                                            context,
                                            "이미지가 저장되었습니다",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    onFailure = {
                                        Toast.makeText(
                                            context,
                                            "저장 실패: ${it.message ?: "알 수 없는 오류"}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                )
                                isSaving = false
                            }
                        },
                        enabled = !isProcessing && !isSaving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("저장", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }

    if (showQualityDialog) {
        QualityDialog(
            currentQuality = quality,
            onDismiss = { showQualityDialog = false },
            onConfirm = { newQuality ->
                quality = newQuality
                showQualityDialog = false
            }
        )
    }
}

@Composable
fun RatioButton(
    ratio: AspectRatioOption,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSecondaryContainer
        ),
        modifier = Modifier.height(48.dp)
    ) {
        Text(
            ratio.name,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
fun QualityDialog(
    currentQuality: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var dialogQuality by remember { mutableIntStateOf(currentQuality) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("품질 설정") },
        text = {
            Column {
                Text("품질: ${dialogQuality}%")
                Spacer(modifier = Modifier.height(16.dp))
                Slider(
                    value = dialogQuality.toFloat(),
                    onValueChange = { dialogQuality = it.toInt() },
                    valueRange = 1f..100f,
                    steps = 98
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
        }
    )
}

/**
 * Photo Picker/Document Provider가 돌려준 URI는 공급자에 따라 재오픈 동작이 다를 수 있습니다.
 * 선택 직후 앱 전용 cache 파일로 한 번만 복사한 뒤 모든 디코딩/EXIF 처리는 로컬 파일에서 수행합니다.
 * 이렇게 하면 cloud media provider, 에뮬레이터 MediaProvider, 일회성 URI grant에서도 안정적입니다.
 */
private fun copySelectedImageToCache(context: Context, uri: Uri): File {
    val cacheFile = File.createTempFile("ratio_selected_", ".img", context.cacheDir)

    try {
        openImageInputStream(context, uri).use { input ->
            cacheFile.outputStream().buffered().use { output ->
                input.copyTo(output)
            }
        }

        if (cacheFile.length() <= 0L) {
            throw IOException("선택한 이미지 데이터가 비어 있습니다")
        }

        return cacheFile
    } catch (e: Exception) {
        cacheFile.delete()
        if (e is IOException) throw e
        throw IOException(e.message ?: "선택한 이미지 데이터를 읽을 수 없습니다", e)
    }
}

/**
 * URI 공급자 호환성을 높이기 위해 일반 InputStream -> FileDescriptor -> typed asset 순서로 시도합니다.
 */
private fun openImageInputStream(context: Context, uri: Uri): InputStream {
    if (uri.scheme == ContentResolver.SCHEME_FILE) {
        val path = uri.path ?: throw IOException("이미지 파일 경로가 없습니다")
        return File(path).inputStream()
    }

    val resolver = context.contentResolver
    var firstError: Exception? = null

    try {
        resolver.openInputStream(uri)?.let { return it }
    } catch (e: Exception) {
        firstError = e
    }

    try {
        resolver.openFileDescriptor(uri, "r")?.let { descriptor ->
            return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        }
    } catch (e: Exception) {
        if (firstError == null) firstError = e
    }

    try {
        resolver.openTypedAssetFileDescriptor(uri, "image/*", null)?.let { descriptor ->
            return descriptor.createInputStream()
        }
    } catch (e: Exception) {
        if (firstError == null) firstError = e
    }

    val reason = firstError?.message?.takeIf { it.isNotBlank() }
    throw IOException(
        if (reason != null) "이미지 스트림을 열 수 없습니다 ($reason)"
        else "이미지 스트림을 열 수 없습니다",
        firstError
    )
}

/**
 * 화면 표시용 Bitmap은 장시간 메모리에 남기 때문에 최대 크기를 제한해서 디코딩합니다.
 * 원본 파일 자체는 수정하지 않으며, 저장 시에는 별도의 memory-safe export 경로를 사용합니다.
 */
fun loadBitmapWithOrientation(
    sourceFile: File,
    maxDimension: Int
): Bitmap {
    val bounds = readBitmapBounds(sourceFile)
    val inSampleSize = calculateInSampleSizeForMaxDimension(
        width = bounds.first,
        height = bounds.second,
        maxDimension = maxDimension
    )

    val bitmap = decodeBitmap(sourceFile, inSampleSize)
    val exifRotation = getOrientationFromExif(sourceFile)
    return rotateBitmapAndRecycleSource(bitmap, exifRotation)
}

/**
 * 저장용 원본은 기기의 heap 크기와 목표 비율을 고려해 가능한 높은 해상도로 디코딩합니다.
 * 예상 작업 메모리가 안전 예산을 넘을 때에만 2배 단위로 다운샘플링합니다.
 */
fun loadBitmapForExport(
    context: Context,
    sourceFile: File,
    targetRatio: Float,
    additionalRotation: Float
): Bitmap {
    val (rawWidth, rawHeight) = readBitmapBounds(sourceFile)
    val exifRotation = getOrientationFromExif(sourceFile)
    val totalRotation = normalizeRotation(exifRotation + additionalRotation)
    val rotationSwapsDimensions = totalRotation == 90f || totalRotation == 270f
    val orientedWidth = if (rotationSwapsDimensions) rawHeight else rawWidth
    val orientedHeight = if (rotationSwapsDimensions) rawWidth else rawHeight

    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val heapBytes = activityManager.memoryClass.toLong() * 1024L * 1024L
    val memoryBudget = (heapBytes * EXPORT_MEMORY_FRACTION).toLong()
        .coerceIn(MIN_EXPORT_MEMORY_BUDGET, MAX_EXPORT_MEMORY_BUDGET)

    var inSampleSize = 1
    while (
        estimateWorkingSetBytes(
            width = max(1, orientedWidth / inSampleSize),
            height = max(1, orientedHeight / inSampleSize),
            targetRatio = targetRatio,
            rotationNeeded = totalRotation != 0f
        ) > memoryBudget &&
        max(orientedWidth, orientedHeight) / inSampleSize > 1
    ) {
        inSampleSize *= 2
    }

    val bitmap = decodeBitmap(sourceFile, inSampleSize)
    return rotateBitmapAndRecycleSource(bitmap, totalRotation)
}

fun getOrientationFromExif(sourceFile: File): Float {
    return try {
        val exif = ExifInterface(sourceFile.absolutePath)
        when (
            exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } catch (_: Exception) {
        0f
    }
}

fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
    val normalized = normalizeRotation(degrees)
    if (normalized == 0f) return bitmap

    val matrix = Matrix().apply { postRotate(normalized) }
    return Bitmap.createBitmap(
        bitmap,
        0,
        0,
        bitmap.width,
        bitmap.height,
        matrix,
        true
    )
}

private fun rotateBitmapAndRecycleSource(bitmap: Bitmap, degrees: Float): Bitmap {
    val rotated = rotateBitmap(bitmap, degrees)
    if (rotated !== bitmap && !bitmap.isRecycled) {
        bitmap.recycle()
    }
    return rotated
}

fun resizeToAspectRatio(
    bitmap: Bitmap,
    targetRatio: Float,
    backgroundColor: Int = android.graphics.Color.WHITE
): Bitmap {
    val (canvasWidth, canvasHeight) = calculateCanvasSize(
        bitmap.width,
        bitmap.height,
        targetRatio
    )

    val newBitmap = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(newBitmap)
    canvas.drawColor(backgroundColor)

    val left = (canvasWidth - bitmap.width) / 2f
    val top = (canvasHeight - bitmap.height) / 2f
    canvas.drawBitmap(bitmap, left, top, null)

    return newBitmap
}

private fun calculateCanvasSize(
    originalWidth: Int,
    originalHeight: Int,
    targetRatio: Float
): Pair<Int, Int> {
    require(originalWidth > 0 && originalHeight > 0) { "잘못된 이미지 크기입니다." }
    require(targetRatio > 0f) { "잘못된 목표 비율입니다." }

    val originalRatio = originalWidth.toFloat() / originalHeight.toFloat()

    return when {
        originalRatio > targetRatio -> {
            originalWidth to max(1, (originalWidth / targetRatio).toInt())
        }
        originalRatio < targetRatio -> {
            max(1, (originalHeight * targetRatio).toInt()) to originalHeight
        }
        else -> originalWidth to originalHeight
    }
}

private fun readBitmapBounds(sourceFile: File): Pair<Int, Int> {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(sourceFile.absolutePath, options)

    if (options.outWidth <= 0 || options.outHeight <= 0) {
        throw IOException("이미지 크기를 확인할 수 없습니다")
    }

    return options.outWidth to options.outHeight
}

private fun decodeBitmap(sourceFile: File, inSampleSize: Int): Bitmap {
    val options = BitmapFactory.Options().apply {
        this.inSampleSize = max(1, inSampleSize)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    return BitmapFactory.decodeFile(sourceFile.absolutePath, options)
        ?: throw IOException("이미지를 디코딩할 수 없습니다")
}

private fun calculateInSampleSizeForMaxDimension(
    width: Int,
    height: Int,
    maxDimension: Int
): Int {
    var sample = 1
    val longest = max(width, height)
    while (longest / sample > maxDimension && longest / sample > 1) {
        sample *= 2
    }
    return sample
}

private fun estimateWorkingSetBytes(
    width: Int,
    height: Int,
    targetRatio: Float,
    rotationNeeded: Boolean
): Long {
    val (canvasWidth, canvasHeight) = calculateCanvasSize(width, height, targetRatio)
    val sourceBytes = width.toLong() * height.toLong() * BYTES_PER_PIXEL
    val outputBytes = canvasWidth.toLong() * canvasHeight.toLong() * BYTES_PER_PIXEL
    val rotationPeak = if (rotationNeeded) sourceBytes else 0L
    val safetyMargin = 8L * 1024L * 1024L

    return sourceBytes + rotationPeak + outputBytes + safetyMargin
}

private fun normalizeRotation(degrees: Float): Float {
    val normalized = ((degrees % 360f) + 360f) % 360f
    return when {
        normalized < 45f || normalized >= 315f -> 0f
        normalized < 135f -> 90f
        normalized < 225f -> 180f
        else -> 270f
    }
}

fun exportEditedImage(
    context: Context,
    sourceFile: File,
    originalUri: Uri,
    targetRatio: Float,
    backgroundColor: Int,
    additionalRotation: Float,
    format: ImageFormat,
    quality: Int
) {
    val source = loadBitmapForExport(
        context = context,
        sourceFile = sourceFile,
        targetRatio = targetRatio,
        additionalRotation = additionalRotation
    )

    var output: Bitmap? = null
    try {
        output = resizeToAspectRatio(source, targetRatio, backgroundColor)
        saveImage(
            context = context,
            bitmap = output,
            originalUri = originalUri,
            format = format,
            quality = quality
        )
    } finally {
        if (output != null && output !== source && !output.isRecycled) {
            output.recycle()
        }
        if (!source.isRecycled) {
            source.recycle()
        }
    }
}

fun saveImage(
    context: Context,
    bitmap: Bitmap,
    originalUri: Uri?,
    format: ImageFormat,
    quality: Int
) {
    val originalFileNameWithoutExt = getImprovedFileNameFromUri(context, originalUri)
    val dateFormat = SimpleDateFormat("yyMMddHHmmss", Locale.getDefault())
    val timestamp = dateFormat.format(Date())
    val extension = if (format == ImageFormat.JPG) "jpg" else "png"

    var baseFileName = "${originalFileNameWithoutExt}_${timestamp}"
    val maxLength = 250 - extension.length - 1
    if (baseFileName.length > maxLength) {
        val timestampWithUnderscore = "_${timestamp}"
        val maxOriginalLength = maxLength - timestampWithUnderscore.length

        baseFileName = if (maxOriginalLength > 0) {
            val truncatedOriginal = if (originalFileNameWithoutExt.length > maxOriginalLength) {
                if (maxOriginalLength > 10) {
                    originalFileNameWithoutExt.take(maxOriginalLength - 3) + "~"
                } else {
                    originalFileNameWithoutExt.take(maxOriginalLength)
                }
            } else {
                originalFileNameWithoutExt
            }
            "${truncatedOriginal}_${timestamp}"
        } else {
            "IMG_${timestamp}"
        }
    }

    val finalFileName = "${baseFileName}.${extension}"
    val mimeType = if (format == ImageFormat.JPG) "image/jpeg" else "image/png"

    val contentValues = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, finalFileName)
        put(MediaStore.Images.Media.MIME_TYPE, mimeType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }

    val uri = context.contentResolver.insert(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        contentValues
    ) ?: throw IOException("미디어 저장소에 접근할 수 없습니다")

    try {
        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            val compressFormat = if (format == ImageFormat.JPG) {
                Bitmap.CompressFormat.JPEG
            } else {
                Bitmap.CompressFormat.PNG
            }

            if (!bitmap.compress(compressFormat, quality, outputStream)) {
                throw IOException("이미지 압축에 실패했습니다")
            }
        } ?: throw IOException("파일 스트림을 열 수 없습니다")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val publishValues = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            context.contentResolver.update(uri, publishValues, null, null)
        }
    } catch (e: Exception) {
        context.contentResolver.delete(uri, null, null)
        throw e
    }
}

fun getImprovedFileNameFromUri(context: Context, uri: Uri?): String {
    uri ?: return "IMG"

    var fileName: String? = null

    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                fileName = cursor.getString(nameIndex)
            }
        }
    } catch (_: Exception) {
        // 다음 방법으로 진행합니다.
    }

    if (fileName == null && uri.scheme == "content") {
        try {
            val projection = arrayOf(MediaStore.Images.Media.DISPLAY_NAME)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    fileName = cursor.getString(0)
                }
            }
        } catch (_: Exception) {
            // 다음 방법으로 진행합니다.
        }
    }

    if (fileName == null) {
        fileName = uri.lastPathSegment
        if (fileName?.all { it.isDigit() } == true) {
            fileName = null
        }
    }

    val validFileName = fileName
    if (validFileName == null || validFileName.length < 3 || validFileName.all { it.isDigit() }) {
        val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        return "IMG_${dateFormat.format(Date())}"
    }

    val lastDotIndex = validFileName.lastIndexOf('.')
    return if (lastDotIndex > 0) {
        validFileName.substring(0, lastDotIndex)
    } else {
        validFileName
    }
}
