package com.krdondon.txt.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
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
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var handle by remember { mutableStateOf<PdfHandle?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    // 확대 상태일 때는 페이지 넘김(세로 스크롤) 잠금
    var pagerScrollEnabled by remember { mutableStateOf(true) }

    // ===== 암호 입력 다이얼로그 상태 =====
    var showPasswordDialog by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pdfUri) {
        loading = true
        error = null
        showPasswordDialog = false
        password = ""
        passwordError = null

        handle?.close()
        handle = null

        try {
            handle = openPdf(context, pdfUri)
        } catch (t: Throwable) {
            if (isPasswordRequired(t)) {
                showPasswordDialog = true
            } else {
                error = t.message ?: "Failed to open PDF"
            }
        } finally {
            loading = false
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
                            passwordError = null
                            error = null

                            handle?.close()
                            handle = null

                            try {
                                handle = openPdf(context, pdfUri, pw)
                                showPasswordDialog = false
                            } catch (e: InvalidPasswordException) {
                                passwordError = "암호가 올바르지 않습니다."
                            } catch (t: Throwable) {
                                passwordError = t.message ?: "암호 확인 중 오류가 발생했습니다."
                            } finally {
                                loading = false
                            }
                        }
                    }
                ) { Text("열기") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showPasswordDialog = false
                        error = "암호 입력이 필요합니다."
                    }
                ) { Text("취소") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        }
    ) { padding ->

        when {
            loading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            }

            error != null -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("PDF를 열 수 없습니다.", style = MaterialTheme.typography.titleMedium)
                    Text(error!!, style = MaterialTheme.typography.bodyMedium)
                }
            }

            handle == null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (showPasswordDialog) "암호를 입력해 주세요." else "PDF를 열 수 없습니다.")
                }
            }

            else -> {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    val pageCount = handle!!.renderer.pageCount
                    val viewportWidthPx = with(density) { maxWidth.toPx().toInt().coerceAtLeast(1) }
                    val viewportHeightPx = with(density) { maxHeight.toPx().toInt().coerceAtLeast(1) }

                    val pagerState = rememberPagerState(pageCount = { pageCount })

                    Box(Modifier.fillMaxSize()) {

                        VerticalPager(
                            state = pagerState,
                            userScrollEnabled = pagerScrollEnabled,
                            modifier = Modifier.fillMaxSize()
                        ) { pageIndex ->
                            ZoomablePdfPage(
                                handle = handle!!,
                                pageIndex = pageIndex,
                                pageCount = pageCount,
                                viewportWidthPx = viewportWidthPx,
                                viewportHeightPx = viewportHeightPx,
                                onZoomActiveChanged = { zoomActive ->
                                    pagerScrollEnabled = !zoomActive
                                }
                            )
                        }

                        Surface(
                            tonalElevation = 3.dp,
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val canPrev = pagerState.currentPage > 0
                                val canNext = pagerState.currentPage < pageCount - 1

                                Button(
                                    onClick = {
                                        if (canPrev) {
                                            scope.launch {
                                                pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                            }
                                        }
                                    },
                                    enabled = canPrev,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                ) { Text("◀") }

                                Text(
                                    text = "${pagerState.currentPage + 1} / $pageCount",
                                    style = MaterialTheme.typography.bodySmall
                                )

                                Button(
                                    onClick = {
                                        if (canNext) {
                                            scope.launch {
                                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                            }
                                        }
                                    },
                                    enabled = canNext,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                ) { Text("▶") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomablePdfPage(
    handle: PdfHandle,
    pageIndex: Int,
    pageCount: Int,
    viewportWidthPx: Int,
    viewportHeightPx: Int,
    onZoomActiveChanged: (Boolean) -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val zoomActive = scale > 1.01f || abs(offset.x) > 0.5f || abs(offset.y) > 0.5f
    LaunchedEffect(zoomActive) { onZoomActiveChanged(zoomActive) }

    val bmpState by produceState<Bitmap?>(
        initialValue = null,
        key1 = pageIndex,
        key2 = viewportWidthPx
    ) {
        value = null
        val renderScale = 1.5f
        val targetWidth = (viewportWidthPx * renderScale).toInt().coerceAtLeast(1)
        value = renderPage(handle, pageIndex, targetWidth)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxSize(),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                scale = 1f
                                offset = Offset.Zero
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(1f, 5f)
                            scale = newScale

                            if (scale <= 1.01f) {
                                offset = Offset.Zero
                            } else {
                                val newOffset = offset + pan
                                val maxX = (viewportWidthPx * (scale - 1f)) / 2f
                                val maxY = (viewportHeightPx * (scale - 1f)) / 2f

                                offset = Offset(
                                    x = newOffset.x.coerceIn(-maxX, maxX),
                                    y = newOffset.y.coerceIn(-maxY, maxY)
                                )
                            }
                        }
                    }
            ) {
                if (bmpState == null) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator()
                        Text("Rendering ${pageIndex + 1} / $pageCount")
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                    ) {
                        Image(
                            bitmap = bmpState!!.asImageBitmap(),
                            contentDescription = "page ${pageIndex + 1}",
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopCenter)
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = offset.x
                                    translationY = offset.y
                                }
                        )

                        Surface(
                            tonalElevation = 2.dp,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                        ) {
                            Text(
                                text = "${pageIndex + 1} / $pageCount",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

private class PdfHandle(
    val pfd: ParcelFileDescriptor,
    val renderer: PdfRenderer,
    val tempFile: File? = null
) {
    fun close() {
        try { renderer.close() } catch (_: Throwable) {}
        try { pfd.close() } catch (_: Throwable) {}
        try { tempFile?.delete() } catch (_: Throwable) {}
    }
}

private suspend fun openPdf(context: Context, uri: Uri, password: String? = null): PdfHandle =
    withContext(Dispatchers.IO) {

        if (password.isNullOrBlank()) {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("Cannot open file descriptor for: $uri")
            val renderer = PdfRenderer(pfd)
            return@withContext PdfHandle(pfd, renderer, tempFile = null)
        }

        val unlocked = decryptPdfToCache(context, uri, password)

        val pfd = ParcelFileDescriptor.open(unlocked, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        PdfHandle(pfd, renderer, tempFile = unlocked)
    }

private fun decryptPdfToCache(context: Context, uri: Uri, password: String): File {
    PDFBoxResourceLoader.init(context)

    val outFile = File.createTempFile("unlocked_", ".pdf", context.cacheDir)

    val input = context.contentResolver.openInputStream(uri)
        ?: throw IllegalStateException("Cannot open input stream for: $uri")

    input.use {
        PDDocument.load(it, password).use { doc ->
            doc.setAllSecurityToBeRemoved(true)
            doc.save(outFile)
        }
    }

    return outFile
}

private fun isPasswordRequired(t: Throwable): Boolean {
    val msg = (t.message ?: "").lowercase()
    return msg.contains("password required") ||
            msg.contains("password") ||
            msg.contains("encrypted") ||
            (msg.contains("암호") && msg.contains("필요"))
}

private suspend fun renderPage(handle: PdfHandle, pageIndex: Int, targetWidthPx: Int): Bitmap =
    withContext(Dispatchers.IO) {
        val page = handle.renderer.openPage(pageIndex)
        try {
            val pageWidth = page.width.coerceAtLeast(1)
            val pageHeight = page.height.coerceAtLeast(1)

            val targetHeightPx =
                (targetWidthPx.toFloat() * (pageHeight.toFloat() / pageWidth.toFloat()))
                    .toInt()
                    .coerceAtLeast(1)

            val bitmap = Bitmap.createBitmap(targetWidthPx, targetHeightPx, Bitmap.Config.ARGB_8888)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        } finally {
            page.close()
        }
    }
