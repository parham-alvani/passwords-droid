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
import me.parham1995.passwords.data.PasswordRepository
import me.parham1995.passwords.store.Totp
import me.parham1995.passwords.store.WifiQr
import me.parham1995.passwords.ui.SecretClipboard
import me.parham1995.passwords.unlock.UnlockPrompts

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryScreen(
    name: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onOpen: (String) -> Unit,
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
                is EntryViewModel.State.Shown -> EntryDetails(current.opened, viewModel, onOpen)
            }
        }
    }
}

@Composable
private fun EntryDetails(
    opened: PasswordRepository.Opened,
    viewModel: EntryViewModel,
    onOpen: (String) -> Unit,
) {
    val entry = opened.entry
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val copy: (String, String) -> Unit = { label, value ->
        scope.launch { SecretClipboard.copy(context, label, value, viewModel.clipboardSeconds()) }
    }

    if (WifiQr.isWifi(viewModel.name, entry)) {
        var sharing by remember { mutableStateOf(false) }
        ElevatedCard(Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Wi-Fi network", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { sharing = true }) { Text("Share as QR") }
            }
        }
        if (sharing) {
            // The resolved password: a gopass reference shares the network it points at.
            WifiQrDialog(WifiQr.networkOf(viewModel.name, entry.copy(password = opened.password)), onDismiss = {
                sharing =
                    false
            })
        }
    }
    SecretRow("Password", opened.password, secret = true, onCopy = { copy("Password", opened.password) })
    opened.via.lastOrNull()?.let { target ->
        TextButton(onClick = { onOpen(target) }) {
            Text("From gopass://" + opened.via.joinToString(" → gopass://"))
        }
    }
    entry.username?.let { SecretRow("Username", it, secret = false, onCopy = { copy("Username", it) }) }
    entry.otp?.let { uri -> Totp.parse(uri)?.let { OtpRow(it, onCopy = { code -> copy("One-time code", code) }) } }
    val otpField = entry.fields.firstOrNull { entry.otpUri == null && it.key.lowercase() in OTP_KEYS }
    entry.fields
        .filterNot { it.value == entry.username || it == otpField }
        .forEach { field ->
            val target = field.reference
            if (target != null) {
                ReferenceRow(field.key, target, onOpen = { onOpen(target) })
            } else {
                SecretRow(field.key, field.value, field.isSecret, onCopy = { copy(field.key, field.value) })
            }
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
                    when {
                        revealed -> value
                        '\n' in value -> "•".repeat(MASK_LENGTH) + " (${value.lines().size} lines)"
                        else -> "•".repeat(MASK_LENGTH)
                    },
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
private fun ReferenceRow(
    label: String,
    target: String,
    onOpen: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium)
                Text("gopass://$target", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
            }
            TextButton(onClick = onOpen) { Text("Open") }
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
private val OTP_KEYS = setOf("totp", "otpauth")
private const val TICK_MILLIS = 500L
