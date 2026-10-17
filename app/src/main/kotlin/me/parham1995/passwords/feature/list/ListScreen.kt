package me.parham1995.passwords.feature.list

import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.parham1995.passwords.R
import me.parham1995.passwords.data.PasswordRepository
import me.parham1995.passwords.store.LoginMethods
import me.parham1995.passwords.store.PasswordStore
import me.parham1995.passwords.store.StoreIcons
import me.parham1995.passwords.store.StoreTree
import me.parham1995.passwords.ui.icon.LucideIcon
import me.parham1995.passwords.ui.icon.iconColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    onOpen: (String) -> Unit,
    onAdd: (folder: String) -> Unit,
    onSettings: () -> Unit,
    viewModel: ListViewModel = hiltViewModel(),
) {
    val content by viewModel.content.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val folder by viewModel.folder.collectAsStateWithLifecycle()
    val total by viewModel.total.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val icons by viewModel.icons.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val loginMethods by viewModel.loginMethods.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    var pickedFile by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    val pickFile =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val fileName = displayName(context, uri) ?: "file"
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null && bytes.size <= MAX_FILE_BYTES) pickedFile = fileName to bytes
            }
        }
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }
    // Back clears a search first, then climbs folders, then leaves.
    BackHandler(enabled = query.isNotEmpty() || folder.isNotEmpty()) {
        if (query.isNotEmpty()) viewModel.search("") else viewModel.up()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        folder.substringAfterLast('/').ifEmpty { "Passwords" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (folder.isNotEmpty()) {
                        IconButton(onClick = { viewModel.up() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up") }
                    }
                },
                actions = {
                    TextButton(onClick = { pickFile.launch(arrayOf("*/*")) }) { Text("Add file") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onAdd(folder) }) { Icon(Icons.Default.Add, "New entry") }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = syncing,
            onRefresh = viewModel::sync,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                item(key = "search") {
                    OutlinedTextField(
                        value = query,
                        onValueChange = viewModel::search,
                        placeholder = { Text("Search $total entries") },
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
                if (syncStatus.pending > 0 || syncStatus.error != null) {
                    item(key = "sync") { SyncBanner(syncStatus, syncing, onRetry = viewModel::sync) }
                }
                when (val current = content) {
                    is ListViewModel.Content.Searching -> {
                        items(current.results, key = { "s:" + it.name }) { item ->
                            EntryRow(
                                item,
                                showFolder = true,
                                loginMethod = loginMethods[item.name],
                                onClick = { onOpen(item.name) },
                            )
                        }
                        if (current.results.isEmpty()) item(key = "none") { Empty("Nothing matches") }
                    }
                    is ListViewModel.Content.Browsing -> {
                        if (folder.isNotEmpty()) item(key = "crumbs") { Breadcrumbs(folder, onOpen = viewModel::open) }
                        items(current.listing.folders, key = { "f:" + it.path }) { child ->
                            // Icons are for the top level: the contexts a store is
                            // split into. Deeper folders are mostly domains.
                            val topLevel = '/' !in child.path
                            FolderRow(
                                child,
                                icon = icons.folder(child.path),
                                onClick = { viewModel.open(child.path) },
                                onLongClick = if (topLevel) ({ picking = child.path }) else null,
                            )
                        }
                        if (current.listing.folders.isNotEmpty() && current.listing.entries.isNotEmpty()) {
                            item(key = "divider") { HorizontalDivider(Modifier.padding(horizontal = 16.dp)) }
                        }
                        items(current.listing.entries, key = { "e:" + it.name }) { item ->
                            EntryRow(
                                item,
                                showFolder = false,
                                loginMethod = loginMethods[item.name],
                                onClick = { onOpen(item.name) },
                            )
                        }
                        if (current.listing.isEmpty) {
                            item(key = "none") {
                                Empty(
                                    if (total ==
                                        0
                                    ) {
                                        "Pull down to sync the store"
                                    } else {
                                        "This folder is empty"
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    picking?.let { folder ->
        IconPicker(
            folder = folder,
            current = icons.folder(folder),
            onPick = { icon ->
                viewModel.setIcon(folder, icon)
                picking = null
            },
            onDismiss = { picking = null },
        )
    }

    pickedFile?.let { (fileName, bytes) ->
        var name by remember(fileName) { mutableStateOf(if (folder.isEmpty()) fileName else "$folder/$fileName") }
        AlertDialog(
            onDismissRequest = { pickedFile = null },
            title = { Text("Add $fileName") },
            text = {
                Column {
                    Text("${bytes.size} bytes, encrypted for this folder's recipients and committed.")
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Save as") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.addFile(name, fileName, bytes)
                    pickedFile = null
                }, enabled = name.isNotBlank()) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { pickedFile = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Breadcrumbs(
    folder: String,
    onOpen: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onOpen("") }) { Text("Store") }
        StoreTree.crumbs(folder).forEach { (name, path) ->
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { onOpen(path) }, enabled = path != folder) { Text(name) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRow(
    folder: StoreTree.Folder,
    icon: StoreIcons.Icon?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    ListItem(
        headlineContent = { Text(folder.name) },
        supportingContent = {
            Text(
                if (folder.count == 1) "1 entry" else "${folder.count} entries",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = {
            if (icon != null) {
                LucideIcon(
                    icon.name,
                    tint = iconColor(icon.color, MaterialTheme.colorScheme.primary),
                    contentDescription = icon.name,
                )
            } else {
                Icon(painterResource(R.drawable.ic_folder), null, tint = MaterialTheme.colorScheme.primary)
            }
        },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
        modifier =
            Modifier.combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = "Change icon",
            ),
    )
}

@Composable
private fun EntryRow(
    item: PasswordStore.Item,
    showFolder: Boolean,
    loginMethod: String?,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(item.leaf) },
        supportingContent = {
            val context =
                listOfNotNull(
                    item.folder.takeIf { showFolder && it.isNotEmpty() },
                    item.aliasOf?.let { "alias of $it" },
                    loginMethod?.let(LoginMethods::signIn),
                )
            if (context.isNotEmpty()) {
                Text(context.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        leadingContent = { Icon(Icons.Default.Lock, null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * Where the store stands with its remote, when that needs saying: commits
 * not pushed yet, the last push's error, or a conflict only a sync from
 * Settings can sort out. Retry runs the same sync as pulling down.
 */
@Composable
private fun SyncBanner(
    status: PasswordRepository.SyncStatus,
    syncing: Boolean,
    onRetry: () -> Unit,
) {
    val waiting =
        when (status.pending) {
            0 -> null
            1 -> "1 change waiting to push"
            else -> "${status.pending} changes waiting to push"
        }
    val text =
        when {
            status.conflict -> "Conflict — sync from Settings"
            else -> listOfNotNull(waiting, status.error).joinToString(" · ")
        }
    val color =
        if (status.error !=
            null
        ) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status.error != null) {
            Icon(Icons.Default.Warning, contentDescription = "Sync problem", tint = color)
        } else {
            Icon(Icons.Default.Info, contentDescription = "Not pushed yet", tint = color)
        }
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        )
        TextButton(onClick = onRetry, enabled = !syncing) { Text("Retry") }
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text(text) }
}

/** Bigger files do not belong in a password store, and would be slow to encrypt on every device. */
private const val MAX_FILE_BYTES = 5 * 1024 * 1024

private fun displayName(
    context: android.content.Context,
    uri: android.net.Uri,
): String? =
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
