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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CreatePasswordResponse
import androidx.credentials.GetCredentialResponse
import androidx.credentials.PasswordCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.PendingIntentHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
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
                val outcome by viewModel.outcome.collectAsStateWithLifecycle()
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
                            SaveDialog(viewModel, target, request.id, request.password)
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
