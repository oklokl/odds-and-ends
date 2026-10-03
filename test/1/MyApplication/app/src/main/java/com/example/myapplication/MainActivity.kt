package com.example.myapplication

import android.content.Context
import android.os.Bundle
import android.view.SoundEffectConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// To-Do 데이터 모델 (고유 ID + 텍스트 + 완료 여부)
data class TodoItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isDone: Boolean = false
)

// 원형 물결 파동(Circular Ripple Wave) 애니메이션 데이터 모델
data class WaterRipple(
    val id: Long = System.nanoTime(),
    val center: Offset,
    val progress: Animatable<Float, AnimationVector1D> = Animatable(0f)
)

// SharedPreferences 데이터 저장/불러오기를 담당하는 헬퍼 객체
object TodoRepository {
    private const val PREF_NAME = "todo_prefs"
    private const val KEY_TODO_LIST = "todo_list"

    fun loadTodoList(context: Context): List<TodoItem> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(KEY_TODO_LIST, null) ?: return emptyList()
        val list = mutableListOf<TodoItem>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.optJSONObject(i)
                if (item != null) {
                    val id = item.optString("id", UUID.randomUUID().toString())
                    val text = item.getString("text")
                    val isDone = item.optBoolean("isDone", false)
                    list.add(TodoItem(id, text, isDone))
                } else {
                    val text = jsonArray.getString(i)
                    list.add(TodoItem(text = text, isDone = false))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun saveTodoList(context: Context, list: List<TodoItem>) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val jsonArray = JSONArray()
        list.forEach { item ->
            val jsonObject = JSONObject().apply {
                put("id", item.id)
                put("text", item.text)
                put("isDone", item.isDone)
            }
            jsonArray.put(jsonObject)
        }
        prefs.edit {
            putString(KEY_TODO_LIST, jsonArray.toString())
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    TodoListScreen(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun TodoListScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = LocalView.current // 효과음 재생을 위한 View 참조
    val coroutineScope = rememberCoroutineScope()
    var inputText by remember { mutableStateOf("") }

    // 연못 물결 파동 애니메이션 리스트 관리
    val waterRipples = remember { mutableStateListOf<WaterRipple>() }

    val todoList = remember {
        mutableStateListOf<TodoItem>().apply {
            addAll(TodoRepository.loadTodoList(context))
        }
    }

    // 🌊 터치 위치에서 물결 파동 생성 및 애니메이션 실행
    fun spawnWaterRipple(center: Offset) {
        val ripple = WaterRipple(center = center)
        waterRipples.add(ripple)
        coroutineScope.launch {
            ripple.progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 800, easing = LinearOutSlowInEasing)
            )
            waterRipples.remove(ripple)
        }
    }

    // ⚡ 최적화: derivedStateOf를 통해 상태 변경 시 불필요한 전체 Recomposition 방지
    val selectedCount by remember {
        derivedStateOf { todoList.count { it.isDone } }
    }
    val isAllChecked by remember {
        derivedStateOf { todoList.isNotEmpty() && todoList.all { it.isDone } }
    }
    val hasSelected by remember {
        derivedStateOf { todoList.any { it.isDone } }
    }

    // 전체 선택 / 전체 해제 처리 및 저장
    fun toggleAllDone() {
        view.playSoundEffect(SoundEffectConstants.CLICK)
        val targetState = !isAllChecked
        for (i in todoList.indices) {
            todoList[i] = todoList[i].copy(isDone = targetState)
        }
        TodoRepository.saveTodoList(context, todoList)
    }

    // 선택된 항목만 삭제 처리 및 저장
    fun deleteSelected() {
        if (hasSelected) {
            view.playSoundEffect(SoundEffectConstants.CLICK)
            todoList.removeAll { it.isDone }
            TodoRepository.saveTodoList(context, todoList)
        }
    }

    // 전체 삭제 처리 및 저장
    fun deleteAll() {
        if (todoList.isNotEmpty()) {
            view.playSoundEffect(SoundEffectConstants.CLICK)
            todoList.clear()
            TodoRepository.saveTodoList(context, todoList)
        }
    }

    // 할 일 추가 및 클릭 효과음 재생
    fun addTodo() {
        if (inputText.isNotBlank()) {
            view.playSoundEffect(SoundEffectConstants.CLICK)
            todoList.add(TodoItem(text = inputText.trim(), isDone = false))
            TodoRepository.saveTodoList(context, todoList)
            inputText = ""
        }
    }

    // 완료 여부 토글 및 클릭 효과음 재생
    fun toggleTodoDone(item: TodoItem) {
        val index = todoList.indexOfFirst { it.id == item.id }
        if (index != -1) {
            view.playSoundEffect(SoundEffectConstants.CLICK)
            todoList[index] = todoList[index].copy(isDone = !todoList[index].isDone)
            TodoRepository.saveTodoList(context, todoList)
        }
    }

    // 할 일 단일 삭제 및 클릭 효과음 재생
    fun deleteTodo(item: TodoItem) {
        view.playSoundEffect(SoundEffectConstants.CLICK)
        todoList.removeAll { it.id == item.id }
        TodoRepository.saveTodoList(context, todoList)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // 🌊 터치 지점 감지: 버튼, 리스트, 배경 등 화면 어디든 터치 시 위치(x, y)에 물결 파동을 발생시킴
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press) {
                            val position = event.changes.first().position
                            spawnWaterRipple(position)
                        }
                    }
                }
            }
    ) {
        // 배경 이미지 설정
        Image(
            painter = painterResource(id = R.drawable.bg_library),
            contentDescription = "배경 이미지",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // 가독성을 위한 반투명 흰색 오버레이
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White.copy(alpha = 0.35f))
        )

        // 🌊 원형 물결 파동(Circular Ripple Wave) 캔버스 레이어
        WaterRippleOverlay(ripples = waterRipples)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                text = "To-Do 리스트",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = Color(0xFF2C1D11),
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    label = { Text("할 일을 입력하세요", color = Color(0xFF4A3425)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White.copy(alpha = 0.85f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.75f),
                        focusedBorderColor = Color(0xFF7B5233),
                        unfocusedBorderColor = Color(0xFFA07855)
                    ),
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = { addTodo() },
                    modifier = Modifier.height(56.dp)
                ) {
                    Text("추가")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (todoList.isEmpty()) {
                Text(
                    text = "등록된 할 일이 없습니다.",
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = Color(0xFF3E2723),
                    modifier = Modifier.padding(top = 16.dp)
                )
            } else {
                // 상단 컨트롤 영역: 전체 선택 + 선택 삭제 + 전체 삭제
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // 좌측: 전체 선택 체크박스
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isAllChecked,
                            onCheckedChange = { toggleAllDone() }
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = if (isAllChecked) "전체 해제" else "전체 선택 ($selectedCount/${todoList.size})",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = Color(0xFF2C1D11)
                        )
                    }

                    // 우측: [선택 삭제] [전체 삭제] 버튼 모음
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { deleteSelected() },
                            enabled = hasSelected
                        ) {
                            Text("선택 삭제")
                        }

                        OutlinedButton(
                            onClick = { deleteAll() }
                        ) {
                            Text("전체 삭제")
                        }
                    }
                }

                // ⚡ 최적화: key 지정을 통해 리스트 항목 변경/삭제 시 불필요한 항목 전체 재그리기 방지
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = todoList,
                        key = { it.id }
                    ) { item ->
                        TodoItemRow(
                            todoItem = item,
                            onToggleDone = { toggleTodoDone(item) },
                            onDelete = { deleteTodo(item) }
                        )
                    }
                }
            }
        }
    }
}

// 🌊 원형 물결 파동(Circular Ripple Wave)을 그리는 커스텀 캔버스 컴포넌트
@Composable
fun WaterRippleOverlay(
    ripples: List<WaterRipple>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val maxRadius = size.maxDimension * 0.45f // 화면 크기에 비례하여 크게 확산

        ripples.forEach { ripple ->
            val p = ripple.progress.value
            val currentRadius = p * maxRadius
            val alpha = (1f - p).coerceIn(0f, 1f)

            if (alpha > 0f) {
                // 1. 메인 외곽 파동 고리 (Circular Ripple Ring)
                drawCircle(
                    color = Color(0xFF7B5233).copy(alpha = alpha * 0.5f),
                    radius = currentRadius,
                    center = ripple.center,
                    style = Stroke(width = (5.dp.toPx() * (1f - p * 0.4f)).coerceAtLeast(1f))
                )

                // 2. 보조 내측 밝은 흰색 파동 고리 (Secondary Water Ring)
                if (p > 0.1f) {
                    val innerP = (p - 0.1f) / 0.9f
                    val innerRadius = currentRadius * 0.72f
                    val innerAlpha = (1f - innerP).coerceIn(0f, 1f)
                    drawCircle(
                        color = Color.White.copy(alpha = innerAlpha * 0.6f),
                        radius = innerRadius,
                        center = ripple.center,
                        style = Stroke(width = (3.dp.toPx() * (1f - innerP * 0.4f)).coerceAtLeast(1f))
                    )
                }

                // 3. 중심 은은한 잔물결 확산 (Center Glow Effect)
                drawCircle(
                    color = Color(0xFFA07855).copy(alpha = alpha * 0.25f),
                    radius = currentRadius * 0.35f,
                    center = ripple.center
                )
            }
        }
    }
}

@Composable
fun TodoItemRow(
    todoItem: TodoItem,
    onToggleDone: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onToggleDone,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.88f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = todoItem.isDone,
                onCheckedChange = { onToggleDone() }
            )

            Spacer(modifier = Modifier.width(4.dp))

            Text(
                text = todoItem.text,
                style = MaterialTheme.typography.bodyLarge.copy(
                    textDecoration = if (todoItem.isDone) TextDecoration.LineThrough else TextDecoration.None,
                    fontWeight = FontWeight.Medium
                ),
                color = if (todoItem.isDone) {
                    Color.Gray
                } else {
                    Color(0xFF2B1B10)
                },
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(8.dp))

            OutlinedButton(
                onClick = onDelete
            ) {
                Text("삭제")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun TodoListScreenPreview() {
    MyApplicationTheme {
        TodoListScreen()
    }
}
