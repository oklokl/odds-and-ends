package com.krdondon.txt.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PdfViewerScreen(
    pdfUri: Uri,
    title: String,
    onBack: () -> Unit,
    // 외부 URI가 SAF로 다시 선택되면(=영구 권한을 확보하면) 호출됩니다.
    onResolvedUri: ((Uri) -> Unit)? = null
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // NOTE: pdfUri 파라미터가 바뀌면 currentUri도 초기화되어야 합니다.
    var currentUri by remember(pdfUri) { mutableStateOf(pdfUri) }

    var handle by remember { mutableStateOf<PdfHandle?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    // 확대 상태일 때는 페이지 넘김(세로 스크롤) 잠금
    var pagerScrollEnabled by remember { mutableStateOf(true) }

    // ===== 암호 입력 다이얼로그 상태 =====
    var showPasswordDialog by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf<String?>(null) }

    // ===== SAF 재선택(권한 재확보) 흐름 =====
    var needSafReopen by remember { mutableStateOf(false) }
    var safLaunchedOnce by remember { mutableStateOf(false) }

    val reopenPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { pickedUri: Uri? ->
        if (pickedUri == null) {
            // 사용자가 취소한 경우
            error = "파일 선택이 취소되었습니다."
            needSafReopen = false
            safLaunchedOnce = false
            return@rememberLauncherForActivityResult
        }

        try {
            // 가능하면 영구 권한 확보(재부팅/앱 재실행에도 유지)
            context.contentResolver.takePersistableUriPermission(
                pickedUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Throwable) {
            // 일부 URI는 persistable 이 아닐 수 있음 (그래도 선택 직후에는 열릴 수 있음)
        }

        currentUri = pickedUri
        needSafReopen = false
        safLaunchedOnce = false
        onResolvedUri?.invoke(pickedUri)
    }

    // ===== PDF 오픈: currentUri 기준으로 처리 =====
    LaunchedEffect(currentUri) {
        loading = true
        error = null
        showPasswordDialog = false
        password = ""
        passwordError = null
        needSafReopen = false

        handle?.close()
        handle = null

        try {
            handle = openPdf(context, currentUri)
        } catch (t: Throwable) {
            when {
                t is SecurityException || (t.message?.contains("ACTION_OPEN_DOCUMENT", ignoreCase = true) == true) -> {
                    // DownloadsStorageProvider(msf:xx) 유형은 직접 읽기가 막혀 있어 SAF 재선택이 필요합니다.
                    needSafReopen = true
                }
                isPasswordRequired(t) -> {
                    showPasswordDialog = true
                }
                else -> {
                    error = t.message ?: "Failed to open PDF"
                }
            }
        } finally {
            loading = false
        }
    }

    // 필요하면 자동으로 SAF 선택창을 띄웁니다.
    LaunchedEffect(needSafReopen) {
        if (needSafReopen && !safLaunchedOnce) {
            safLaunchedOnce = true
            reopenPdfLauncher.launch(arrayOf("application/pdf"))
        }
    }

    DisposableEffect(Unit) {
        onDispose { handle?.close() }
    }

    // ===== 암호 입력 다이얼로그 =====
    if (showPasswordDialog) {
        AlertDialog(
            onDismissRequest = {
                showPasswordDialog = false
                error = "암호 입력이 필요합니다."
            },
            title = { Text("PDF 암호 입력") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("이 PDF는 암호가 설정되어 있습니다. 암호를 입력해 주세요.")
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        label = { Text("암호") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                    passwordError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val pw = password
                        if (pw.isBlank()) {
                            passwordError = "암호를 입력해 주세요."
                            return@TextButton
                        }

                        scope.launch {
                            loading = true
                            error = null
                            passwordError = null

                            handle?.close()
                            handle = null

                            try {
                                handle = openPdf(context, currentUri, pw)
                                showPasswordDialog = false
                            } catch (e: Throwable) {
                                if (e is InvalidPasswordException) {
                                    passwordError = "암호가 올바르지 않습니다."
                                } else if (e is SecurityException || (e.message?.contains("ACTION_OPEN_DOCUMENT", ignoreCase = true) == true)) {
                                    // 암호창에서 열기 시도 중 권한 문제이면 SAF로 유도
                                    showPasswordDialog = false
                                    needSafReopen = true
                                } else {
                                    error = e.message ?: "Failed to open PDF"
                                    showPasswordDialog = false
                                }
                            } finally {
                                loading = false
                            }
                        }
                    }
                ) {
                    Text("확인")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPasswordDialog = false
                    error = "암호 입력이 필요합니다."
                }) {
                    Text("취소")
                }
            }
        )
    }

    // ===== UI =====
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                needSafReopen -> {
                    // 자동으로 선택창을 띄우지만, 사용자가 취소했다면 이 화면이 남습니다.
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("PDF를 열 수 없습니다.")
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "이 PDF는 시스템(다운로드/내 파일) 제공 URI 권한 제한으로 인해, 한 번 더 파일 선택이 필요합니다. 아래 버튼을 눌러 같은 PDF를 다시 선택해 주세요.",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Spacer(Modifier.height(12.dp))
                        Button(onClick = {
                            safLaunchedOnce = true
                            reopenPdfLauncher.launch(arrayOf("application/pdf"))
                        }) {
                            Text("PDF 다시 선택")
                        }
                    }
                }

                error != null -> {
                    Text(
                        text = error ?: "Error",
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(20.dp)
                    )
                }

                handle == null -> {
                    Text(
                        text = "No PDF",
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                else -> {
                    val h = handle!!

                    val pagerState = rememberPagerState(initialPage = 0, pageCount = { h.pageCount })

                    // Pinch-to-zoom 상태
                    var scale by remember { mutableStateOf(1f) }
                    var offset by remember { mutableStateOf(Offset.Zero) }

                    // 확대/축소에 따라 pager scroll enable
                    LaunchedEffect(scale) {
                        pagerScrollEnabled = scale <= 1.01f
                    }

                    VerticalPager(
                        state = pagerState,
                        userScrollEnabled = pagerScrollEnabled,
                        modifier = Modifier.fillMaxSize()
                    ) { pageIndex ->
                        val bitmapState = produceState<Bitmap?>(initialValue = null, pageIndex) {
                            value = withContext(Dispatchers.IO) {
                                h.renderPage(context, pageIndex, density)
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 1.dp)
                                .background(MaterialTheme.colorScheme.background)
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        val newScale = (scale * zoom).coerceIn(1f, 5f)
                                        // 확대 상태에서만 pan 적용
                                        val newOffset = if (newScale > 1f) {
                                            offset + pan
                                        } else {
                                            Offset.Zero
                                        }
                                        scale = newScale
                                        offset = newOffset
                                    }
                                }
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onDoubleTap = {
                                            // 더블탭: 1x <-> 2x
                                            if (abs(scale - 1f) < 0.01f) {
                                                scale = 2f
                                            } else {
                                                scale = 1f
                                                offset = Offset.Zero
                                            }
                                        }
                                    )
                                }
                        ) {
                            val bmp = bitmapState.value
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "page $pageIndex",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .graphicsLayer {
                                            scaleX = scale
                                            scaleY = scale
                                            translationX = offset.x
                                            translationY = offset.y
                                        }
                                )
                            } else {
                                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ===== 내부 PDF 처리 (기존과 동일한 패턴 유지) =====
private data class PdfHandle(
    val renderer: PdfRenderer,
    val fileDescriptor: ParcelFileDescriptor,
    val tempFile: File,
    val pageCount: Int
) {
    fun close() {
        try { renderer.close() } catch (_: Throwable) {}
        try { fileDescriptor.close() } catch (_: Throwable) {}
        try { tempFile.delete() } catch (_: Throwable) {}
    }

    suspend fun renderPage(context: Context, index: Int, density: Density): Bitmap = withContext(Dispatchers.IO) {
        val page = renderer.openPage(index)
        try {
            val width = (page.width * density.density).toInt().coerceAtLeast(1)
            val height = (page.height * density.density).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        } finally {
            page.close()
        }
    }
}

private fun isPasswordRequired(t: Throwable): Boolean {
    return t is InvalidPasswordException || (t.message?.contains("password", ignoreCase = true) == true)
}

private suspend fun openPdf(context: Context, uri: Uri, password: String? = null): PdfHandle = withContext(Dispatchers.IO) {
    PDFBoxResourceLoader.init(context)

    val cacheFile = decryptPdfToCache(context, uri, password)
    val pfd = ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
    val renderer = PdfRenderer(pfd)

    PdfHandle(
        renderer = renderer,
        fileDescriptor = pfd,
        tempFile = cacheFile,
        pageCount = renderer.pageCount
    )
}

private suspend fun decryptPdfToCache(context: Context, uri: Uri, password: String?): File = withContext(Dispatchers.IO) {
    // 복사 후 PDFBox로 암호 여부 처리 -> 캐시 파일로 저장
    val cacheDir = context.cacheDir
    val inputFile = File(cacheDir, "input_${System.currentTimeMillis()}.pdf")
    val outputFile = File(cacheDir, "output_${System.currentTimeMillis()}.pdf")

    context.contentResolver.openInputStream(uri)?.use { input ->
        inputFile.outputStream().use { out -> input.copyTo(out) }
    } ?: throw IllegalStateException("Cannot open input stream")

    val doc = if (password.isNullOrBlank()) {
        PDDocument.load(inputFile)
    } else {
        PDDocument.load(inputFile, password)
    }

    doc.use {
        // 암호 PDF를 '복호화된 상태'로 캐시에 저장하려면 암호 해제 플래그가 필요합니다.
        // (그렇지 않으면 "PDF contains an encryption dictionary" 오류로 save()가 실패합니다.)
        try {
            it.setAllSecurityToBeRemoved(true)
        } catch (_: Throwable) {
            // 일부 버전/상황에서 setAllSecurityToBeRemoved가 동작하지 않을 수 있으나,
            // 그 경우에도 일반(비암호) PDF는 그대로 저장됩니다.
        }
        it.save(outputFile)
    }

    try { inputFile.delete() } catch (_: Throwable) {}

    outputFile
}
