package me.parham1995.passwords.autofill

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import android.service.autofill.Dataset
import android.view.WindowManager
import android.view.autofill.AutofillManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.github.androidpasswordstore.autofillparser.AutofillAction
import com.github.androidpasswordstore.autofillparser.AutofillScenario
import com.github.androidpasswordstore.autofillparser.Credentials
import com.github.androidpasswordstore.autofillparser.FormOrigin
import com.github.androidpasswordstore.autofillparser.fillWith
import dagger.hilt.android.AndroidEntryPoint
import me.parham1995.passwords.credman.CredentialViewModel
import me.parham1995.passwords.credman.SaveDialog
import me.parham1995.passwords.credman.SearchDialog
import me.parham1995.passwords.ui.theme.PasswordsTheme
import me.parham1995.passwords.unlock.UnlockPrompts

/**
 * Opened by autofill when a locked suggestion is chosen: unlock, decrypt that
 * entry, and return a dataset that fills the form. Also "Search Passwords"
 * and saving what was typed into a form.
 *
 * The same view model and dialogs as the Credential Manager path; only how
 * the answer is handed back differs.
 */
@AndroidEntryPoint
class AutofillActivity : ComponentActivity() {
    private val viewModel: CredentialViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val mode = intent.getStringExtra(EXTRA_MODE)
        setContent {
            PasswordsTheme {
                val outcome by viewModel.outcome.collectAsState()
                LaunchedEffect(outcome) { outcome?.let(::finishWith) }
                UnlockPrompts(viewModel)
                when (mode) {
                    MODE_FILL -> LaunchedEffect(Unit) { intent.getStringExtra(EXTRA_NAME)?.let(viewModel::fill) }
                    MODE_SEARCH -> SearchDialog(viewModel)
                    MODE_SAVE -> {
                        val origin = intent.getBundleExtra(EXTRA_ORIGIN)?.let { FormOrigin.fromBundle(it) }
                        SaveDialog(
                            viewModel,
                            origin?.toTarget(),
                            intent.getStringExtra(EXTRA_USERNAME).orEmpty(),
                            intent.getStringExtra(EXTRA_PASSWORD).orEmpty(),
                        )
                    }
                    else -> LaunchedEffect(Unit) { viewModel.cancel() }
                }
            }
        }
    }

    private fun finishWith(outcome: CredentialViewModel.Outcome) {
        when (outcome) {
            is CredentialViewModel.Outcome.Filled -> {
                val clientState = intent.getBundleExtra(AutofillManager.EXTRA_CLIENT_STATE)
                val scenario = clientState?.let { AutofillScenario.fromClientState(it) }
                if (scenario == null) {
                    setResult(Activity.RESULT_CANCELED)
                } else {
                    val action =
                        if (intent.getStringExtra(EXTRA_MODE) ==
                            MODE_SEARCH
                        ) {
                            AutofillAction.Search
                        } else {
                            AutofillAction.Match
                        }
                    val credentials =
                        Credentials(outcome.username.toCharArray(), outcome.password.toCharArray(), outcome.otp)
                    val dataset = Dataset.Builder().apply { fillWith(scenario, action, credentials) }.build()
                    setResult(
                        Activity.RESULT_OK,
                        Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset),
                    )
                }
            }
            CredentialViewModel.Outcome.Saved -> setResult(Activity.RESULT_OK)
            is CredentialViewModel.Outcome.Failed, CredentialViewModel.Outcome.Cancelled ->
                setResult(
                    Activity.RESULT_CANCELED,
                )
        }
        finish()
    }

    companion object {
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_ORIGIN = "origin"
        private const val EXTRA_USERNAME = "username"
        private const val EXTRA_PASSWORD = "password"
        private const val MODE_FILL = "fill"
        private const val MODE_SEARCH = "search"
        private const val MODE_SAVE = "save"

        private var requestCode = 0

        private fun sender(
            context: Context,
            intent: Intent,
        ): IntentSender =
            PendingIntent
                .getActivity(
                    context,
                    requestCode++,
                    intent,
                    // Mutable: autofill adds the client state before launching it.
                    PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE,
                ).intentSender

        private fun intent(
            context: Context,
            mode: String,
        ) = Intent(context, AutofillActivity::class.java).putExtra(EXTRA_MODE, mode)

        fun fill(
            context: Context,
            name: String,
            clientState: Bundle,
        ): IntentSender =
            sender(
                context,
                intent(
                    context,
                    MODE_FILL,
                ).putExtra(EXTRA_NAME, name).putExtra(AutofillManager.EXTRA_CLIENT_STATE, clientState),
            )

        fun search(
            context: Context,
            clientState: Bundle,
        ): IntentSender =
            sender(context, intent(context, MODE_SEARCH).putExtra(AutofillManager.EXTRA_CLIENT_STATE, clientState))

        /** The typed values travel in the intent only until the dialog encrypts them. */
        fun save(
            context: Context,
            origin: FormOrigin,
            username: String,
            password: String,
        ): IntentSender =
            sender(
                context,
                intent(context, MODE_SAVE)
                    .putExtra(EXTRA_ORIGIN, origin.toBundle())
                    .putExtra(EXTRA_USERNAME, username)
                    .putExtra(EXTRA_PASSWORD, password),
            )
    }
}
