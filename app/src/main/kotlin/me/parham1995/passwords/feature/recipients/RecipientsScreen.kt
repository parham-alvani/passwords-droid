package me.parham1995.passwords.feature.recipients

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipientsScreen(
    onBack: () -> Unit,
    viewModel: RecipientsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var folder by remember { mutableStateOf(state.folder) }
    var confirm by remember { mutableStateOf(false) }
    UnlockPrompts(viewModel)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recipients") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Who can decrypt the store, as gopass recipients and pass's .gpg-id. Changing them re-encrypts " +
                    "every entry the .gpg-id governs and commits it as one change.",
            )
            OutlinedTextField(
                value = folder,
                onValueChange = { folder = it },
                label = { Text("Folder (empty for the whole store)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { viewModel.load(folder) }) { Text("Show") }
            Text(
                "Governed by ${state.governedBy.ifEmpty { "the store's root" }}/.gpg-id · ${state.entries} entries",
                style = MaterialTheme.typography.bodySmall,
            )
            state.candidates.forEach { candidate ->
                ListItem(
                    headlineContent = { Text(candidate.label) },
                    supportingContent = {
                        Text(
                            candidate.id + (if (candidate.secret) " · this phone's key" else "") +
                                (if (candidate.id in state.current) " · current" else ""),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    leadingContent = {
                        Checkbox(
                            checked = candidate.id in state.selected,
                            onCheckedChange = { viewModel.toggle(candidate.id) },
                        )
                    },
                )
            }
            state.progress?.let { (done, total) ->
                Text("Re-encrypting $done of $total")
                LinearProgressIndicator(progress = {
                    if (total ==
                        0
                    ) {
                        0f
                    } else {
                        done.toFloat() / total
                    }
                }, modifier = Modifier.fillMaxWidth())
            }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            Button(
                onClick = { confirm = true },
                enabled = state.changed && state.progress == null,
            ) { Text("Re-encrypt") }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Re-encrypt ${state.entries} entries?") },
            text = {
                Text(
                    "Every entry under ${state.governedBy.ifEmpty { "the store" }} is decrypted and encrypted " +
                        "again for the ${state.selected.size} selected keys, then pushed. Anyone removed keeps what " +
                        "they already have in git history; change those passwords too if that matters.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    viewModel.apply()
                }) { Text("Re-encrypt") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}
