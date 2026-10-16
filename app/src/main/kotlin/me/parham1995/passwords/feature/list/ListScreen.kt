package me.parham1995.passwords.feature.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    onOpen: (String) -> Unit,
    onAdd: (folder: String) -> Unit,
    onSettings: () -> Unit,
    viewModel: ListViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsState()
    val query by viewModel.query.collectAsState()
    val syncing by viewModel.syncing.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Passwords") },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onAdd("") }) { Icon(Icons.Default.Add, "New entry") }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = syncing,
            onRefresh = viewModel::sync,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = viewModel::search,
                        placeholder = { Text("Search ${items.size} entries") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { viewModel.search("") }) { Icon(Icons.Default.Clear, "Clear") }
                            }
                        },
                        singleLine = true,
                        keyboardOptions =
                            KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                autoCorrectEnabled = false,
                                imeAction = ImeAction.Search,
                            ),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(items, key = { it.name }) { item ->
                    ListItem(
                        headlineContent = { Text(item.leaf) },
                        supportingContent = {
                            val context =
                                listOfNotNull(item.folder.ifEmpty { null }, item.aliasOf?.let { "alias of $it" })
                            if (context.isNotEmpty()) {
                                Text(context.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        leadingContent = { Icon(Icons.Default.Lock, null) },
                        modifier = Modifier.clickable { onOpen(item.name) },
                    )
                }
                if (items.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text(if (query.isEmpty()) "Pull down to sync the store" else "Nothing matches")
                        }
                    }
                }
            }
        }
    }
}
