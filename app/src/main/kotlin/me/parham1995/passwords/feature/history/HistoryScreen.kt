package me.parham1995.passwords.feature.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.parham1995.passwords.unlock.UnlockPrompts
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    name: String,
    onBack: () -> Unit,
    viewModel: HistoryViewModel =
        hiltViewModel<HistoryViewModel, HistoryViewModel.Factory>(key = "history:$name") { it.create(name) },
) {
    val revisions by viewModel.revisions.collectAsState()
    val viewing by viewModel.viewing.collectAsState()
    val restored by viewModel.restored.collectAsState()
    LaunchedEffect(restored) { if (restored) onBack() }
    UnlockPrompts(viewModel)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        val list = revisions
        if (list == null) {
            CircularProgressIndicator(Modifier.padding(padding).padding(32.dp))
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
            }
            items(list, key = { it.commit }) { revision ->
                ListItem(
                    headlineContent = { Text(revision.message) },
                    supportingContent = {
                        Text("${format(revision.time)} · ${revision.author} · ${revision.commit.take(SHORT_SHA)}")
                    },
                    modifier = Modifier.clickable { viewModel.view(revision) },
                )
            }
        }
    }

    viewing?.let { current ->
        var reveal by remember(current) { mutableStateOf(false) }
        var confirm by remember(current) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = viewModel::close,
            title = { Text(format(current.revision.time)) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    current.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    val entry = current.entry
                    if (entry == null && current.error == null) Text("The entry did not exist in this version.")
                    if (entry != null) {
                        Text("Password", style = MaterialTheme.typography.labelMedium)
                        Text(if (reveal) entry.password else "•".repeat(MASK), fontFamily = FontFamily.Monospace)
                        TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Hide" else "Show") }
                        entry.fields.forEach { field ->
                            Text(
                                "${field.key}: ${if (field.isSecret && !reveal) "•".repeat(MASK) else field.value}",
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        if (entry.notes.isNotBlank()) Text(entry.notes, fontFamily = FontFamily.Monospace)
                    }
                }
            },
            confirmButton = {
                if (current.entry != null) {
                    TextButton(onClick = { confirm = true }) { Text("Restore this version") }
                }
            },
            dismissButton = { TextButton(onClick = viewModel::close) { Text("Close") } },
        )
        if (confirm) {
            AlertDialog(
                onDismissRequest = { confirm = false },
                title = { Text("Restore?") },
                text = {
                    Text(
                        "$name goes back to this version as a new commit. The current version stays in history, " +
                            "so this can be undone the same way.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirm = false
                        viewModel.restore()
                    }) { Text("Restore") }
                },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
            )
        }
    }
}

private val FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun format(time: Long): String = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(FORMAT)

private const val SHORT_SHA = 8
private const val MASK = 12
