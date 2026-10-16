package me.parham1995.passwords.feature.entry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.parham1995.passwords.store.PassEntry
import me.parham1995.passwords.store.Totp
import me.parham1995.passwords.ui.SecretClipboard
import me.parham1995.passwords.unlock.UnlockPrompts

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryScreen(
    name: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    viewModel: EntryViewModel =
        hiltViewModel<EntryViewModel, EntryViewModel.Factory>(key = name) { it.create(name) },
) {
    val state by viewModel.state.collectAsState()
    UnlockPrompts(viewModel)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name.substringAfterLast('/')) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    if (state is EntryViewModel.State.Shown) {
                        IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (val current = state) {
                EntryViewModel.State.Locked ->
                    Box(Modifier.fillMaxWidth()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
                is EntryViewModel.State.Failed -> {
                    Text(current.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = viewModel::retry) { Text("Try again") }
                }
                is EntryViewModel.State.Shown -> EntryDetails(current.entry, viewModel)
            }
        }
    }
}

@Composable
private fun EntryDetails(
    entry: PassEntry,
    viewModel: EntryViewModel,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val copy: (String, String) -> Unit = { label, value ->
        scope.launch { SecretClipboard.copy(context, label, value, viewModel.clipboardSeconds()) }
    }

    SecretRow("Password", entry.password, secret = true, onCopy = { copy("Password", entry.password) })
    entry.username?.let { SecretRow("Username", it, secret = false, onCopy = { copy("Username", it) }) }
    entry.otpUri?.let { uri -> Totp.parse(uri)?.let { OtpRow(it, onCopy = { code -> copy("One-time code", code) }) } }
    entry.fields
        .filterNot { it.value == entry.username }
        .forEach { field ->
            SecretRow(field.key, field.value, field.isSecret, onCopy = { copy(field.key, field.value) })
        }
    if (entry.notes.isNotBlank()) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Notes", style = MaterialTheme.typography.labelMedium)
                SelectionContainer { Text(entry.notes, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}

@Composable
private fun SecretRow(
    label: String,
    value: String,
    secret: Boolean,
    onCopy: () -> Unit,
) {
    var revealed by remember { mutableStateOf(!secret) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium)
                Text(
                    if (revealed) value else "•".repeat(MASK_LENGTH),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (secret) TextButton(onClick = { revealed = !revealed }) { Text(if (revealed) "Hide" else "Show") }
            TextButton(onClick = onCopy) { Text("Copy") }
        }
    }
}

@Composable
private fun OtpRow(
    totp: Totp,
    onCopy: (String) -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(totp) {
        while (true) {
            now = System.currentTimeMillis()
            delay(TICK_MILLIS)
        }
    }
    val code = totp.code(now)
    val remaining = totp.secondsRemaining(now)
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        listOfNotNull("One-time code", totp.issuer).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        code.chunked(code.length / 2).joinToString(" "),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
                Text("${remaining}s", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { onCopy(code) }) { Text("Copy") }
            }
            LinearProgressIndicator(
                progress = { remaining.toFloat() / totp.periodSeconds },
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp, top = 4.dp),
            )
        }
    }
}

private const val MASK_LENGTH = 12
private const val TICK_MILLIS = 500L
