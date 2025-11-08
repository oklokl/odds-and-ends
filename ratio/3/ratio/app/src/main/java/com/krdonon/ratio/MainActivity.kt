package com.krdonon.ratio

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
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
                val inputStream = context.contentResolver.openInputStream(it)
                originalBitmap = BitmapFactory.decodeStream(inputStream)
                editedBitmap = null
                selectedRatio = null
            } catch (e: Exception) {
                Toast.makeText(context, "이미지를 불러올 수 없습니다", Toast.LENGTH_SHORT).show()
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

            // 비율 선택
            if (originalBitmap != null) {
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
        // 원본 파일명 가져오기
        val originalFileName = getFileNameFromUri(context, originalUri) ?: "image"

        // 날짜 시간 포맷
        val dateFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault())
        val timestamp = dateFormat.format(Date())

        // 새 파일명 생성
        val extension = if (format == ImageFormat.JPG) "jpg" else "png"
        val newFileName = "${timestamp}${originalFileName}"

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, newFileName)
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

fun getFileNameFromUri(context: android.content.Context, uri: Uri?): String? {
    uri ?: return null

    val cursor = context.contentResolver.query(uri, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val nameIndex = it.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
            if (nameIndex != -1) {
                return it.getString(nameIndex)
            }
        }
    }

    // fallback: URI의 마지막 경로 세그먼트 사용
    return uri.lastPathSegment
}