package com.krdonon.ratio

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RatioApp()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RatioApp() {
    val context = LocalContext.current
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var editedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selectedRatio by remember { mutableStateOf<AspectRatioOption?>(null) }
    var selectedFormat by remember { mutableStateOf(ImageFormat.JPG) }
    var selectedBackgroundColor by remember { mutableStateOf(BackgroundColor.WHITE) }
    var quality by remember { mutableStateOf(100) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var currentRotation by remember { mutableStateOf(0f) }

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

    // 권한 요청
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(context, "저장 권한이 필요합니다", Toast.LENGTH_SHORT).show()
        }
    }

    // 이미지 선택
    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedImageUri = it
            try {
                // EXIF 오리엔테이션을 고려한 이미지 로드
                originalBitmap = loadBitmapWithOrientation(context, it)
                editedBitmap = null
                selectedRatio = null
                currentRotation = 0f // 회전 초기화
            } catch (e: Exception) {
                Toast.makeText(context, "이미지를 불러올 수 없습니다: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 90도 회전 함수
    fun rotateImage() {
        originalBitmap?.let { bitmap ->
            currentRotation = (currentRotation + 90f) % 360f
            val matrix = Matrix().apply {
                postRotate(90f)
            }
            originalBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

            // 비율이 선택된 경우 다시 적용
            if (selectedRatio != null) {
                editedBitmap = resizeToAspectRatio(
                    originalBitmap!!,
                    selectedRatio!!.ratio,
                    selectedBackgroundColor.colorValue
                )
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
            // 이미지 선택 버튼
            Button(
                onClick = { imagePickerLauncher.launch("image/*") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("이미지 선택")
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 원본 또는 편집된 이미지 표시
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

            // 이미지가 로드되었을 때만 편집 옵션 표시
            if (originalBitmap != null) {
                // 90도 회전 버튼
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { rotateImage() },
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
                        Text("(${currentRotation.toInt()}°)", color = MaterialTheme.colorScheme.secondary)
                    }
                }

                // 비율 선택
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
                            onClick = {
                                selectedRatio = ratio
                                editedBitmap = resizeToAspectRatio(
                                    originalBitmap!!,
                                    ratio.ratio,
                                    selectedBackgroundColor.colorValue
                                )
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 포맷 및 품질 설정
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            "저장 설정",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        // 배경색 선택
                        Text("배경색", style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            BackgroundColor.entries.forEach { bgColor ->
                                FilterChip(
                                    selected = selectedBackgroundColor == bgColor,
                                    onClick = {
                                        selectedBackgroundColor = bgColor
                                        // 이미 비율이 선택된 경우 다시 적용
                                        if (selectedRatio != null && originalBitmap != null) {
                                            editedBitmap = resizeToAspectRatio(
                                                originalBitmap!!,
                                                selectedRatio!!.ratio,
                                                bgColor.colorValue
                                            )
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

                        // 포맷 선택
                        Text("포맷", style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            FilterChip(
                                selected = selectedFormat == ImageFormat.JPG,
                                onClick = { selectedFormat = ImageFormat.JPG },
                                label = { Text("JPG") },
                                leadingIcon = if (selectedFormat == ImageFormat.JPG) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else null
                            )
                            FilterChip(
                                selected = selectedFormat == ImageFormat.PNG,
                                onClick = { selectedFormat = ImageFormat.PNG },
                                label = { Text("PNG") },
                                leadingIcon = if (selectedFormat == ImageFormat.PNG) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else null
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // 품질 설정
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("품질: ${quality}%")
                            Button(
                                onClick = { showQualityDialog = true },
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

                // 저장 버튼
                if (editedBitmap != null) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = {
                            saveImage(
                                context = context,
                                bitmap = editedBitmap!!,
                                originalUri = selectedImageUri,
                                format = selectedFormat,
                                quality = quality,
                                onSuccess = {
                                    Toast.makeText(context, "이미지가 저장되었습니다", Toast.LENGTH_SHORT).show()
                                },
                                onError = {
                                    Toast.makeText(context, "저장 실패: $it", Toast.LENGTH_SHORT).show()
                                }
                            )
                        },
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

    // 품질 설정 다이얼로그
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
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSecondaryContainer
        ),
        modifier = Modifier.height(48.dp)
    ) {
        Text(ratio.name, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
fun QualityDialog(
    currentQuality: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var quality by remember { mutableStateOf(currentQuality) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("품질 설정") },
        text = {
            Column {
                Text("품질: ${quality}%")
                Spacer(modifier = Modifier.height(16.dp))
                Slider(
                    value = quality.toFloat(),
                    onValueChange = { quality = it.toInt() },
                    valueRange = 1f..100f,
                    steps = 98
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(quality) }) {
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

// EXIF 오리엔테이션을 고려한 이미지 로드 함수
fun loadBitmapWithOrientation(context: android.content.Context, uri: Uri): Bitmap {
    // 먼저 이미지를 디코드
    val inputStream = context.contentResolver.openInputStream(uri)
    val bitmap = BitmapFactory.decodeStream(inputStream)
    inputStream?.close()

    // EXIF 정보에서 회전 각도 가져오기
    val rotation = getOrientationFromExif(context, uri)

    // 회전이 필요한 경우 회전된 비트맵 반환
    return if (rotation != 0f) {
        val matrix = Matrix().apply {
            postRotate(rotation)
        }
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    } else {
        bitmap
    }
}

// EXIF 오리엔테이션 정보 가져오기
fun getOrientationFromExif(context: android.content.Context, uri: Uri): Float {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri)
        inputStream?.use { stream ->
            val exif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ExifInterface(stream)
            } else {
                // API 24 미만의 경우 파일 경로로 처리
                val tempFile = File.createTempFile("temp", "jpg", context.cacheDir)
                tempFile.outputStream().use { output ->
                    stream.copyTo(output)
                }
                val exifInterface = ExifInterface(tempFile.absolutePath)
                tempFile.delete()
                exifInterface
            }

            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
    } catch (e: Exception) {
        // EXIF 정보를 읽을 수 없는 경우 회전하지 않음
        0f
    }
}

// 수정된 resizeToAspectRatio 함수
fun resizeToAspectRatio(bitmap: Bitmap, targetRatio: Float, backgroundColor: Int = android.graphics.Color.WHITE): Bitmap {
    val originalWidth = bitmap.width
    val originalHeight = bitmap.height
    val originalRatio = originalWidth.toFloat() / originalHeight.toFloat()

    // 캔버스 크기 계산
    val (canvasWidth, canvasHeight) = when {
        targetRatio > 1 -> {
            // 목표 비율이 가로형 (예: 16:9, 4:3)
            if (originalRatio > targetRatio) {
                // 원본이 목표보다 더 넓음 -> 원본 너비 유지, 높이 증가
                val height = (originalWidth / targetRatio).toInt()
                originalWidth to height
            } else {
                // 원본이 목표보다 더 좁음 -> 원본 높이 유지, 너비 증가
                val width = (originalHeight * targetRatio).toInt()
                width to originalHeight
            }
        }
        targetRatio < 1 -> {
            // 목표 비율이 세로형 (예: 9:16, 3:4)
            if (originalRatio < targetRatio) {
                // 원본이 목표보다 더 좁음 (더 세로형) -> 원본 높이 유지, 너비 증가
                val width = (originalHeight * targetRatio).toInt()
                width to originalHeight
            } else {
                // 원본이 목표보다 더 넓음 -> 원본 너비 유지, 높이 증가
                val height = (originalWidth / targetRatio).toInt()
                originalWidth to height
            }
        }
        else -> {
            // 목표 비율이 1:1 (정사각형)
            val maxDimension = maxOf(originalWidth, originalHeight)
            maxDimension to maxDimension
        }
    }

    // 새 캔버스 생성
    val newBitmap = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(newBitmap)

    // 배경을 선택한 색으로 채우기
    canvas.drawColor(backgroundColor)

    // 원본 이미지를 중앙에 배치
    val left = (canvasWidth - originalWidth) / 2f
    val top = (canvasHeight - originalHeight) / 2f
    canvas.drawBitmap(bitmap, left, top, null)

    return newBitmap
}

fun saveImage(
    context: android.content.Context,
    bitmap: Bitmap,
    originalUri: Uri?,
    format: ImageFormat,
    quality: Int,
    onSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    try {
        // 원본 파일명 가져오기 (더 안정적인 방법)
        val originalFileNameWithoutExt = getImprovedFileNameFromUri(context, originalUri)

        // 현재 날짜 시간 포맷 (yyMMddHHmmss)
        val dateFormat = SimpleDateFormat("yyMMddHHmmss", Locale.getDefault())
        val timestamp = dateFormat.format(Date())

        // 확장자 결정
        val extension = if (format == ImageFormat.JPG) "jpg" else "png"

        // 새 파일명 생성: 원본이름_타임스탬프.확장자
        var baseFileName = "${originalFileNameWithoutExt}_${timestamp}"

        // 파일명 길이 체크 (Android 파일 시스템 제한 고려, 최대 255자)
        val maxLength = 250 - extension.length - 1  // 점(.)과 확장자 길이 고려
        if (baseFileName.length > maxLength) {
            // 타임스탬프는 유지하고 원본 파일명 부분을 자름
            val timestampWithUnderscore = "_${timestamp}"
            val maxOriginalLength = maxLength - timestampWithUnderscore.length

            if (maxOriginalLength > 0) {
                val truncatedOriginal = if (originalFileNameWithoutExt.length > maxOriginalLength) {
                    // 원본 파일명을 축약
                    if (maxOriginalLength > 10) {
                        originalFileNameWithoutExt.take(maxOriginalLength - 3) + "~"
                    } else {
                        originalFileNameWithoutExt.take(maxOriginalLength)
                    }
                } else {
                    originalFileNameWithoutExt
                }
                baseFileName = "${truncatedOriginal}_${timestamp}"
            } else {
                // 극단적인 경우: IMG_ 접두사와 타임스탬프 사용
                baseFileName = "IMG_${timestamp}"
            }
        }

        val finalFileName = "${baseFileName}.${extension}"

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, finalFileName)
            put(MediaStore.Images.Media.MIME_TYPE, if (format == ImageFormat.JPG) "image/jpeg" else "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
        }

        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        )

        uri?.let {
            context.contentResolver.openOutputStream(it)?.use { outputStream ->
                val compressFormat = if (format == ImageFormat.JPG)
                    Bitmap.CompressFormat.JPEG
                else
                    Bitmap.CompressFormat.PNG
                bitmap.compress(compressFormat, quality, outputStream)
                onSuccess()
            } ?: onError("파일 스트림을 열 수 없습니다")
        } ?: onError("미디어 저장소에 접근할 수 없습니다")

    } catch (e: Exception) {
        onError(e.message ?: "알 수 없는 오류")
    }
}

// 개선된 파일명 가져오기 함수
fun getImprovedFileNameFromUri(context: android.content.Context, uri: Uri?): String {
    uri ?: return "IMG"

    var fileName: String? = null

    // 방법 1: OpenableColumns 사용 (가장 신뢰할 수 있는 방법)
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                fileName = cursor.getString(nameIndex)
            }
        }
    } catch (e: Exception) {
        // 쿼리 실패 시 다음 방법으로
    }

    // 방법 2: MediaStore의 DISPLAY_NAME 사용
    if (fileName == null && uri.scheme == "content") {
        try {
            val projection = arrayOf(MediaStore.Images.Media.DISPLAY_NAME)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    fileName = cursor.getString(0)
                }
            }
        } catch (e: Exception) {
            // 쿼리 실패 시 다음 방법으로
        }
    }

    // 방법 3: URI path에서 파일명 추출
    if (fileName == null) {
        fileName = uri.lastPathSegment

        // 숫자만 있는 경우 (MediaStore ID인 경우) 기본값 사용
        if (fileName?.all { it.isDigit() } == true) {
            fileName = null
        }
    }

    // 파일명이 여전히 null이거나 비정상적인 경우
    if (fileName == null || fileName.length < 3 || fileName.all { it.isDigit() }) {
        // 현재 날짜로 기본 파일명 생성
        val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        return "IMG_${dateFormat.format(Date())}"
    }

    // 확장자 제거
    val lastDotIndex = fileName.lastIndexOf('.')
    return if (lastDotIndex > 0) {
        fileName.substring(0, lastDotIndex)
    } else {
        fileName
    }
}

// InputStream 복사 확장 함수
fun InputStream.copyTo(out: OutputStream, bufferSize: Int = DEFAULT_BUFFER_SIZE): Long {
    var bytesCopied: Long = 0
    val buffer = ByteArray(bufferSize)
    var bytes = read(buffer)
    while (bytes >= 0) {
        out.write(buffer, 0, bytes)
        bytesCopied += bytes
        bytes = read(buffer)
    }
    return bytesCopied
}