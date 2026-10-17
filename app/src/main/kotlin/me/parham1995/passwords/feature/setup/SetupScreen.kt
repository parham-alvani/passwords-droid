package me.parham1995.passwords.feature.setup

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.parham1995.passwords.feature.keys.BackupCodeDialog
import me.parham1995.passwords.ui.SecretClipboard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onDone: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val pickKey =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(viewModel::importKey)
        }
    LaunchedEffect(state.done) { if (state.done) onDone() }

    if (state.askBackupCode) {
        BackupCodeDialog(onSubmit = viewModel::submitBackupCode, onDismiss = viewModel::dismissBackupCode)
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Set up") }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Step("1. Your OpenPGP key") {
                Text(
                    "The secret key your store is encrypted for. Export it with " +
                        "`gpg --export-secret-keys --armor <id> | gpg --symmetric --armor` and move the file " +
                        "to the phone; the backup code keeps it safe on the way.",
                )
                state.secretKeys.forEach { Text("✓ $it", style = MaterialTheme.typography.bodyMedium) }
                OutlinedButton(onClick = { pickKey.launch(arrayOf("*/*")) }) {
                    Text(if (state.secretKeys.isEmpty()) "Import key file" else "Import another")
                }
            }

            Step("2. Repository") {
                OutlinedTextField(
                    value = state.remoteUrl,
                    onValueChange = { v -> viewModel.edit { it.copy(remoteUrl = v) } },
                    label = { Text("Git URL") },
                    placeholder = { Text("git@github.com:you/passwords.git") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.branch,
                    onValueChange = { v -> viewModel.edit { it.copy(branch = v) } },
                    label = { Text("Branch") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.authorName,
                        onValueChange = { v -> viewModel.edit { it.copy(authorName = v) } },
                        label = { Text("Commit name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = state.authorEmail,
                        onValueChange = { v -> viewModel.edit { it.copy(authorEmail = v) } },
                        label = { Text("Commit email") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Step("3. SSH deploy key") {
                Text(
                    "Generated on this phone; the private half never leaves it. Add the public line to the " +
                        "repository as a deploy key and tick \"Allow write access\" so edits can be pushed.",
                )
                val line = state.sshPublicKey
                if (line == null) {
                    Button(onClick = viewModel::generateSshKey, enabled = !state.busy) { Text("Generate key") }
                } else {
                    SelectionContainer {
                        Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { SecretClipboard.copy(context, "SSH public key", line, seconds = 0) },
                        ) {
                            Text("Copy")
                        }
                        viewModel.deployKeyPage()?.let { page ->
                            OutlinedButton(
                                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, page.toUri())) },
                            ) {
                                Text("Open GitHub")
                            }
                        }
                    }
                }
            }

            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = viewModel::finish,
                enabled =
                    !state.busy &&
                        state.secretKeys.isNotEmpty() &&
                        state.sshPublicKey != null &&
                        state.remoteUrl.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.busy) "Working…" else "Clone the store") }
        }
    }
}

@Composable
private fun Step(
    title: String,
    content: @Composable () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
