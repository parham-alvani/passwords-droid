package me.parham1995.passwords.credman

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import android.util.Log
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.Action
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePasswordCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPasswordOption
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialEntry
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.PasswordCredentialEntry
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.parham1995.passwords.R
import me.parham1995.passwords.data.PasswordRepository
import me.parham1995.passwords.store.CredentialMatcher
import javax.inject.Inject

/**
 * Answers Android's sign-in sheet -- and, through the platform's credential
 * autofill bridge, ordinary login forms -- from the store.
 *
 * The first step only says *which* entries could fill the request, from their
 * names; nothing is decrypted, and no passphrase is needed to see the list.
 * Choosing an entry opens [CredentialActivity], which unlocks and decrypts
 * just that one.
 */
@AndroidEntryPoint
class PasswordsCredentialService : CredentialProviderService() {
    @Inject
    lateinit var repository: PasswordRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val callers by lazy { Callers(this) }

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>,
    ) {
        val job =
            scope.launch {
                runCatching { respond(request) }
                    .onSuccess(callback::onResult)
                    .onFailure { callback.onError(GetCredentialUnknownException(it.message)) }
            }
        cancellationSignal.setOnCancelListener { job.cancel() }
    }

    private suspend fun respond(request: BeginGetCredentialRequest): BeginGetCredentialResponse {
        if (repository.items.value.isEmpty()) repository.refresh()
        val caller = request.callingAppInfo
        val target = caller?.let(callers::targetOf)
        val icon = Icon.createWithResource(this, R.mipmap.ic_launcher)
        val entries = mutableListOf<CredentialEntry>()
        request.beginGetCredentialOptions.filterIsInstance<BeginGetPasswordOption>().forEach { option ->
            val matches = target?.let { CredentialMatcher.find(repository.items.value, it) }.orEmpty()
            matches.forEach { match ->
                entries +=
                    PasswordCredentialEntry
                        .Builder(this, match.username, CredentialActivity.fill(this, match.item.name), option)
                        .setDisplayName(match.item.folder)
                        .setIcon(icon)
                        .build()
            }
        }
        // Who asked and how many names matched -- never a secret, and it is
        // the only way to tell "not asked" from "asked, matched nothing".
        Log.d(TAG, "request from ${caller?.packageName} as $target: ${entries.size} entries")
        val search = Action.Builder(getString(R.string.credman_search), CredentialActivity.search(this)).build()
        return BeginGetCredentialResponse
            .Builder()
            .setCredentialEntries(entries)
            .setActions(listOf(search))
            .build()
    }

    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>,
    ) {
        if (request !is BeginCreatePasswordCredentialRequest) {
            callback.onError(CreateCredentialUnknownException("only passwords are kept here"))
            return
        }
        val entry = CreateEntry.Builder(getString(R.string.app_name), CredentialActivity.create(this)).build()
        callback.onResult(BeginCreateCredentialResponse.Builder().addCreateEntry(entry).build())
    }

    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>,
    ) {
        // Nothing is cached per caller, so there is nothing to forget.
        callback.onResult(null)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    internal companion object {
        private const val TAG = "PasswordsCredman"

        /**
         * One PendingIntent per [key], told apart by the intent's identifier
         * rather than a request code -- two names with one hash would share one.
         */
        fun pending(
            service: android.content.Context,
            intent: Intent,
            key: String,
        ): PendingIntent =
            PendingIntent.getActivity(
                service,
                0,
                intent.setIdentifier(key),
                // Mutable: the system writes the request into it before launching.
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
