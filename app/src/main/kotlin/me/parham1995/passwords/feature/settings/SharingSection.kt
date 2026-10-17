package me.parham1995.passwords.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.parham1995.passwords.data.share.ShareRepository
import java.text.DateFormat
import java.util.Date

/**
 * Where one-off share links go: a static share page on GitHub Pages, its
 * repository, and a token that may write to it. The fields are filled once
 * from what is stored and written back by Save, so typing never races the
 * store. The token is write-only: once saved it is never shown again.
 */
@Composable
fun SharingSection(viewModel: SettingsViewModel) {
    val tokenSaved by viewModel.shareTokenSaved.collectAsStateWithLifecycle()
    val shares by viewModel.recentShares.collectAsStateWithLifecycle()
    val busy by viewModel.shareBusy.collectAsStateWithLifecycle()
    var baseUrl by remember { mutableStateOf("") }
    var repository by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("") }
    var prefix by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var revoking by remember { mutableStateOf<ShareRepository.Share?>(null) }
    LaunchedEffect(Unit) {
        val current = viewModel.currentSettings()
        baseUrl = current.shareBaseUrl
        repository = current.shareRepository
        branch = current.shareBranch
        prefix = current.sharePrefix
    }
    val save = { test: Boolean ->
        viewModel.saveSharing(
            baseUrl,
            repository,
            branch,
            prefix,
            token
                .takeIf {
                    it.isNotBlank()
                }?.trim()
                ?.toCharArray(),
            test,
        )
        token = ""
    }

    Text(
        "Share an entry as a one-off link to a static share page. " +
            "Create a fine-grained GitHub token limited to the page's repository, with Contents: read and write.",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedTextField(
        value = baseUrl,
        onValueChange = { baseUrl = it },
        label = { Text("Share page address") },
        placeholder = { Text("https://example.com/share/") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = repository,
        onValueChange = { repository = it },
        label = { Text("GitHub repository") },
        placeholder = { Text("owner/name") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = branch,
            onValueChange = { branch = it },
            label = { Text("Branch") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = prefix,
            onValueChange = { prefix = it },
            label = { Text("Folder") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            modifier = Modifier.weight(1f),
        )
    }
    if (tokenSaved) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Token saved", modifier = Modifier.weight(1f))
            TextButton(onClick = viewModel::removeShareToken) { Text("Remove") }
        }
    } else {
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("GitHub token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { save(false) }, enabled = !busy) { Text("Save") }
        OutlinedButton(
            onClick = { save(true) },
            enabled = !busy && (tokenSaved || token.isNotBlank()) && repository.isNotBlank(),
        ) { Text("Test") }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

    if (shares.isNotEmpty()) {
        HorizontalDivider()
        Text("Recent shares", style = MaterialTheme.typography.labelLarge)
        Text(
            "Links are shown once, when they are made; this phone does not keep their keys. " +
                "The page deletes shares after 7 days.",
            style = MaterialTheme.typography.bodySmall,
        )
        shares.forEach { share ->
            val description = "${share.name}, ${share.kind.label}, ${formatted(share.createdMillis)}"
            ListItem(
                headlineContent = { Text(share.name) },
                supportingContent = { Text("${share.kind.label} · ${formatted(share.createdMillis)}") },
                trailingContent = {
                    TextButton(
                        onClick = { revoking = share },
                        enabled = !busy,
                        modifier = Modifier.semantics { contentDescription = "Revoke $description" },
                    ) { Text("Revoke") }
                },
            )
        }
    }

    revoking?.let { share ->
        AlertDialog(
            onDismissRequest = { revoking = null },
            title = { Text("Revoke this share?") },
            text = {
                Text(
                    "Deletes the shared ${share.kind.label} of ${share.name} from the share page, " +
                        "so its link stops working. " +
                        "Anyone who already opened it may have kept a copy.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    revoking = null
                    viewModel.revokeShare(share.id)
                }) { Text("Revoke") }
            },
            dismissButton = { TextButton(onClick = { revoking = null }) { Text("Cancel") } },
        )
    }
}

private fun formatted(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
