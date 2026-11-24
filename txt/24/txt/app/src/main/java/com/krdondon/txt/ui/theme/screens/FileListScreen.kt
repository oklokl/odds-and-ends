package com.krdondon.txt.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.krdondon.txt.model.FileItem
import com.krdondon.txt.utils.FileManager
import kotlinx.coroutines.launch

@Composable
fun FileListScreen(
    files: List<FileItem>,
    onOpenFile: (FileItem) -> Unit,
    onDeleteFile: (FileItem) -> Unit,
    onNewDocument: () -> Unit,
    onViewPdf: (FileItem) -> Unit = {},
    onImportCompleted: () -> Unit
)
{

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

// SAF: user selects an external txt/pdf once -> we copy it into Downloads (MediaStore) so it appears in the list.
    val openDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                scope.launch {
                    FileManager.importIntoDownloads(context, uri)
                    onImportCompleted()
                }
            }
        }
    )

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onNewDocument) { Text("+") }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "TXT Documents",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { openDocLauncher.launch(arrayOf("text/plain", "application/pdf")) }) {
                    Icon(Icons.Filled.Folder, contentDescription = "Import")
                }
            }
            Spacer(Modifier.height(12.dp))

            if (files.isEmpty()) {
                Text("No documents. Tap + to create a new one.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(files, key = { it.uri.toString() }) { item ->
                        val isPdf = item.name.lowercase().endsWith(".pdf")

                        Card(onClick = { onOpenFile(item) }) {
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        if (isPdf) "PDF - tap to edit linked TXT" else "TXT - editable",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isPdf) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Row {
                                    if (isPdf) {
                                        IconButton(onClick = { onViewPdf(item) }) {
                                            Icon(
                                                Icons.Filled.Visibility,
                                                contentDescription = "View PDF",
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    IconButton(onClick = { onDeleteFile(item) }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
