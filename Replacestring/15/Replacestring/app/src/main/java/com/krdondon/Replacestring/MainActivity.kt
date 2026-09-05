package com.krdondon.Replacestring

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.imePadding


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

@Composable
fun WordReplacerApp() {
    var targetWord by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var originalText by remember { mutableStateOf("") }
    var replacedText by remember { mutableStateOf("") }

    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()   // 상태바 + 네비게이션 바 만큼 안전 영역 확보
            .imePadding()          // 키보드 올라올 때도 내용이 가려지지 않게
            .padding(16.dp)        // 기존 여백
    ) {
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

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            OutlinedTextField(
                value = originalText,
                onValueChange = { originalText = it },
                label = { Text("여기에 전체 텍스트를 입력하세요...") },
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            )

            Button(
                onClick = {
                    targetWord = ""
                    replacement = ""
                    originalText = ""
                    replacedText = ""
                    Toast.makeText(context, "모든 입력값이 지워졌습니다.", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
            ) {
                Text("Clean")
            }
        }

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
                        Toast.makeText(context, "대체할 단어를 입력해 주세요.", Toast.LENGTH_SHORT).show()
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
                        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clipData = ClipData.newPlainText("Replaced Text", replacedText)
                        clipboardManager.setPrimaryClip(clipData)

                        Toast.makeText(context, "결과가 클립보드에 복사되었습니다.", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "복사할 내용이 없습니다.", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = replacedText.isNotEmpty()
            ) {
                Text("결과 복사")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 조건 없이 항상 결과 영역 표시
        Text("결과:", fontSize = 18.sp, style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = replacedText,
            onValueChange = { replacedText = it },
            label = { Text("수정 가능한 결과") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .verticalScroll(rememberScrollState())
                .weight(1f),
        )
    }
}