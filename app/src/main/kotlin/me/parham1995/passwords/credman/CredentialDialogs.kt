package me.parham1995.passwords.credman

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.parham1995.passwords.store.CredentialMatcher

/*
 * The dialogs both ways into the store share: Android's sign-in sheet
 * (CredentialActivity) and classic autofill (AutofillActivity).
 */

@Composable
internal fun SearchDialog(viewModel: CredentialViewModel) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    var picked by remember { mutableStateOf(false) }
    if (picked) return
    AlertDialog(
        onDismissRequest = viewModel::cancel,
        icon = { Icon(Icons.Default.Search, null) },
        title = { Text("Choose a password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::search,
                    placeholder = { Text("Search") },
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(results, key = { it.name }) { item ->
                        ListItem(
                            headlineContent = { Text(item.leaf) },
                            supportingContent = {
                                Text(
                                    item.folder,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            leadingContent = { Icon(Icons.Default.Lock, null) },
                            modifier =
                                Modifier.clickable {
                                    picked = true
                                    viewModel.fill(item.name)
                                },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = viewModel::cancel) { Text("Cancel") } },
    )
}

@Composable
internal fun SaveDialog(
    viewModel: CredentialViewModel,
    target: CredentialMatcher.Target?,
    id: String,
    password: String,
) {
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf(viewModel.suggestName(target, id)) }
    val url = (target as? CredentialMatcher.Target.Web)?.host?.let { if ("://" in it) it else "https://$it" }
    AlertDialog(
        onDismissRequest = viewModel::cancel,
        title = { Text("Save password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("For ${id.ifBlank { "this sign-in" }}")
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Save as") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { viewModel.save(name, id, password, url) },
                enabled =
                    !saving && name.isNotBlank(),
            ) {
                Text(if (saving) "Saving…" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = viewModel::cancel) { Text("Cancel") } },
    )
}
