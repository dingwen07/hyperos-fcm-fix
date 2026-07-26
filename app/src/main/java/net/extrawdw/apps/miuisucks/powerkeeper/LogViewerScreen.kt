package net.extrawdw.apps.miuisucks.powerkeeper

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Pathline-style stacked full-screen dialogs: Back closes a file, then the viewer, never the app. */
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
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Logs") },
                    navigationIcon = {
                        TextButton(onClick = onDismiss, enabled = !clearing) { Text("Back") }
                    },
                    actions = {
                        TextButton(
                            onClick = {
                                AppLog.i("UI/Logs", "list refreshed")
                                refreshKey++
                            },
                        ) { Text("Refresh") }
                        TextButton(
                            onClick = { confirmClear = true },
                            enabled = !clearing,
                        ) { Text("Clear") }
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
                        "Newest sessions first. Each file combines app, background-worker, and privileged Shizuku events. Very large files show their latest 200 KB.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                if (files.isEmpty()) {
                    item {
                        Text(
                            "No log sessions found.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(files, key = File::getName) { file ->
                        LogFileCard(
                            file = file,
                            onClick = {
                                AppLog.i("UI/Logs", "file opened name=${file.name} bytes=${file.length()}")
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
            AppLog.i("UI/Logs", "file closed name=${file.name}")
            selectedFileName = null
        }
        FullScreenLogDialog(onDismiss = closeFile) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(file.name) },
                        navigationIcon = {
                            TextButton(onClick = closeFile) { Text("Back") }
                        },
                        actions = {
                            TextButton(
                                onClick = {
                                    AppLog.i("UI/Logs", "file refreshed name=${file.name}")
                                    refreshKey++
                                },
                            ) { Text("Refresh") }
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
                        text = selectedContent.ifEmpty { "(empty log)" },
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
            title = { Text("Clear logs?") },
            text = { Text("All saved log sessions will be deleted. A new empty session will start immediately.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        clearing = true
                        scope.launch {
                            val deleted = withContext(Dispatchers.IO) { AppLog.clearSessionFiles() }
                            AppLog.i("UI/Logs", "logs cleared deleted=$deleted")
                            selectedFileName = null
                            confirmClear = false
                            clearing = false
                            refreshKey++
                            onLogsCleared()
                        }
                    },
                    enabled = !clearing,
                ) { Text(if (clearing) "Clearing…" else "Clear") }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmClear = false },
                    enabled = !clearing,
                ) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun FullScreenLogDialog(
    onDismiss: () -> Unit,
    dismissEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = { if (dismissEnabled) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            content()
        }
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
