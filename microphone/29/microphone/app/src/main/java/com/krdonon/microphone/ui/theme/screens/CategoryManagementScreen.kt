package com.krdonon.microphone.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.krdonon.microphone.data.model.Category
import com.krdonon.microphone.ui.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryManagementScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit
) {
    val categories by viewModel.categories.collectAsState()
    val recordings by viewModel.recordings.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Category?>(null) }
    var deleteTarget by remember { mutableStateOf<Category?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("카테고리 관리") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "뒤로가기"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "카테고리 추가")
                    }
                }
            )
        }
    ) { padding ->
        if (categories.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("등록된 카테고리가 없습니다.\n오른쪽 위 + 버튼으로 추가하세요.")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                items(categories, key = { it.id }) { category ->
                    val fileCount = recordings.count { it.category == category.name }

                    ListItem(
                        leadingContent = {
                            Icon(Icons.Filled.Folder, contentDescription = null)
                        },
                        headlineContent = {
                            Text(category.name)
                        },
                        supportingContent = {
                            Text("녹음 파일 ${fileCount}개")
                        },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { editTarget = category }) {
                                    Icon(
                                        Icons.Filled.Edit,
                                        contentDescription = "이름 변경"
                                    )
                                }
                                IconButton(onClick = { deleteTarget = category }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "삭제"
                                    )
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // 카테고리 선택 → 홈 화면에서 이 카테고리만 보이도록 설정
                                viewModel.setSelectedCategoryFilter(category.name)
                                onNavigateBack()
                            }
                    )
                }
            }
        }
    }

    // 새 카테고리 추가 다이얼로그
    if (showAddDialog) {
        var name by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("새 카테고리") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("카테고리 이름") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        viewModel.addCategory(name.trim())
                        showAddDialog = false
                    }
                }) {
                    Text("저장")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("취소")
                }
            }
        )
    }

    // 이름 변경 다이얼로그
    editTarget?.let { category ->
        var name by remember(category) { mutableStateOf(category.name) }

        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text("카테고리 이름 변경") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("카테고리 이름") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        viewModel.renameCategory(category.id, name.trim())
                        editTarget = null
                    }
                }) {
                    Text("저장")
                }
            },
            dismissButton = {
                TextButton(onClick = { editTarget = null }) {
                    Text("취소")
                }
            }
        )
    }

    // 삭제 확인 다이얼로그
    deleteTarget?.let { category ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("카테고리 삭제") },
            text = {
                Text(
                    "‘${category.name}’ 카테고리를 삭제할까요?\n" +
                            "카테고리는 삭제되지만, 기존 녹음 파일은 그대로 남습니다."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteCategory(category.id)
                    deleteTarget = null
                }) {
                    Text("삭제")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("취소")
                }
            }
        )
    }
}
