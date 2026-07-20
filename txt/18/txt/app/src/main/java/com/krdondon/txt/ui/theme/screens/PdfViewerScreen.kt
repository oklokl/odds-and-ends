package com.krdondon.txt.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TextSnippet
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.krdondon.txt.pdf.PageTextLayout
import com.krdondon.txt.pdf.CharBox
import com.krdondon.txt.pdf.extractPageTextLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PdfViewerScreen(
    pdfUri: Uri,
    title: String,
    onBack: () -> Unit,
    onResolvedUri: ((Uri) -> Unit)? = null

) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var handle by remember { mutableStateOf<PdfHandle?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    // 확대 상태일 때는 페이지 넘김(세로 스크롤) 잠금
    var pagerScrollEnabled by remember { mutableStateOf(true) }

    // 현재 페이지/총 페이지 (상단바 및 텍스트 보기용)
    var currentPageIndex by remember { mutableIntStateOf(0) }
    var totalPages by remember { mutableIntStateOf(0) }

    // ===== 텍스트 보기(선택/복사) 바텀시트 상태 =====
    val clipboard = LocalClipboardManager.current
    var showTextSheet by remember { mutableStateOf(false) }
    var sheetPageIndex by remember { mutableIntStateOf(0) }
    var extractedText by remember { mutableStateOf<String?>(null) }
    var extractingText by remember { mutableStateOf(false) }

    // ===== 암호 입력 다이얼로그 상태 =====
    var showPasswordDialog by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf<String?>(null) }

    // NOTE: pdfUri가 바뀌면 currentUri도 초기화
    var currentUri by remember(pdfUri) { mutableStateOf(pdfUri) }

// SAF 재선택(권한 재확보) 플로우
    var needSafReopen by remember { mutableStateOf(false) }
    var safLaunchedOnce by remember { mutableStateOf(false) }

    val reopenPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { pickedUri: Uri? ->
        if (pickedUri == null) {
            error = "파일 선택이 취소되었습니다."
            needSafReopen = false
            safLaunchedOnce = false
            return@rememberLauncherForActivityResult
        }

        try {
            context.contentResolver.takePersistableUriPermission(
                pickedUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Throwable) { }

        currentUri = pickedUri
        needSafReopen = false
        safLaunchedOnce = false

        onResolvedUri?.invoke(pickedUri)  // ✅ 바로 이 줄은 여기 들어갑니다
    }



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
                            passwordError = null
                            error = null

                            handle?.close()
                            handle = null

                            try {
                                handle = openPdf(context, currentUri, pw)
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


    // ===== 텍스트 보기(선택/복사) 바텀시트 =====
    if (showTextSheet && handle != null) {
        LaunchedEffect(sheetPageIndex, handle, currentUri) {
            extractingText = true
            extractedText = null
            try {
                extractedText = extractPageText(context, handle!!, currentUri, sheetPageIndex)
            } catch (t: Throwable) {
                extractedText = "텍스트 추출 실패: " + (t.message ?: t.javaClass.simpleName)
            } finally {
                extractingText = false
            }
        }

        ModalBottomSheet(
            onDismissRequest = { showTextSheet = false },
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "텍스트 (${sheetPageIndex + 1} / ${totalPages.coerceAtLeast(1)})",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                val all = extractedText.orEmpty()
                                clipboard.setText(AnnotatedString(all))
                            },
                            enabled = !extractingText && !extractedText.isNullOrBlank()
                        ) { Text("전체 복사") }

                        TextButton(onClick = { showTextSheet = false }) { Text("닫기") }
                    }
                }

                Text(
                    text = "주의: 이 화면은 PDFBox로 텍스트를 추출한 결과입니다. 원본 레이아웃/줄바꿈은 다를 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (extractingText) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) { CircularProgressIndicator() }
                } else {
                    val scroll = rememberScrollState()
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        tonalElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SelectionContainer {
                            Text(
                                text = extractedText.orEmpty(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                                    .heightIn(min = 200.dp, max = 520.dp)
                                    .verticalScroll(scroll),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    IconButton(
                        onClick = {
                            sheetPageIndex = currentPageIndex
                            showTextSheet = true
                        },
                        enabled = handle != null && !loading && error == null
                    ) {
                        Icon(Icons.Outlined.TextSnippet, contentDescription = "텍스트")
                    }
                }
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

                    totalPages = pageCount

                    LaunchedEffect(pagerState.currentPage) {
                        currentPageIndex = pagerState.currentPage
                    }

                    Box(Modifier.fillMaxSize()) {

                        VerticalPager(
                            state = pagerState,
                            userScrollEnabled = pagerScrollEnabled,
                            modifier = Modifier.fillMaxSize()
                        ) { pageIndex ->
                            ZoomablePdfPage(
                                handle = handle!!,
                                sourceUri = currentUri,
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
    sourceUri: Uri,
    pageIndex: Int,
    pageCount: Int,
    viewportWidthPx: Int,
    viewportHeightPx: Int,
    onZoomActiveChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

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

    // ===== 텍스트 레이어(글자 박스) 로드: 부분 선택을 위해 필요 =====
    val layoutState by produceState<PageTextLayout?>(
        initialValue = null,
        key1 = pageIndex,
        key2 = sourceUri,
        key3 = handle.tempFile?.absolutePath
    ) {
        value = try {
            extractPageTextLayout(context, sourceUri, handle.tempFile, pageIndex)
        } catch (_: Throwable) {
            null
        }
    }

    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    var selectionStart by remember { mutableStateOf<Offset?>(null) }
    var selectionRect by remember { mutableStateOf<Rect?>(null) }
    var selectedText by remember { mutableStateOf("") }
    var showContextMenu by remember { mutableStateOf(false) }
    var contextMenuOffset by remember { mutableStateOf(DpOffset(0.dp, 0.dp)) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 8.dp),
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
                            .onSizeChanged { containerSize = it }
                            // 롱프레스 후 드래그: 사각형 영역 선택
                            .pointerInput(bmpState, layoutState, scale, offset, containerSize) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { down ->
                                        showContextMenu = false
                                        selectedText = ""

                                        // 외부 좌표 -> 콘텐츠(변환 전) 좌표로 역변환
                                        val p = Offset(
                                            x = (down.x - offset.x) / scale,
                                            y = (down.y - offset.y) / scale
                                        )
                                        selectionStart = p
                                        selectionRect = Rect(p, p)
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        val start = selectionStart ?: return@detectDragGesturesAfterLongPress
                                        val p = Offset(
                                            x = (change.position.x - offset.x) / scale,
                                            y = (change.position.y - offset.y) / scale
                                        )
                                        selectionRect = Rect(start, p)
                                    },
                                    onDragEnd = {
                                        val rect = selectionRect
                                        val layout = layoutState
                                        val bmp = bmpState
                                        if (rect == null || layout == null || bmp == null) {
                                            selectedText = ""
                                            showContextMenu = false
                                            return@detectDragGesturesAfterLongPress
                                        }

                                        val containerW = containerSize.width.toFloat()
                                        val containerH = containerSize.height.toFloat()
                                        if (containerW <= 0f || containerH <= 0f) return@detectDragGesturesAfterLongPress

                                        val bmpW = bmp.width.toFloat()
                                        val bmpH = bmp.height.toFloat()
                                        val fitScale = min(containerW / bmpW, containerH / bmpH)
                                        val dispW = bmpW * fitScale
                                        val dispH = bmpH * fitScale
                                        val padX = (containerW - dispW) / 2f
                                        val padY = (containerH - dispH) / 2f

                                        fun charRect(cb: CharBox): Rect {
                                            val left = padX + (cb.xPt / layout.pageWidthPt) * dispW
                                            val top = padY + (cb.yTopPt / layout.pageHeightPt) * dispH
                                            val w = (cb.wPt / layout.pageWidthPt) * dispW
                                            val h = (cb.hPt / layout.pageHeightPt) * dispH
                                            return Rect(left, top, left + w, top + h)
                                        }

                                        val norm = Rect(
                                            left = min(rect.left, rect.right),
                                            top = min(rect.top, rect.bottom),
                                            right = maxOf(rect.left, rect.right),
                                            bottom = maxOf(rect.top, rect.bottom)
                                        )

                                        val picked = layout.chars
                                            .asSequence()
                                            .filter { cb ->
                                                val r = charRect(cb)
                                                r.overlaps(norm)
                                            }
                                            .sortedBy { it.index }
                                            .map { it.text }
                                            .joinToString(separator = "")
                                            .trim()

                                        selectedText = picked
                                        showContextMenu = picked.isNotBlank()

                                        if (picked.isNotBlank()) {
                                            val endContent = norm.bottomRight
                                            val endOuter = Offset(
                                                x = endContent.x * scale + offset.x,
                                                y = endContent.y * scale + offset.y
                                            )
                                            contextMenuOffset = with(density) {
                                                DpOffset(endOuter.x.toDp(), endOuter.y.toDp())
                                            }
                                        }
                                    },
                                    onDragCancel = {
                                        selectionRect = null
                                        selectionStart = null
                                        selectedText = ""
                                        showContextMenu = false
                                    }
                                )
                            }
                    ) {
                        // 이미지 + 선택 하이라이트를 같은 레이어에 두고 함께 zoom/pan 변환 적용
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = offset.x
                                    translationY = offset.y
                                }
                        ) {
                            Image(
                                bitmap = bmpState!!.asImageBitmap(),
                                contentDescription = "page ${pageIndex + 1}",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )

                            // 선택 하이라이트 오버레이
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val rect = selectionRect ?: return@Canvas
                                val bmp = bmpState ?: return@Canvas
                                val layout = layoutState ?: return@Canvas

                                // 선택 사각형(드래그 범위) 시각화
                                drawRect(
                                    color = Color(0x3333B5E5),
                                    topLeft = rect.topLeft,
                                    size = rect.size
                                )

                                val containerW = size.width
                                val containerH = size.height
                                val bmpW = bmp.width.toFloat()
                                val bmpH = bmp.height.toFloat()
                                val fitScale = min(containerW / bmpW, containerH / bmpH)
                                val dispW = bmpW * fitScale
                                val dispH = bmpH * fitScale
                                val padX = (containerW - dispW) / 2f
                                val padY = (containerH - dispH) / 2f

                                fun charRect(cb: CharBox): Rect {
                                    val left = padX + (cb.xPt / layout.pageWidthPt) * dispW
                                    val top = padY + (cb.yTopPt / layout.pageHeightPt) * dispH
                                    val w = (cb.wPt / layout.pageWidthPt) * dispW
                                    val h = (cb.hPt / layout.pageHeightPt) * dispH
                                    return Rect(left, top, left + w, top + h)
                                }

                                val norm = Rect(
                                    left = min(rect.left, rect.right),
                                    top = min(rect.top, rect.bottom),
                                    right = maxOf(rect.left, rect.right),
                                    bottom = maxOf(rect.top, rect.bottom)
                                )

                                // 실제로 선택된 텍스트 영역(글자 박스) 하이라이트
                                layout.chars.asSequence()
                                    .filter { cb -> charRect(cb).overlaps(norm) }
                                    .take(5000) // 안전장치: 지나치게 많은 렌더 방지
                                    .forEach { cb ->
                                        val r = charRect(cb)
                                        drawRect(
                                            color = Color(0x5533B5E5),
                                            topLeft = r.topLeft,
                                            size = r.size
                                        )
                                    }
                            }
                        }

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

                        // 컨텍스트 메뉴 (선택 영역이 있을 때)
                        DropdownMenu(
                            expanded = showContextMenu,
                            onDismissRequest = { showContextMenu = false },
                            offset = contextMenuOffset
                        ) {
                            DropdownMenuItem(
                                text = { Text("복사") },
                                onClick = {
                                    if (selectedText.isNotBlank()) {
                                        clipboard.setText(AnnotatedString(selectedText))
                                    }
                                    showContextMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("페이지 전체 복사") },
                                onClick = {
                                    scope.launch {
                                        try {
                                            val full = extractPageText(context, handle, sourceUri, pageIndex)
                                            if (full.isNotBlank()) clipboard.setText(AnnotatedString(full))
                                        } catch (_: Throwable) {
                                        }
                                    }
                                    showContextMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("선택 해제") },
                                onClick = {
                                    selectionRect = null
                                    selectedText = ""
                                    showContextMenu = false
                                }
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
            try {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IllegalStateException("Cannot open file descriptor for: $uri")
                val renderer = PdfRenderer(pfd)
                return@withContext PdfHandle(pfd, renderer, tempFile = null)
            } catch (t: Throwable) {
                // PdfRenderer는 암호 PDF에서 모호한 예외를 던질 수 있어서 PDFBox로 한 번 더 확인
                try {
                    PDFBoxResourceLoader.init(context)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        PDDocument.load(input).close() // 암호면 여기서 InvalidPasswordException 발생
                    }
                } catch (e: InvalidPasswordException) {
                    throw e // -> isPasswordRequired()가 잡아서 암호 다이얼로그가 뜸
                } catch (_: Throwable) {
                    // 암호가 아닌 다른 오류면 원래 예외 유지
                }
                throw t
            }
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


private suspend fun extractPageText(
    context: Context,
    handle: PdfHandle,
    uri: Uri,
    pageIndex: Int
): String = withContext(Dispatchers.IO) {
    PDFBoxResourceLoader.init(context)

    val doc = if (handle.tempFile != null && handle.tempFile.exists()) {
        PDDocument.load(handle.tempFile)
    } else {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot open input stream for: $uri")
        input.use { PDDocument.load(it) }
    }

    doc.use {
        val stripper = PDFTextStripper()
        val page = pageIndex + 1
        stripper.startPage = page
        stripper.endPage = page
        stripper.getText(it).trim()
    }
}

private fun isPasswordRequired(t: Throwable): Boolean {
    val msg = (t.message ?: "").lowercase()
    return t is InvalidPasswordException ||
            msg.contains("password") ||
            msg.contains("encrypted") ||
            msg.contains("encryption dictionary") ||
            msg.contains("setallsecuritytoberemoved")
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
