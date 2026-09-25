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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.max

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

    // TextField 사이에서 포커스가 이동할 때 일부 IME/에뮬레이터 조합은
    // hide -> show 요청을 아주 짧은 간격으로 연속 발생시킵니다.
    // 기본 imePadding()이 그 중간 프레임까지 그대로 따라가면 weight 영역이
    // 순간적으로 커졌다 작아지며 화면이 튀어 보일 수 있으므로, IME 하단
    // 여백은 감소 중에는 유지하고 실제로 닫힌 상태가 잠시 지속될 때만 해제합니다.
    val stableImeBottomPadding = rememberStableImeBottomPadding()

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
            .padding(bottom = stableImeBottomPadding)
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

                    // "결과:" 라벨을 제거해 편집창이 버튼 바로 아래에서 시작하도록 하고,
                    // 확보된 높이는 위/아래 두 편집창이 weight로 나누어 사용합니다.
                    Spacer(modifier = Modifier.height(8.dp))

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
                            .weight(1f)
                    )
                }
            }
        }
    }
}

/**
 * IME가 짧게 hide -> show 되는 포커스 전환 중에는 기존 키보드 여백을 유지합니다.
 * 실제 IME가 닫힌 상태가 120ms 이상 지속될 때만 여백을 해제해,
 * 두 weight 편집창이 순간적으로 커졌다 돌아오는 레이아웃 흔들림을 막습니다.
 */
@Composable
private fun rememberStableImeBottomPadding(): Dp {
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val navigationBottomPx = WindowInsets.navigationBars.getBottom(density)
    val effectiveImeBottomPx = max(0, imeBottomPx - navigationBottomPx)

    var stableBottomPx by remember { mutableIntStateOf(0) }

    LaunchedEffect(effectiveImeBottomPx) {
        if (effectiveImeBottomPx > 0) {
            // 키보드가 올라오거나 전환 애니메이션 중 줄어드는 동안에는
            // 가장 큰 하단 inset을 유지해 레이아웃 높이가 왕복하지 않게 합니다.
            stableBottomPx = max(stableBottomPx, effectiveImeBottomPx)
        } else {
            // 포커스가 TextField 사이에서 이동할 때 발생하는 매우 짧은 0 inset은 무시합니다.
            delay(120)
            stableBottomPx = 0
        }
    }

    return with(density) { stableBottomPx.toDp() }
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
