package com.krdondon.Replacestring

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.krdondon.Replacestring.ui.theme.ReplacestringTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var ageSignalsCompliance: AgeSignalsCompliance
    private val viewModel: WordReplacerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Request Play Age Signals at runtime. The result stays in memory only and
        // defaults to UNKNOWN whenever sharing is unavailable or an error occurs.
        ageSignalsCompliance = AgeSignalsCompliance(applicationContext)
        if (savedInstanceState == null) {
            ageSignalsCompliance.refresh(this)
        }

        setContent {
            ReplacestringTheme {
                WordReplacerApp(viewModel = viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // If verification was required in Play Store, re-check when the user returns to the app.
        if (ageSignalsCompliance.state.value.accessStatus == AgeAccessStatus.VERIFICATION_REQUIRED) {
            ageSignalsCompliance.refresh(this)
        }
    }

    override fun onDestroy() {
        ageSignalsCompliance.cancel()
        super.onDestroy()
    }
}

@Composable
fun WordReplacerApp(viewModel: WordReplacerViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    val coroutineScope = rememberCoroutineScope()

    fun showMessage(message: String) {
        coroutineScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short
            )
        }
    }

    LaunchedEffect(viewModel, resources) {
        viewModel.events.collect { event ->
            snackbarHostState.currentSnackbarData?.dismiss()
            when (event) {
                WordReplacerEvent.EmptyTargetWord -> {
                    snackbarHostState.showSnackbar(
                        message = resources.getString(R.string.msg_enter_target_word),
                        duration = SnackbarDuration.Short
                    )
                }

                WordReplacerEvent.EmptyOriginalText -> {
                    snackbarHostState.showSnackbar(
                        message = resources.getString(R.string.msg_enter_original_text),
                        duration = SnackbarDuration.Short
                    )
                }

                is WordReplacerEvent.Replaced -> {
                    val message = if (event.matchCount > 0) {
                        resources.getString(R.string.msg_replaced_count, event.matchCount)
                    } else {
                        resources.getString(R.string.msg_no_matches_found)
                    }
                    snackbarHostState.showSnackbar(
                        message = message,
                        duration = SnackbarDuration.Short
                    )
                }

                WordReplacerEvent.ClearedAll -> {
                    val result = snackbarHostState.showSnackbar(
                        message = resources.getString(R.string.msg_cleared_all),
                        actionLabel = resources.getString(R.string.action_undo),
                        duration = SnackbarDuration.Short
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        viewModel.undoClear()
                    }
                }
            }
        }
    }

    WordReplacerScreen(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onTargetWordChange = viewModel::onTargetWordChange,
        onReplacementChange = viewModel::onReplacementChange,
        onOriginalTextChange = viewModel::onOriginalTextChange,
        onReplacedTextChange = viewModel::onReplacedTextChange,
        onExpandEditorChange = viewModel::setExpandedEditor,
        onReplaceClick = viewModel::replaceWords,
        onClearAllClick = viewModel::clearAll,
        onCopyClick = {
            val textToCopy = uiState.replacedText
            if (textToCopy.isEmpty()) {
                showMessage(resources.getString(R.string.msg_nothing_to_copy))
            } else {
                val clipboardManager = context.getSystemService(
                    Context.CLIPBOARD_SERVICE
                ) as? ClipboardManager
                if (clipboardManager != null) {
                    val clipData = ClipData.newPlainText("Replaced Text", textToCopy)
                    clipboardManager.setPrimaryClip(clipData)
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        showMessage(resources.getString(R.string.msg_copied_to_clipboard))
                    }
                } else {
                    showMessage(resources.getString(R.string.msg_clipboard_unavailable))
                }
            }
        }
    )
}

@Composable
fun WordReplacerScreen(
    uiState: WordReplacerUiState,
    snackbarHostState: SnackbarHostState,
    onTargetWordChange: (String) -> Unit,
    onReplacementChange: (String) -> Unit,
    onOriginalTextChange: (String) -> Unit,
    onReplacedTextChange: (String) -> Unit,
    onExpandEditorChange: (ExpandedEditor?) -> Unit,
    onReplaceClick: () -> Unit,
    onClearAllClick: () -> Unit,
    onCopyClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var originalHasFocus by remember { mutableStateOf(false) }
    var resultHasFocus by remember { mutableStateOf(false) }
    var focusToRestore by remember { mutableStateOf<ExpandedEditor?>(null) }

    val replacementFocusRequester = remember { FocusRequester() }
    val originalFocusRequester = remember { FocusRequester() }
    val resultFocusRequester = remember { FocusRequester() }

    LaunchedEffect(uiState.expandedEditor) {
        when (focusToRestore) {
            ExpandedEditor.ORIGINAL -> originalFocusRequester.requestFocus()
            ExpandedEditor.RESULT -> resultFocusRequester.requestFocus()
            null -> Unit
        }
        focusToRestore = null
    }

    BackHandler(enabled = uiState.expandedEditor != null) {
        focusToRestore = when (uiState.expandedEditor) {
            ExpandedEditor.ORIGINAL -> if (originalHasFocus) ExpandedEditor.ORIGINAL else null
            ExpandedEditor.RESULT -> if (resultHasFocus) ExpandedEditor.RESULT else null
            null -> null
        }
        onExpandEditorChange(null)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            when (uiState.expandedEditor) {
                ExpandedEditor.ORIGINAL -> {
                    ExpandableTextEditor(
                        value = uiState.originalText,
                        onValueChange = onOriginalTextChange,
                        label = stringResource(R.string.label_original_text),
                        expanded = true,
                        expandContentDescription = stringResource(R.string.cd_collapse_original),
                        onExpandToggle = {
                            focusToRestore = if (originalHasFocus) ExpandedEditor.ORIGINAL else null
                            onExpandEditorChange(null)
                        },
                        focusRequester = originalFocusRequester,
                        onFocusChanged = { originalHasFocus = it },
                        modifier = Modifier.fillMaxSize(),
                        trailingAction = {
                            CleanButton(
                                enabled = uiState.hasAnyInput,
                                onClick = onClearAllClick
                            )
                        }
                    )
                }

                ExpandedEditor.RESULT -> {
                    ExpandableTextEditor(
                        value = uiState.replacedText,
                        onValueChange = onReplacedTextChange,
                        label = stringResource(R.string.label_result_text),
                        expanded = true,
                        expandContentDescription = stringResource(R.string.cd_collapse_result),
                        onExpandToggle = {
                            focusToRestore = if (resultHasFocus) ExpandedEditor.RESULT else null
                            onExpandEditorChange(null)
                        },
                        focusRequester = resultFocusRequester,
                        onFocusChanged = { resultHasFocus = it },
                        modifier = Modifier.fillMaxSize(),
                        trailingAction = {
                            FilledTonalButton(
                                onClick = onCopyClick,
                                enabled = uiState.replacedText.isNotEmpty()
                            ) {
                                Text(stringResource(R.string.action_copy_result))
                            }
                        }
                    )
                }

                null -> {
                    BoxWithConstraints(
                        modifier = Modifier
                            .widthIn(max = 488.dp)
                            .fillMaxSize()
                    ) {
                        val useScrollableFallback = maxHeight < 420.dp
                        val columnModifier = if (useScrollableFallback) {
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                        } else {
                            Modifier.fillMaxSize()
                        }

                        Column(modifier = columnModifier) {
                            val editorModifier = if (useScrollableFallback) {
                                Modifier
                                    .fillMaxWidth()
                                    .height(160.dp)
                            } else {
                                Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            }

                            SingleLineInputField(
                                value = uiState.targetWord,
                                onValueChange = onTargetWordChange,
                                label = stringResource(R.string.label_target_word),
                                clearDescription = stringResource(R.string.cd_clear_target_word),
                                onNext = { replacementFocusRequester.requestFocus() },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            SingleLineInputField(
                                value = uiState.replacement,
                                onValueChange = onReplacementChange,
                                label = stringResource(R.string.label_replacement_word),
                                clearDescription = stringResource(R.string.cd_clear_replacement_word),
                                onNext = { originalFocusRequester.requestFocus() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(replacementFocusRequester)
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            ExpandableTextEditor(
                                value = uiState.originalText,
                                onValueChange = onOriginalTextChange,
                                label = stringResource(R.string.label_original_text),
                                expanded = false,
                                expandContentDescription = stringResource(R.string.cd_expand_original),
                                onExpandToggle = {
                                    focusToRestore = if (originalHasFocus) ExpandedEditor.ORIGINAL else null
                                    onExpandEditorChange(ExpandedEditor.ORIGINAL)
                                },
                                focusRequester = originalFocusRequester,
                                onFocusChanged = { originalHasFocus = it },
                                modifier = editorModifier,
                                trailingAction = {
                                    CleanButton(
                                        enabled = uiState.hasAnyInput,
                                        onClick = onClearAllClick
                                    )
                                }
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = onReplaceClick,
                                    enabled = !uiState.isReplacing,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    if (uiState.isReplacing) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    Text(stringResource(R.string.action_replace))
                                }

                                Button(
                                    onClick = onCopyClick,
                                    enabled = uiState.replacedText.isNotEmpty(),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.action_copy_result))
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            ExpandableTextEditor(
                                value = uiState.replacedText,
                                onValueChange = onReplacedTextChange,
                                label = stringResource(R.string.label_result_text),
                                expanded = false,
                                expandContentDescription = stringResource(R.string.cd_expand_result),
                                onExpandToggle = {
                                    focusToRestore = if (resultHasFocus) ExpandedEditor.RESULT else null
                                    onExpandEditorChange(ExpandedEditor.RESULT)
                                },
                                focusRequester = resultFocusRequester,
                                onFocusChanged = { resultHasFocus = it },
                                modifier = editorModifier
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SingleLineInputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    clearDescription: String,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val iconColor = MaterialTheme.colorScheme.onSurfaceVariant
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { onNext() }),
        trailingIcon = if (value.isNotEmpty()) {
            {
                IconButton(
                    onClick = { onValueChange("") },
                    modifier = Modifier.semantics { contentDescription = clearDescription }
                ) {
                    Canvas(modifier = Modifier.size(16.dp)) {
                        val strokeWidth = 1.8.dp.toPx()
                        drawLine(
                            color = iconColor,
                            start = Offset.Zero,
                            end = Offset(size.width, size.height),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round
                        )
                        drawLine(
                            color = iconColor,
                            start = Offset(size.width, 0f),
                            end = Offset(0f, size.height),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round
                        )
                    }
                }
            }
        } else {
            null
        }
    )
}

@Composable
private fun CleanButton(
    enabled: Boolean,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Text(stringResource(R.string.action_clean))
    }
}

@Composable
private fun ExpandableTextEditor(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    expanded: Boolean,
    expandContentDescription: String,
    onExpandToggle: () -> Unit,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    trailingAction: (@Composable () -> Unit)? = null
) {
    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .focusRequester(focusRequester)
                .onFocusChanged { onFocusChanged(it.isFocused) }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onExpandToggle,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.semantics {
                    contentDescription = expandContentDescription
                }
            ) {
                Text(
                    text = stringResource(
                        if (expanded) R.string.action_collapse else R.string.action_expand
                    )
                )
            }

            if (trailingAction != null) {
                trailingAction()
            }
        }
    }
}

@Preview(name = "Word Replacer (Light)", showSystemUi = true)
@Composable
private fun WordReplacerLightPreview() {
    ReplacestringTheme(darkTheme = false, dynamicColor = false) {
        WordReplacerScreen(
            uiState = WordReplacerUiState(
                targetWord = "사과",
                replacement = "배",
                originalText = "사과가 맛있습니다. 사과를 먹었습니다.",
                replacedText = "배가 맛있습니다. 배를 먹었습니다."
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onTargetWordChange = {},
            onReplacementChange = {},
            onOriginalTextChange = {},
            onReplacedTextChange = {},
            onExpandEditorChange = {},
            onReplaceClick = {},
            onClearAllClick = {},
            onCopyClick = {}
        )
    }
}

@Preview(name = "Word Replacer (Dark)", showSystemUi = true)
@Composable
private fun WordReplacerDarkPreview() {
    ReplacestringTheme(darkTheme = true, dynamicColor = false) {
        WordReplacerScreen(
            uiState = WordReplacerUiState(
                targetWord = "사과",
                replacement = "배",
                originalText = "사과가 맛있습니다.",
                replacedText = "배가 맛있습니다."
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onTargetWordChange = {},
            onReplacementChange = {},
            onOriginalTextChange = {},
            onReplacedTextChange = {},
            onExpandEditorChange = {},
            onReplaceClick = {},
            onClearAllClick = {},
            onCopyClick = {}
        )
    }
}
