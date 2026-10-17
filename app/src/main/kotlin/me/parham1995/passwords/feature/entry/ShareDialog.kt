package me.parham1995.passwords.feature.entry

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.parham1995.passwords.data.share.ShareRepository
import me.parham1995.passwords.ui.SecretClipboard

/**
 * Makes a one-off share link: pick what to share, push it, then copy or
 * send the link. The link holds its key, so it is shown here once and
 * dropped with the dialog.
 */
@Composable
fun ShareDialog(
    viewModel: EntryViewModel,
    offerPassword: Boolean,
    onDismiss: () -> Unit,
) {
    val sharing by viewModel.sharing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var kind by remember {
        mutableStateOf(if (offerPassword) ShareRepository.Kind.PASSWORD else ShareRepository.Kind.ENTRY)
    }
    // Not while uploading: the link that comes back would have nowhere to show.
    val close = {
        if (viewModel.sharing.value != EntryViewModel.Sharing.Uploading) {
            viewModel.dismissSharing()
            onDismiss()
        }
    }

    when (val current = sharing) {
        is EntryViewModel.Sharing.Made ->
            AlertDialog(
                onDismissRequest = close,
                title = { Text("Share link") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            current.link,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text("Live in about a minute, once the page has deployed.")
                        Text(
                            "This link cannot be shown again; copy or send it now. Revoke it from Settings → Sharing.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val send =
                            Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, current.link)
                        context.startActivity(Intent.createChooser(send, "Share link"))
                    }) { Text("Share") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            scope.launch {
                                SecretClipboard.copy(context, "Share link", current.link, viewModel.clipboardSeconds())
                            }
                        }) { Text("Copy") }
                        TextButton(onClick = close) { Text("Done") }
                    }
                },
            )
        else ->
            AlertDialog(
                onDismissRequest = close,
                title = { Text("Share as a link") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.selectableGroup()) {
                            if (offerPassword) {
                                KindOption("Password only", ShareRepository.Kind.PASSWORD, kind) {
                                    kind =
                                        it
                                }
                            }
                            KindOption("Whole entry", ShareRepository.Kind.ENTRY, kind) { kind = it }
                        }
                        Text(
                            "Anyone with the link can read it until it expires (7 days) or you revoke it.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        when (current) {
                            EntryViewModel.Sharing.Uploading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                            is EntryViewModel.Sharing.Failed ->
                                Text(
                                    current.message,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            else -> {}
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { viewModel.share(kind) },
                        enabled = current != EntryViewModel.Sharing.Uploading,
                    ) { Text(if (current is EntryViewModel.Sharing.Failed) "Try again" else "Create link") }
                },
                dismissButton = {
                    TextButton(
                        onClick = close,
                        enabled = current != EntryViewModel.Sharing.Uploading,
                    ) { Text("Cancel") }
                },
            )
    }
}

@Composable
private fun KindOption(
    label: String,
    value: ShareRepository.Kind,
    selected: ShareRepository.Kind,
    onSelect: (ShareRepository.Kind) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = value == selected, onClick = { onSelect(value) }, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = value == selected, onClick = null)
        Text(label)
    }
}
