package com.krdondon.Replacestring

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {

    private lateinit var ageSignalsCompliance: AgeSignalsCompliance

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request Play Age Signals at runtime. The result stays in memory only and
        // defaults to UNKNOWN whenever sharing is unavailable or an error occurs.
        ageSignalsCompliance = AgeSignalsCompliance(applicationContext)
        ageSignalsCompliance.refresh(this)

        setContent {
            MaterialTheme {
                WordReplacerApp()
            }
        }
    }
}

private enum class ExpandedEditor {
    ORIGINAL,
    RESULT
}

@Composable
fun WordReplacerApp() {
    var targetWord by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var originalText by remember { mutableStateOf("") }
    var replacedText by remember { mutableStateOf("") }

    var expandedEditor by remember { mutableStateOf<ExpandedEditor?>(null) }
    var originalHasFocus by remember { mutableStateOf(false) }
    var resultHasFocus by remember { mutableStateOf(false) }
    var focusToRestore by remember { mutableStateOf<ExpandedEditor?>(null) }

    val context = LocalContext.current
    val originalFocusRequester = remember { FocusRequester() }
    val resultFocusRequester = remember { FocusRequester() }

    LaunchedEffect(expandedEditor) {
        when (focusToRestore) {
            ExpandedEditor.ORIGINAL -> originalFocusRequester.requestFocus()
            ExpandedEditor.RESULT -> resultFocusRequester.requestFocus()
            null -> Unit
        }
        focusToRestore = null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()   // 상태바 + 네비게이션 바 만큼 안전 영역 확보
            .imePadding()          // 키보드가 나타나면 남은 높이에 맞춰 자동 축소, 사라지면 자동 확장
            .padding(16.dp)
    ) {
        when (expandedEditor) {
            ExpandedEditor.ORIGINAL -> {
                ExpandableTextEditor(
                    value = originalText,
                    onValueChange = { originalText = it },
                    label = "여기에 전체 텍스트를 입력하세요...",
                    expanded = true,
                    onExpandToggle = {
                        focusToRestore = if (originalHasFocus) ExpandedEditor.ORIGINAL else null
                        expandedEditor = null
                    },
                    focusRequester = originalFocusRequester,
                    onFocusChanged = { originalHasFocus = it },
                    modifier = Modifier.fillMaxSize(),
                    bottomEndContent = {
                        Button(
                            onClick = {
                                targetWord = ""
                                replacement = ""
                                originalText = ""
                                replacedText = ""
                                Toast.makeText(
                                    context,
                                    "모든 입력값이 지워졌습니다.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            modifier = Modifier.padding(8.dp)
                        ) {
                            Text("Clean")
                        }
                    }
                )
            }

            ExpandedEditor.RESULT -> {
                ExpandableTextEditor(
                    value = replacedText,
                    onValueChange = { replacedText = it },
                    label = "수정 가능한 결과",
                    expanded = true,
                    onExpandToggle = {
                        focusToRestore = if (resultHasFocus) ExpandedEditor.RESULT else null
                        expandedEditor = null
                    },
                    focusRequester = resultFocusRequester,
                    onFocusChanged = { resultHasFocus = it },
                    modifier = Modifier.fillMaxSize()
                )
            }

            null -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    OutlinedTextField(
                        value = targetWord,
                        onValueChange = { targetWord = it },
                        label = { Text("대체할 단어") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = replacement,
                        onValueChange = { replacement = it },
                        label = { Text("대체될 단어") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    ExpandableTextEditor(
                        value = originalText,
                        onValueChange = { originalText = it },
                        label = "여기에 전체 텍스트를 입력하세요...",
                        expanded = false,
                        onExpandToggle = {
                            focusToRestore = if (originalHasFocus) ExpandedEditor.ORIGINAL else null
                            expandedEditor = ExpandedEditor.ORIGINAL
                        },
                        focusRequester = originalFocusRequester,
                        onFocusChanged = { originalHasFocus = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        bottomEndContent = {
                            Button(
                                onClick = {
                                    targetWord = ""
                                    replacement = ""
                                    originalText = ""
                                    replacedText = ""
                                    Toast.makeText(
                                        context,
                                        "모든 입력값이 지워졌습니다.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                modifier = Modifier.padding(8.dp)
                            ) {
                                Text("Clean")
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        Button(
                            onClick = {
                                // 빈 문자열을 replace()의 검색어로 넘기면 원문 문자 사이마다
                                // replacement가 삽입되어 결과 문자열이 비정상적으로 커질 수 있습니다.
                                // 불필요한 대량 메모리 할당을 막기 위해 빈 검색어는 실행하지 않습니다.
                                if (targetWord.isEmpty()) {
                                    Toast.makeText(
                                        context,
                                        "대체할 단어를 입력해 주세요.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    replacedText = originalText.replace(targetWord, replacement)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("단어 대체")
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = {
                                if (replacedText.isNotEmpty()) {
                                    val clipboardManager = context.getSystemService(
                                        Context.CLIPBOARD_SERVICE
                                    ) as ClipboardManager
                                    val clipData = ClipData.newPlainText("Replaced Text", replacedText)
                                    clipboardManager.setPrimaryClip(clipData)

                                    Toast.makeText(
                                        context,
                                        "결과가 클립보드에 복사되었습니다.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    Toast.makeText(
                                        context,
                                        "복사할 내용이 없습니다.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = replacedText.isNotEmpty()
                        ) {
                            Text("결과 복사")
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        "결과:",
                        fontSize = 18.sp,
                        style = MaterialTheme.typography.headlineSmall
                    )

                    ExpandableTextEditor(
                        value = replacedText,
                        onValueChange = { replacedText = it },
                        label = "수정 가능한 결과",
                        expanded = false,
                        onExpandToggle = {
                            focusToRestore = if (resultHasFocus) ExpandedEditor.RESULT else null
                            expandedEditor = ExpandedEditor.RESULT
                        },
                        focusRequester = resultFocusRequester,
                        onFocusChanged = { resultHasFocus = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .weight(1f)
                    )
                }
            }
        }
    }
}

/**
 * 여러 줄 텍스트 편집창에 하단 테두리형 "확대/축소" 컨트롤을 붙입니다.
 * expanded 상태에서는 부모가 화면 전체의 사용 가능한 영역을 제공하므로,
 * IME가 보일 때는 키보드 위까지, IME가 사라지면 화면 하단까지 자동으로 늘어납니다.
 */
@Composable
private fun ExpandableTextEditor(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    expanded: Boolean,
    onExpandToggle: () -> Unit,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    bottomEndContent: (@Composable BoxScope.() -> Unit)? = null
) {
    Box(
        modifier = modifier.padding(bottom = 8.dp)
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .onFocusChanged { onFocusChanged(it.isFocused) }
        )

        // 텍스트를 배경색 위에 올려 테두리의 일부처럼 보이도록 배치합니다.
        Text(
            text = if (expanded) "축소" else "확대",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 14.sp,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = 12.dp, y = 8.dp)
                .background(MaterialTheme.colorScheme.surface)
                .clickable(role = Role.Button, onClick = onExpandToggle)
                .padding(horizontal = 7.dp, vertical = 2.dp)
        )

        if (bottomEndContent != null) {
            Box(
                modifier = Modifier.align(Alignment.BottomEnd),
                content = bottomEndContent
            )
        }
    }
}
