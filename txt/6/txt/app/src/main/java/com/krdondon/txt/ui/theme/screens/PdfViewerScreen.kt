package com.krdondon.txt.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import androidx.compose.foundation.ExperimentalFoundationApi

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PdfViewerScreen(
    pdfUri: Uri,
    title: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    var handle by remember { mutableStateOf<PdfHandle?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    // 확대 상태일 때는 페이지 넘김(세로 스크롤) 잠금
    var pagerScrollEnabled by remember { mutableStateOf(true) }

    LaunchedEffect(pdfUri) {
        loading = true
        error = null
        handle?.close()
        handle = null

        try {
            handle = openPdf(context, pdfUri)
        } catch (t: Throwable) {
            error = t.message ?: "Failed to open PDF"
        } finally {
            loading = false
        }
    }

    DisposableEffect(Unit) {
        onDispose { handle?.close() }
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
                ) { Text("PDF handle is null") }
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
                    val scope = rememberCoroutineScope()

                    // ✅ 여기서 VerticalPager 위에 버튼을 "오버레이"로 얹습니다.
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

                        // ✅ 이전/다음 페이지 버튼 + 페이지 표시(우측 하단)
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

    // recycle() 사용 금지(크래시 방지). 대신 렌더 스케일을 적당히.
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

    // 페이지 “종이 카드” 느낌
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
                    // 여백(종이 마진 느낌)
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

                        // 페이지 번호 뱃지(기존 유지)
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
    val renderer: PdfRenderer
) {
    fun close() {
        try { renderer.close() } catch (_: Throwable) {}
        try { pfd.close() } catch (_: Throwable) {}
    }
}

private suspend fun openPdf(context: Context, uri: Uri): PdfHandle = withContext(Dispatchers.IO) {
    val pfd = context.contentResolver.openFileDescriptor(uri, "r")
        ?: throw IllegalStateException("Cannot open file descriptor for: $uri")
    val renderer = PdfRenderer(pfd)
    PdfHandle(pfd, renderer)
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
