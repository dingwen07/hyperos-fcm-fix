package net.extrawdw.apps.miuisucks.powerkeeper

import android.view.WindowManager
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.coroutines.cancellation.CancellationException

private const val DialogTransitionMillis = 220

/** Stacked full-screen dialogs: Back closes a file, then the viewer, never the app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(
    onDismiss: () -> Unit,
    onLogsCleared: () -> Unit,
) {
    var refreshKey by remember { mutableIntStateOf(0) }
    var files by remember { mutableStateOf(emptyList<File>()) }
    var selectedFileName by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedContent by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val emptySessionText = stringResource(R.string.empty_log)

    LaunchedEffect(refreshKey) {
        files = withContext(Dispatchers.IO) { AppLog.sessionFiles() }
        if (selectedFileName != null && files.none { it.name == selectedFileName }) {
            selectedFileName = null
        }
    }

    val selectedFile = files.firstOrNull { it.name == selectedFileName }
    LaunchedEffect(selectedFileName, refreshKey, selectedFile?.length()) {
        selectedContent = selectedFile?.let { file ->
            withContext(Dispatchers.IO) { AppLog.readSessionFile(file) }
        }.orEmpty()
    }

    FullScreenLogDialog(
        onDismiss = onDismiss,
        dismissEnabled = !clearing,
    ) { requestClose ->
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.diagnostics)) },
                    navigationIcon = {
                        TextButton(
                            onClick = { requestClose(onDismiss) },
                            enabled = !clearing,
                        ) {
                            Text(stringResource(R.string.back))
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = {
                                refreshKey++
                            },
                        ) { Text(stringResource(R.string.refresh)) }
                        TextButton(
                            onClick = { confirmClear = true },
                            enabled = !clearing,
                        ) { Text(stringResource(R.string.clear)) }
                    },
                )
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = innerPadding.calculateTopPadding() + 8.dp,
                    end = 16.dp,
                    bottom = innerPadding.calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        stringResource(R.string.diagnostics_sessions_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                if (files.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.no_diagnostic_sessions),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(files, key = File::getName) { file ->
                        LogFileCard(
                            file = file,
                            onClick = {
                                selectedFileName = file.name
                            },
                        )
                    }
                }
            }
        }
    }

    selectedFile?.let { file ->
        val closeFile = {
            selectedFileName = null
        }
        FullScreenLogDialog(onDismiss = closeFile, dim = false) { requestClose ->
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(file.name) },
                        navigationIcon = {
                            TextButton(onClick = { requestClose(closeFile) }) {
                                Text(stringResource(R.string.back))
                            }
                        },
                        actions = {
                            TextButton(
                                onClick = {
                                    refreshKey++
                                },
                            ) { Text(stringResource(R.string.refresh)) }
                        },
                    )
                },
            ) { innerPadding ->
                SelectionContainer(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    Text(
                        text = selectedContent.ifEmpty { emptySessionText },
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        softWrap = false,
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { if (!clearing) confirmClear = false },
            title = { Text(stringResource(R.string.clear_diagnostics_title)) },
            text = { Text(stringResource(R.string.clear_diagnostics_description)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        clearing = true
                        scope.launch {
                            withContext(Dispatchers.IO) { AppLog.clearSessionFiles() }
                            selectedFileName = null
                            confirmClear = false
                            clearing = false
                            refreshKey++
                            onLogsCleared()
                        }
                    },
                    enabled = !clearing,
                ) {
                    Text(
                        stringResource(if (clearing) R.string.clearing else R.string.clear),
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmClear = false },
                    enabled = !clearing,
                ) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun FullScreenLogDialog(
    onDismiss: () -> Unit,
    dim: Boolean = true,
    dismissEnabled: Boolean = true,
    content: @Composable (requestClose: (() -> Unit) -> Unit) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    LaunchedEffect(Unit) { visible = true }

    val openness by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(DialogTransitionMillis, easing = FastOutSlowInEasing),
        finishedListener = { settled ->
            if (settled == 0f) {
                val action = pendingAction ?: onDismiss
                pendingAction = null
                action()
            }
        },
        label = "dialogOpenness",
    )

    fun requestClose(andThen: () -> Unit) {
        if (!dismissEnabled || !visible) return
        pendingAction = andThen
        visible = false
    }

    var gestureInProgress by remember { mutableStateOf(false) }
    var committing by remember { mutableStateOf(false) }
    var rawProgress by remember { mutableFloatStateOf(0f) }
    var swipeEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    var touchY by remember { mutableFloatStateOf(0f) }
    val gesture by animateFloatAsState(
        targetValue = if (gestureInProgress || committing) rawProgress else 0f,
        label = "predictiveBack",
    )

    Dialog(
        onDismissRequest = { if (dismissEnabled) requestClose(onDismiss) },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
        ),
    ) {
        if (!dim) DisableDialogDim()

        PredictiveBackHandler(enabled = visible && dismissEnabled) { events ->
            try {
                events.collect { event ->
                    gestureInProgress = true
                    rawProgress = event.progress
                    swipeEdge = event.swipeEdge
                    touchY = event.touchY
                }
                gestureInProgress = false
                committing = true
                requestClose(onDismiss)
            } catch (_: CancellationException) {
                gestureInProgress = false
                rawProgress = 0f
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val enterScale = lerp(0.92f, 1f, openness)
                    val gestureScale = 1f - 0.10f * gesture
                    val scale = enterScale * gestureScale

                    scaleX = scale
                    scaleY = scale
                    alpha = openness * (1f - 0.15f * gesture)

                    transformOrigin = if (gesture > 0f) {
                        val pivotY = if (size.height > 0f) {
                            (touchY / size.height).coerceIn(0f, 1f)
                        } else {
                            0.5f
                        }
                        val pivotX = if (swipeEdge == BackEventCompat.EDGE_LEFT) 1f else 0f
                        TransformOrigin(pivotX, pivotY)
                    } else {
                        TransformOrigin(0.5f, 0.5f)
                    }
                },
            color = MaterialTheme.colorScheme.background,
        ) {
            content(::requestClose)
        }
    }
}

@Composable
private fun DisableDialogDim() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect {
        window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window?.setDimAmount(0f)
    }
}

@Composable
private fun LogFileCard(file: File, onClick: () -> Unit) {
    val formattedTime = remember(file.lastModified()) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(file.lastModified()))
    }
    val sizeText = remember(file.length()) {
        val kibibytes = (file.length() + 1_023L) / 1_024L
        if (kibibytes == 0L) "${file.length()} B" else "$kibibytes KB"
    }
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(file.name, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formattedTime, style = MaterialTheme.typography.bodySmall)
                Text(sizeText, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
