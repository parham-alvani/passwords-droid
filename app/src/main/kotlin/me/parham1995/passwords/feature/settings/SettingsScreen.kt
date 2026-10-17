package me.parham1995.passwords.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.parham1995.passwords.BuildConfig
import me.parham1995.passwords.feature.keys.BackupCodeDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onReset: () -> Unit,
    onHealth: () -> Unit,
    onRecipients: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val keys by viewModel.keys.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val askBackupCode by viewModel.askBackupCode.collectAsStateWithLifecycle()
    var confirmReset by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val pickKey =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri -> uri?.let(viewModel::importKey) }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }
    if (askBackupCode) {
        BackupCodeDialog(
            onSubmit = viewModel::submitBackupCode,
            onDismiss = viewModel::dismissBackupCode,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section("Unlocking") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Fingerprint unlock")
                        Text(
                            "The passphrase is sealed by a key that only a strong biometric can use, and is lost if a new fingerprint is enrolled.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = settings.biometricUnlock, onCheckedChange = viewModel::setBiometric)
                }
                Text(
                    "Remember a typed passphrase for ${settings.passphraseMinutes} min (forgotten when the screen locks)",
                )
                Slider(
                    value = settings.passphraseMinutes.toFloat(),
                    onValueChange = { v -> viewModel.update { it.copy(passphraseMinutes = v.toInt()) } },
                    valueRange = 0f..MAX_MINUTES,
                    steps = MAX_MINUTES.toInt() - 1,
                )
                Text("Clear the clipboard after ${settings.clipboardSeconds} s")
                Slider(
                    value = settings.clipboardSeconds.toFloat(),
                    onValueChange = { v -> viewModel.update { it.copy(clipboardSeconds = v.toInt()) } },
                    valueRange = MIN_CLIPBOARD..MAX_CLIPBOARD,
                )
                OutlinedButton(onClick = viewModel::forgetPassphrases) { Text("Forget all passphrases now") }
            }

            Section("Password health") {
                Text("Find weak, reused, old and breached passwords.")
                OutlinedButton(onClick = onHealth) { Text("Check passwords") }
            }

            Section("Recipients") {
                Text("Who the store is encrypted for (.gpg-id), and re-encrypting it for someone new.")
                OutlinedButton(onClick = onRecipients) { Text("Manage recipients") }
            }

            Section("OpenPGP keys") {
                keys.forEach { key ->
                    ListItem(
                        headlineContent = { Text(key.label) },
                        supportingContent = {
                            Text(
                                (if (key.secret) "secret · " else "public · ") +
                                    key.fingerprint.chunked(4).joinToString(" "),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        trailingContent = {
                            TextButton(
                                onClick = { viewModel.removeKey(key.fingerprint) },
                            ) { Text("Remove") }
                        },
                    )
                }
                OutlinedButton(onClick = { pickKey.launch(arrayOf("*/*")) }) { Text("Import key") }
            }

            Section("Sharing") { SharingSection(viewModel) }

            Section("Git") {
                Text(settings.remoteUrl, fontFamily = FontFamily.Monospace)
                Text("Branch ${settings.branch}")
                GitAuthor(
                    settings,
                ) { name, email -> viewModel.update { it.copy(authorName = name, authorEmail = email) } }
                HorizontalDivider()
                Text("SSH deploy key", style = MaterialTheme.typography.labelLarge)
                viewModel.sshFingerprint?.let {
                    Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                viewModel.sshPublicKey?.let {
                    SelectionContainer {
                        Text(
                            it,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            Section("Device") {
                Text(
                    if (viewModel.strongBox) {
                        "Keys are sealed in StrongBox (the phone's separate security chip)."
                    } else {
                        "Keys are sealed in the TEE; this phone's StrongBox was not used."
                    },
                )
                Text(
                    "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { confirmReset = true }) { Text("Remove everything from this phone") }
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Remove everything?") },
            text = {
                Text(
                    "Deletes the imported keys, the SSH key, the sharing token and the local copy of the store. " +
                        "Unpushed changes are lost. " +
                        "The repository on the server is not touched; remove the deploy key there yourself.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    viewModel.reset(onReset)
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(
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

private const val MAX_MINUTES = 30f
private const val MIN_CLIPBOARD = 10f
private const val MAX_CLIPBOARD = 120f
