package me.parham1995.passwords.credman

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CreatePasswordResponse
import androidx.credentials.GetCredentialResponse
import androidx.credentials.PasswordCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.PendingIntentHandler
import dagger.hilt.android.AndroidEntryPoint
import me.parham1995.passwords.store.CredentialMatcher
import me.parham1995.passwords.ui.theme.PasswordsTheme
import me.parham1995.passwords.unlock.UnlockPrompts

/**
 * What the system opens when an entry in its sign-in sheet is chosen: unlock,
 * decrypt that one entry, hand back its username and password, and close.
 * Also the "Search Passwords" action, and saving a login an app offers.
 */
@AndroidEntryPoint
class CredentialActivity : ComponentActivity() {
    private val viewModel: CredentialViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val mode = intent.getStringExtra(EXTRA_MODE)
        val createRequest =
            if (mode ==
                MODE_CREATE
            ) {
                PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)
            } else {
                null
            }
        val target = (createRequest?.callingAppInfo)?.let { Callers(this).targetOf(it) }

        setContent {
            PasswordsTheme {
                val outcome by viewModel.outcome.collectAsState()
                LaunchedEffect(outcome) { outcome?.let(::finishWith) }
                UnlockPrompts(viewModel)
                when (mode) {
                    MODE_FILL -> LaunchedEffect(Unit) { intent.getStringExtra(EXTRA_NAME)?.let(viewModel::fill) }
                    MODE_SEARCH -> SearchDialog(viewModel)
                    MODE_CREATE -> {
                        val request = createRequest?.callingRequest as? CreatePasswordRequest
                        if (request == null) {
                            LaunchedEffect(Unit) { viewModel.cancel() }
                        } else {
                            SaveDialog(viewModel, target, request)
                        }
                    }
                    else -> LaunchedEffect(Unit) { viewModel.cancel() }
                }
            }
        }
    }

    private fun finishWith(outcome: CredentialViewModel.Outcome) {
        val result = Intent()
        val creating = intent.getStringExtra(EXTRA_MODE) == MODE_CREATE
        when (outcome) {
            is CredentialViewModel.Outcome.Filled ->
                PendingIntentHandler.setGetCredentialResponse(
                    result,
                    GetCredentialResponse(PasswordCredential(outcome.username, outcome.password)),
                )
            CredentialViewModel.Outcome.Saved ->
                PendingIntentHandler.setCreateCredentialResponse(
                    result,
                    CreatePasswordResponse(),
                )
            is CredentialViewModel.Outcome.Failed ->
                if (creating) {
                    PendingIntentHandler.setCreateCredentialException(
                        result,
                        CreateCredentialUnknownException(outcome.message),
                    )
                } else {
                    PendingIntentHandler.setGetCredentialException(
                        result,
                        GetCredentialUnknownException(outcome.message),
                    )
                }
            CredentialViewModel.Outcome.Cancelled ->
                if (creating) {
                    PendingIntentHandler.setCreateCredentialException(result, CreateCredentialCancellationException())
                } else {
                    PendingIntentHandler.setGetCredentialException(result, GetCredentialCancellationException())
                }
        }
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    companion object {
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_NAME = "name"
        private const val MODE_FILL = "fill"
        private const val MODE_SEARCH = "search"
        private const val MODE_CREATE = "create"

        private fun intent(
            context: Context,
            mode: String,
            name: String? = null,
        ) = Intent(context, CredentialActivity::class.java)
            .putExtra(EXTRA_MODE, mode)
            .apply { name?.let { putExtra(EXTRA_NAME, it) } }

        fun fill(
            context: Context,
            name: String,
        ): PendingIntent = PasswordsCredentialService.pending(context, intent(context, MODE_FILL, name), "fill:$name")

        fun search(context: Context): PendingIntent =
            PasswordsCredentialService.pending(context, intent(context, MODE_SEARCH), MODE_SEARCH)

        fun create(context: Context): PendingIntent =
            PasswordsCredentialService.pending(context, intent(context, MODE_CREATE), MODE_CREATE)
    }
}

@Composable
private fun SearchDialog(viewModel: CredentialViewModel) {
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
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
private fun SaveDialog(
    viewModel: CredentialViewModel,
    target: CredentialMatcher.Target?,
    request: CreatePasswordRequest,
) {
    val saving by viewModel.saving.collectAsState()
    var name by remember { mutableStateOf(viewModel.suggestName(target, request.id)) }
    val url = (target as? CredentialMatcher.Target.Web)?.host?.let { if ("://" in it) it else "https://$it" }
    AlertDialog(
        onDismissRequest = viewModel::cancel,
        title = { Text("Save password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("For ${request.id.ifBlank { "this sign-in" }}")
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
                onClick = { viewModel.save(name, request.id, request.password, url) },
                enabled =
                    !saving && name.isNotBlank(),
            ) {
                Text(if (saving) "Saving…" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = viewModel::cancel) { Text("Cancel") } },
    )
}
