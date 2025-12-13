package com.krdondon.txt.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.krdondon.txt.model.FileItem

@Composable
fun FileListScreen(
    files: List<FileItem>,
    onOpenFile: (FileItem) -> Unit,
    onDeleteFile: (FileItem) -> Unit,
    onNewDocument: () -> Unit,
    onViewPdf: (FileItem) -> Unit = {}
) {
    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onNewDocument) { Text("+") }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("TXT Documents", style = MaterialTheme.typography.headlineMedium)
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
