package me.parham1995.passwords.feature.health

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.parham1995.passwords.unlock.UnlockPrompts
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthScreen(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    viewModel: HealthViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    UnlockPrompts(viewModel)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Password health") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        when (val current = state) {
            HealthViewModel.State.Idle -> Start(Modifier.padding(padding), viewModel::run)
            is HealthViewModel.State.Running ->
                Column(Modifier.padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(current.phase + if (current.total > 0) " ${current.done} of ${current.total}" else "…")
                    if (current.total > 0) {
                        LinearProgressIndicator(
                            progress = { current.done.toFloat() / current.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            is HealthViewModel.State.Failed ->
                Column(Modifier.padding(padding).padding(24.dp)) {
                    Text(current.message, color = MaterialTheme.colorScheme.error)
                    Start(Modifier, viewModel::run)
                }
            is HealthViewModel.State.Done -> Results(current, Modifier.padding(padding), onOpen)
        }
    }
}

@Composable
private fun Start(
    modifier: Modifier,
    onRun: (Boolean) -> Unit,
) {
    var breaches by remember { mutableStateOf(false) }
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            "Decrypts every login once, on this phone, to find weak, reused and old passwords. " +
                "Nothing is stored afterwards; the report lists entry names only.",
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = breaches, onCheckedChange = { breaches = it })
            Column {
                Text("Also check known breaches")
                Text(
                    "Asks Have I Been Pwned about the first 5 characters of each password's SHA-1 hash. " +
                        "The password and the full hash never leave the phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Button(onClick = { onRun(breaches) }) { Text("Check passwords") }
    }
}

@Composable
private fun Results(
    done: HealthViewModel.State.Done,
    modifier: Modifier,
    onOpen: (String) -> Unit,
) {
    val report = done.report
    LazyColumn(modifier.fillMaxSize()) {
        item {
            ElevatedCard(Modifier.fillMaxWidth().padding(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("${report.checked} logins checked", style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOfNotNull(
                            done.pwned?.let { "${it.size} breached" },
                            "${report.reused.sumOf { it.size }} reused",
                            "${report.weak.size} weak",
                            "${report.old.size} older than 2 years",
                        ).joinToString(" · "),
                    )
                    done.pwnedError?.let { Text("Breach check failed: $it", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        done.pwned?.takeIf { it.isNotEmpty() }?.let { pwned ->
            item { Header("Found in breaches: change these first") }
            items(pwned.entries.sortedByDescending { it.value }.toList(), key = { "p:" + it.key }) { (name, count) ->
                Row(name, "seen $count times in breaches", onOpen)
            }
        }
        if (report.reused.isNotEmpty()) {
            item { Header("Reused") }
            report.reused.forEachIndexed { index, group ->
                items(
                    group,
                    key = { "r$index:$it" },
                ) { name -> Row(name, "same password as ${group.size - 1} other(s)", onOpen) }
            }
        }
        if (report.weak.isNotEmpty()) {
            item { Header("Weak") }
            items(report.weak, key = { "w:" + it.name }) { weak ->
                Row(weak.name, listOfNotNull("strength ${weak.score}/4", weak.hint).joinToString(" · "), onOpen)
            }
        }
        if (report.old.isNotEmpty()) {
            item { Header("Not changed in over 2 years") }
            items(report.old, key = { "o:" + it.first }) { (name, time) ->
                Row(
                    name,
                    "last changed " + Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate(),
                    onOpen,
                )
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Row(
    name: String,
    detail: String,
    onOpen: (String) -> Unit,
) {
    ListItem(
        headlineContent = { Text(name) },
        supportingContent = { Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        modifier = Modifier.clickable { onOpen(name) },
    )
}
