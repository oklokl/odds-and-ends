package com.krdondon.txt.ui.screens

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.krdondon.txt.utils.FileManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    context: Context,
    existingUri: Uri?,
    initialFileName: String,
    initialText: String,
    hasPdfPair: Boolean,
    onSaveAndBack: () -> Unit,
    onBack: () -> Unit
) {
    var currentUri by remember { mutableStateOf(existingUri) }
    var currentHasPdf by remember { mutableStateOf(hasPdfPair) }

    var fileName by remember { mutableStateOf(initialFileName) }
    var tfv by remember(initialText) { mutableStateOf(TextFieldValue(initialText)) }

    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var showRenameDialog by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf(fileName.substringBeforeLast(".")) }

    var showSaveDialog by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tfv.text) {
        val nearBottom = (scrollState.maxValue - scrollState.value) < 120
        if (nearBottom) scrollState.scrollTo(scrollState.maxValue)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextButton(onClick = {
                        renameValue = fileName.substringBeforeLast(".")
                        showRenameDialog = true
                    }) {
                        Column {
                            Text(fileName)
                            if (currentHasPdf) {
                                Text(
                                    "PDF linked",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = { TextButton(onClick = { showSaveDialog = true }) { Text("Save") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
        ) {
            OutlinedTextField(
                value = tfv,
                onValueChange = { tfv = it },
                modifier = Modifier.fillMaxSize().verticalScroll(scrollState),
                placeholder = { Text("Write here...") }
            )

            status?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename File") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    singleLine = true,
                    label = { Text("File Name") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = renameValue.trim().ifEmpty { "doc" }
                    val newName = FileManager.ensureExtension(trimmed, "txt")
                    fileName = newName
                    showRenameDialog = false

                    val uri = currentUri
                    if (uri != null) {
                        scope.launch {
                            val ok = FileManager.renameInDownloads(context, uri, newName)
                            status = if (ok) "Renamed" else "Rename failed"
                        }
                    } else {
                        status = "Renamed: $fileName"
                    }
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") } }
        )
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save to Downloads") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

                    // TXT only save
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            showSaveDialog = false
                            focusManager.clearFocus()

                            scope.launch {
                                runCatching {
                                    val name = FileManager.ensureExtension(fileName, "txt")
                                    fileName = name

                                    val uri = currentUri
                                    if (uri != null) {
                                        FileManager.overwriteTxtUri(context, uri, tfv.text)
                                        // If has PDF pair, update PDF too
                                        if (currentHasPdf) {
                                            val pdfUri = FileManager.findLinkedPdfUri(context, name)
                                            if (pdfUri != null) {
                                                FileManager.overwritePdfUri(context, pdfUri, tfv.text)
                                            }
                                        }
                                        uri
                                    } else {
                                        val newUri = FileManager.saveTxtToDownloads(context, name, tfv.text)
                                        currentUri = newUri
                                        newUri
                                    }
                                }.onSuccess {
                                    onSaveAndBack()
                                }.onFailure {
                                    status = "Save failed: ${it.message}"
                                }
                            }
                        }
                    ) {
                        Text(if (currentHasPdf) "Save TXT + PDF" else "Save TXT only")
                    }

                    // Save as TXT + PDF pair
                    if (!currentHasPdf) {
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                showSaveDialog = false
                                focusManager.clearFocus()

                                scope.launch {
                                    runCatching {
                                        val baseName = fileName.substringBeforeLast(".")
                                        val (txtUri, _) = FileManager.saveTxtAndPdfToDownloads(
                                            context, baseName, tfv.text
                                        )
                                        currentUri = txtUri
                                        fileName = FileManager.ensureExtension(baseName, "txt")
                                        currentHasPdf = true
                                    }.onSuccess {
                                        onSaveAndBack()
                                    }.onFailure {
                                        status = "Save failed: ${it.message}"
                                    }
                                }
                            }
                        ) { Text("Save TXT + PDF") }
                    }

                    // PDF only (export)
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary
                        ),
                        onClick = {
                            showSaveDialog = false
                            focusManager.clearFocus()

                            scope.launch {
                                runCatching {
                                    FileManager.savePdfToDownloads(context, fileName, tfv.text)
                                }.onSuccess {
                                    onSaveAndBack()
                                }.onFailure {
                                    status = "PDF save failed: ${it.message}"
                                }
                            }
                        }
                    ) { Text("Export PDF only (new file)") }
                }
            },
            confirmButton = { TextButton(onClick = { showSaveDialog = false }) { Text("Close") } }
        )
    }
}
