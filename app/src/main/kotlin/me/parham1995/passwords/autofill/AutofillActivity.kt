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
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
        val offered =
            if (mode == MODE_SAVE) {
                viewModel.offered
                    ?: intent
                        .getStringExtra(EXTRA_OFFER)
                        ?.let(SaveOffers::take)
                        ?.let { offer ->
                            (offer.username.concatToString() to offer.password.concatToString()).also { offer.wipe() }
                        }.also { viewModel.offered = it }
            } else {
                null
            }
        setContent {
            PasswordsTheme {
                val outcome by viewModel.outcome.collectAsStateWithLifecycle()
                LaunchedEffect(outcome) { outcome?.let(::finishWith) }
                UnlockPrompts(viewModel)
                when (mode) {
                    MODE_FILL -> LaunchedEffect(Unit) { intent.getStringExtra(EXTRA_NAME)?.let(viewModel::fill) }
                    MODE_SEARCH -> SearchDialog(viewModel)
                    MODE_SAVE -> {
                        val origin = intent.getBundleExtra(EXTRA_ORIGIN)?.let { FormOrigin.fromBundle(it) }
                        if (offered == null) {
                            // Already taken, gone stale, or lost with a restarted process.
                            LaunchedEffect(Unit) { viewModel.cancel() }
                        } else {
                            SaveDialog(viewModel, origin?.toTarget(), offered.first, offered.second)
                        }
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
                    // A passwordless (SSO) account fills the username and leaves the password field alone.
                    val password = if (outcome.loginMethod != null) null else outcome.password.toCharArray()
                    val credentials = Credentials(outcome.username.toCharArray(), password, outcome.otp)
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
        private const val EXTRA_OFFER = "offer"
        private const val MODE_FILL = "fill"
        private const val MODE_SEARCH = "search"
        private const val MODE_SAVE = "save"

        /**
         * Each sender is told apart by its intent's identifier, never a request
         * code: [identifier] is unique per dataset and per fill response, so no
         * new sender replaces or cancels one an earlier response still shows.
         */
        private fun sender(
            context: Context,
            intent: Intent,
            identifier: String,
        ): IntentSender =
            PendingIntent
                .getActivity(
                    context,
                    0,
                    intent.setIdentifier(identifier),
                    // Mutable: autofill adds the client state before launching it.
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                ).intentSender

        private fun intent(
            context: Context,
            mode: String,
        ) = Intent(context, AutofillActivity::class.java).putExtra(EXTRA_MODE, mode)

        /** [response] is unique per fill response, so its senders never collide with an earlier one's. */
        fun fill(
            context: Context,
            name: String,
            clientState: Bundle,
            response: String,
        ): IntentSender =
            sender(
                context,
                intent(
                    context,
                    MODE_FILL,
                ).putExtra(EXTRA_NAME, name).putExtra(AutofillManager.EXTRA_CLIENT_STATE, clientState),
                "$MODE_FILL:$response:$name",
            )

        fun search(
            context: Context,
            clientState: Bundle,
            response: String,
        ): IntentSender =
            sender(
                context,
                intent(context, MODE_SEARCH).putExtra(AutofillManager.EXTRA_CLIENT_STATE, clientState),
                "$MODE_SEARCH:$response",
            )

        /**
         * The typed values stay in this process ([SaveOffers]); the intent
         * carries only the token that finds them, once.
         */
        fun save(
            context: Context,
            origin: FormOrigin,
            username: CharArray,
            password: CharArray,
        ): IntentSender {
            val token = SaveOffers.put(username, password)
            return sender(
                context,
                intent(context, MODE_SAVE)
                    .putExtra(EXTRA_ORIGIN, origin.toBundle())
                    .putExtra(EXTRA_OFFER, token),
                "$MODE_SAVE:$token",
            )
        }
    }
}
