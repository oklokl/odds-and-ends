package com.krdondon.txt

import android.content.Context
import android.content.Intent
import android.widget.Toast
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.krdondon.txt.model.FileItem
import com.krdondon.txt.ui.screens.EditorScreen
import com.krdondon.txt.ui.screens.FileListScreen
import com.krdondon.txt.ui.screens.PdfViewerScreen
import com.krdondon.txt.ui.theme.TxtTheme
import com.krdondon.txt.utils.FileManager
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.net.URLEncoder

private const val PREFS_OPEN_URI_CACHE = "open_uri_cache_v1"

private fun loadCachedPdfUri(context: Context, fileName: String): Uri? {
    val key = "pdf:$fileName"
    val sp = context.getSharedPreferences(PREFS_OPEN_URI_CACHE, Context.MODE_PRIVATE)
    val raw = sp.getString(key, null) ?: return null
    val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null

    // 권한이 살아있는지 1회 검증(권한 만료/삭제된 경우 캐시 제거)
    return try {
        context.contentResolver.openInputStream(uri)?.close()
        uri
    } catch (_: Throwable) {
        sp.edit().remove(key).apply()
        null
    }
}

private fun saveCachedPdfUri(context: Context, fileName: String, uri: Uri) {
    val key = "pdf:$fileName"
    val sp = context.getSharedPreferences(PREFS_OPEN_URI_CACHE, Context.MODE_PRIVATE)
    sp.edit().putString(key, uri.toString()).apply()
}

// External open request (from file managers like "My Files")
data class OpenRequest(
    val uri: Uri,
    val name: String,
    val mimeType: String?,
    val isPdf: Boolean
)

class MainActivity : ComponentActivity() {

    private val openRequestState = mutableStateOf<OpenRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openRequestState.value = buildOpenRequest(intent)

        setContent {
            TxtTheme {
                MainScreen(
                    openRequest = openRequestState.value,
                    onOpenRequestConsumed = { openRequestState.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        this.intent = intent
        openRequestState.value = buildOpenRequest(intent)
    }

    private fun buildOpenRequest(intent: Intent?): OpenRequest? {
        if (intent == null) return null
        if (intent.action != Intent.ACTION_VIEW) return null

        val dataUri = intent.data ?: return null
        val mime = intent.type

        val displayName = queryDisplayName(dataUri) ?: dataUri.lastPathSegment ?: "document"
        val name = displayName.substringAfterLast('/')

        val isPdf = name.lowercase().endsWith(".pdf") || mime == "application/pdf"

        // Some file managers pass DownloadsStorageProvider URIs that our app cannot read directly.
        // If the file exists in MediaStore Downloads, resolve to that URI (our app can read it reliably).
        val cached = if (isPdf) loadCachedPdfUri(this, name) else null

        val resolved = cached ?: resolveDownloadsMediaStoreUriByName(name) ?: dataUri

        return OpenRequest(uri = resolved, name = name, mimeType = mime, isPdf = isPdf)
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun downloadsCollection(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Files.getContentUri("external")
        }

    private fun resolveDownloadsMediaStoreUriByName(displayName: String): Uri? {
        return try {
            val cr = contentResolver
            val collection = downloadsCollection()

            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
            val selectionArgs = arrayOf(displayName)

            cr.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    Uri.withAppendedPath(collection, id.toString())
                } else null
            }
        } catch (_: Throwable) {
            null
        }
    }
}

private object Routes {
    const val LIST = "list"
    const val EDITOR = "editor"
    const val PDF_VIEWER = "pdf_viewer"

    const val ARG_URI = "uri"
    const val ARG_NAME = "name"
    const val ARG_IS_PDF = "isPdf"
}

@Composable
fun MainScreen(
    openRequest: OpenRequest? = null,
    onOpenRequestConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()

    var files by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var refreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(refreshKey) {
        files = FileManager.listDownloadDocs(context)
    }

    // Route external open requests (from "My Files") into the proper screen.
    LaunchedEffect(openRequest) {
        val req = openRequest ?: return@LaunchedEffect

        if (req.isPdf) {
            scope.launch {
                val linkedTxt = FileManager.findLinkedTxtFile(context, req.name)
                if (linkedTxt != null) {
                    val eUri = URLEncoder.encode(linkedTxt.uri.toString(), Charsets.UTF_8.name())
                    val eName = URLEncoder.encode(linkedTxt.name, Charsets.UTF_8.name())
                    navController.navigate("${Routes.EDITOR}/$eUri/$eName/true")
                } else {
                    val eUri = URLEncoder.encode(req.uri.toString(), Charsets.UTF_8.name())
                    val eName = URLEncoder.encode(req.name, Charsets.UTF_8.name())
                    navController.navigate("${Routes.PDF_VIEWER}/$eUri/$eName")
                }
                onOpenRequestConsumed()
            }
        } else {
            val eUri = URLEncoder.encode(req.uri.toString(), Charsets.UTF_8.name())
            val eName = URLEncoder.encode(req.name, Charsets.UTF_8.name())
            navController.navigate("${Routes.EDITOR}/$eUri/$eName/false")
            onOpenRequestConsumed()
        }
    }

    NavHost(navController = navController, startDestination = Routes.LIST) {

        composable(Routes.LIST) {
            FileListScreen(
                files = files,
                onOpenFile = { item ->
                    if (item.name.lowercase().endsWith(".pdf")) {
                        scope.launch {
                            val linkedTxt = FileManager.findLinkedTxtFile(context, item.name)
                            if (linkedTxt != null) {
                                val eUri = URLEncoder.encode(linkedTxt.uri.toString(), Charsets.UTF_8.name())
                                val eName = URLEncoder.encode(linkedTxt.name, Charsets.UTF_8.name())
                                navController.navigate("${Routes.EDITOR}/$eUri/$eName/true")
                            } else {
                                val eUri = URLEncoder.encode(item.uri.toString(), Charsets.UTF_8.name())
                                val eName = URLEncoder.encode(item.name, Charsets.UTF_8.name())
                                navController.navigate("${Routes.PDF_VIEWER}/$eUri/$eName")
                            }
                        }
                    } else {
                        val eUri = URLEncoder.encode(item.uri.toString(), Charsets.UTF_8.name())
                        val eName = URLEncoder.encode(item.name, Charsets.UTF_8.name())
                        navController.navigate("${Routes.EDITOR}/$eUri/$eName/false")
                    }
                },
                onDeleteFile = { item ->
                    scope.launch {
                        FileManager.deleteFromDownloads(context, item.uri)
                        refreshKey++
                    }
                },
                onNewDocument = {
                    val eUri = URLEncoder.encode("new", Charsets.UTF_8.name())
                    val eName = URLEncoder.encode(defaultDocName(), Charsets.UTF_8.name())
                    navController.navigate("${Routes.EDITOR}/$eUri/$eName/false")
                },
                onImportCompleted = { refreshKey++ },
                onViewPdf = { item ->
                    val eUri = URLEncoder.encode(item.uri.toString(), Charsets.UTF_8.name())
                    val eName = URLEncoder.encode(item.name, Charsets.UTF_8.name())
                    navController.navigate("${Routes.PDF_VIEWER}/$eUri/$eName")
                }
            )
        }

        composable(
            route = "${Routes.PDF_VIEWER}/{${Routes.ARG_URI}}/{${Routes.ARG_NAME}}",
            arguments = listOf(
                navArgument(Routes.ARG_URI) { type = NavType.StringType },
                navArgument(Routes.ARG_NAME) { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val rawUri = backStackEntry.arguments?.getString(Routes.ARG_URI) ?: ""
            val rawName = backStackEntry.arguments?.getString(Routes.ARG_NAME) ?: "PDF"
            val fileName = URLDecoder.decode(rawName, Charsets.UTF_8.name())
            val pdfUri = Uri.parse(URLDecoder.decode(rawUri, Charsets.UTF_8.name()))

            PdfViewerScreen(
                pdfUri = pdfUri,
                title = fileName,
                onBack = { navController.popBackStack() },
                onResolvedUri = { picked -> saveCachedPdfUri(context, fileName, picked) }
            )
        }

        composable(
            route = "${Routes.EDITOR}/{${Routes.ARG_URI}}/{${Routes.ARG_NAME}}/{${Routes.ARG_IS_PDF}}",
            arguments = listOf(
                navArgument(Routes.ARG_URI) { type = NavType.StringType },
                navArgument(Routes.ARG_NAME) { type = NavType.StringType },
                navArgument(Routes.ARG_IS_PDF) { type = NavType.BoolType }
            )
        ) { backStackEntry ->
            val rawUri = backStackEntry.arguments?.getString(Routes.ARG_URI) ?: "new"
            val rawName = backStackEntry.arguments?.getString(Routes.ARG_NAME) ?: defaultDocName()
            val isPdfLinked = backStackEntry.arguments?.getBoolean(Routes.ARG_IS_PDF) ?: false

            val fileName = URLDecoder.decode(rawName, Charsets.UTF_8.name())
            val existingUri: Uri? =
                if (rawUri == "new") null
                else Uri.parse(URLDecoder.decode(rawUri, Charsets.UTF_8.name()))

            var isLoading by remember { mutableStateOf(existingUri != null) }
            var initialText by remember(existingUri) { mutableStateOf("") }

            LaunchedEffect(existingUri) {
                if (existingUri != null) {
                    try {
                        initialText = FileManager.readTextFromUri(context, existingUri)
                    } catch (t: Throwable) {
                        Toast.makeText(
                            context,
                            "파일을 열 수 없습니다. 파일을 '다운로드'에 저장한 뒤 다시 시도해 주세요.",
                            Toast.LENGTH_LONG
                        ).show()
                        initialText = ""
                    } finally {
                        isLoading = false
                    }
                }
            }

            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                EditorScreen(
                    context = context,
                    existingUri = existingUri,
                    initialFileName = fileName,
                    initialText = initialText,
                    hasPdfPair = isPdfLinked,
                    onSaveAndBack = {
                        refreshKey++
                        navController.popBackStack()
                    },
                    onBack = {
                        refreshKey++
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}

private fun defaultDocName(): String {
    val now = java.time.LocalDateTime.now()
    val date = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
    return "doc_$date.txt"
}
