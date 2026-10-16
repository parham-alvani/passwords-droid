package me.parham1995.passwords.feature.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.parham1995.passwords.unlock.UnlockPrompts

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(
    name: String?,
    folder: String,
    onDone: (saved: String?) -> Unit,
    onDeleted: () -> Unit,
    viewModel: EditViewModel =
        hiltViewModel<EditViewModel, EditViewModel.Factory>(key = "edit:$name") { it.create(name, folder) },
) {
    val form by viewModel.form.collectAsState()
    val done by viewModel.done.collectAsState()
    val deleted by viewModel.deleted.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(done) { done?.let(onDone) }
    LaunchedEffect(deleted) { if (deleted) onDeleted() }
    UnlockPrompts(viewModel)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (viewModel.isNew) "New entry" else "Edit") },
                navigationIcon = {
                    IconButton(onClick = { onDone(null) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    if (!viewModel.isNew) {
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                    IconButton(onClick = viewModel::save, enabled = !form.saving && !form.loading) {
                        Icon(Icons.Default.Check, "Save")
                    }
                },
            )
        },
    ) { padding ->
        if (form.loading && form.error == null) {
            CircularProgressIndicator(Modifier.padding(padding).padding(32.dp))
            return@Scaffold
        }
        val mono = TextStyle(fontFamily = FontFamily.Monospace)
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            form.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(
                value = form.name,
                onValueChange = { v -> viewModel.edit { it.copy(name = v) } },
                label = { Text("Name") },
                supportingText = { Text("folder/entry, e.g. github.com/parham") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.password,
                onValueChange = { v -> viewModel.edit { it.copy(password = v) } },
                label = { Text("Password") },
                textStyle = mono,
                singleLine = true,
                trailingIcon = {
                    IconButton(
                        onClick = viewModel::generate,
                    ) { Icon(Icons.Default.Refresh, "Generate") }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.username,
                onValueChange = { v -> viewModel.edit { it.copy(username = v) } },
                label = { Text("Username") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.url,
                onValueChange = { v -> viewModel.edit { it.copy(url = v) } },
                label = { Text("URL") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.otp,
                onValueChange = { v -> viewModel.edit { it.copy(otp = v) } },
                label = { Text("One-time code URI") },
                placeholder = { Text("otpauth://totp/...") },
                singleLine = true,
                textStyle = mono,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            form.otherFields.forEach { field ->
                Text("${field.key}: kept as it is", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = form.notes,
                onValueChange = { v -> viewModel.edit { it.copy(notes = v) } },
                label = { Text("Notes") },
                minLines = 3,
                textStyle = mono,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${form.name}?") },
            text = { Text("It is removed from the store and the deletion is pushed. Git history still has it.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
