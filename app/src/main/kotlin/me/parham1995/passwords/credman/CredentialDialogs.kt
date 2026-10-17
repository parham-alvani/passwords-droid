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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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

/**
 * Offers to keep a login an app or page just used. One the store already has
 * is an update of its password alone -- its one-time code, notes and other
 * fields stay -- and anything else is a new entry, named as the store's own
 * `gopass create` website template would name it.
 */
@Composable
internal fun SaveDialog(
    viewModel: CredentialViewModel,
    target: CredentialMatcher.Target?,
    id: String,
    password: String,
) {
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val plan by viewModel.plan.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.planSave(target, id, password) }
    var asNew by rememberSaveable { mutableStateOf(false) }
    val current = plan
    // A name typed below that turns out to exist asks again, as an update.
    LaunchedEffect(current?.update) { asNew = false }
    val update = current?.update?.takeUnless { asNew }
    when {
        current == null ->
            AlertDialog(
                onDismissRequest = viewModel::cancel,
                title = { Text("Save password") },
                text = { CircularProgressIndicator() },
                confirmButton = {},
                dismissButton = { TextButton(onClick = viewModel::cancel) { Text("Cancel") } },
            )
        update != null ->
            AlertDialog(
                onDismissRequest = viewModel::cancel,
                title = { Text("Update password") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Replace the password of $update? Its other fields, one-time code and notes stay as they are.",
                        )
                        TextButton(
                            onClick = { asNew = true },
                            enabled = !saving,
                        ) { Text("Save as a new entry instead") }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.update(update, password) }, enabled = !saving) {
                        Text(if (saving) "Updating…" else "Update")
                    }
                },
                dismissButton = { TextButton(onClick = viewModel::cancel) { Text("Cancel") } },
            )
        else -> {
            var name by rememberSaveable(current.create.name) { mutableStateOf(current.create.name) }
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
                        onClick = { viewModel.create(name, current.create.text) },
                        enabled = !saving && name.isNotBlank(),
                    ) {
                        Text(if (saving) "Saving…" else "Save")
                    }
                },
                dismissButton = { TextButton(onClick = viewModel::cancel) { Text("Cancel") } },
            )
        }
    }
}
