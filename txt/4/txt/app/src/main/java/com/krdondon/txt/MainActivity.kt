package com.krdondon.txt

import android.net.Uri
import android.os.Bundle
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TxtTheme { MainScreen() } }
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
fun MainScreen() {
    val context = LocalContext.current
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()

    var refreshKey by remember { mutableIntStateOf(0) }
    var files by remember { mutableStateOf<List<FileItem>>(emptyList()) }

    LaunchedEffect(refreshKey) {
        files = FileManager.listDownloadDocs(context)
    }

    NavHost(navController = navController, startDestination = Routes.LIST) {

        composable(Routes.LIST) {
            FileListScreen(
                files = files,
                onOpenFile = { item ->
                    if (item.name.lowercase().endsWith(".pdf")) {
                        // PDF: Find linked TXT file and open in editor
                        scope.launch {
                            val linkedTxt = FileManager.findLinkedTxtFile(context, item.name)
                            if (linkedTxt != null) {
                                // Found linked TXT, open in editor
                                val eUri = URLEncoder.encode(linkedTxt.uri.toString(), Charsets.UTF_8.name())
                                val eName = URLEncoder.encode(linkedTxt.name, Charsets.UTF_8.name())
                                navController.navigate("${Routes.EDITOR}/$eUri/$eName/true")
                            } else {
                                // No linked TXT: open PDF in *in-app* viewer
                                val eUri = URLEncoder.encode(item.uri.toString(), Charsets.UTF_8.name())
                                val eName = URLEncoder.encode(item.name, Charsets.UTF_8.name())
                                navController.navigate("${Routes.PDF_VIEWER}/$eUri/$eName")
                            }
                        }
                    } else {
                        // TXT: Open directly in editor
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
                onViewPdf = { item ->
                    // View PDF in *in-app* viewer
                    val eUri = URLEncoder.encode(item.uri.toString(), Charsets.UTF_8.name())
                    val eName = URLEncoder.encode(item.name, Charsets.UTF_8.name())
                    navController.navigate("${Routes.PDF_VIEWER}/$eUri/$eName")
                }
            )
        }

        // In-app PDF viewer
        composable(
            route = "${Routes.PDF_VIEWER}/{${Routes.ARG_URI}}/{${Routes.ARG_NAME}}",
            arguments = listOf(
                navArgument(Routes.ARG_URI) { type = NavType.StringType },
                navArgument(Routes.ARG_NAME) { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val rawUri = backStackEntry.arguments?.getString(Routes.ARG_URI) ?: return@composable
            val rawName = backStackEntry.arguments?.getString(Routes.ARG_NAME) ?: "PDF"

            val pdfUri = Uri.parse(URLDecoder.decode(rawUri, Charsets.UTF_8.name()))
            val fileName = URLDecoder.decode(rawName, Charsets.UTF_8.name())

            PdfViewerScreen(
                pdfUri = pdfUri,
                title = fileName,
                onBack = { navController.popBackStack() }
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
                    initialText = FileManager.readTextFromUri(context, existingUri)
                    isLoading = false
                } else {
                    isLoading = false
                    initialText = ""
                }
            }

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
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
