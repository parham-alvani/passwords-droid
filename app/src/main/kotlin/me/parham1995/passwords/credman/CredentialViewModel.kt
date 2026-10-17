package me.parham1995.passwords.credman

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.parham1995.passwords.crypto.PgpException
import me.parham1995.passwords.data.BrokenReference
import me.parham1995.passwords.data.MissingRecipients
import me.parham1995.passwords.data.PasswordRepository
import me.parham1995.passwords.store.CredentialMatcher
import me.parham1995.passwords.store.PassEntry
import me.parham1995.passwords.store.PasswordStore
import me.parham1995.passwords.store.Search
import me.parham1995.passwords.store.Totp
import me.parham1995.passwords.unlock.Unlocker
import me.parham1995.passwords.unlock.UnlockingViewModel
import javax.inject.Inject

@HiltViewModel
class CredentialViewModel
    @Inject
    constructor(
        repository: PasswordRepository,
        unlocker: Unlocker,
    ) : UnlockingViewModel(repository, unlocker) {
        sealed interface Outcome {
            /** A username and password to hand back to the asking app. */
            data class Filled(
                val username: String,
                val password: String,
                /** The current one-time code, for forms that ask for it too. */
                val otp: String? = null,
            ) : Outcome

            data object Saved : Outcome

            data class Failed(
                val message: String,
            ) : Outcome

            data object Cancelled : Outcome
        }

        private val _outcome = MutableStateFlow<Outcome?>(null)
        val outcome: StateFlow<Outcome?> = _outcome.asStateFlow()

        private val _query = MutableStateFlow("")
        val query: StateFlow<String> = _query.asStateFlow()

        private val _saving = MutableStateFlow(false)
        val saving: StateFlow<Boolean> = _saving.asStateFlow()

        val results: StateFlow<List<PasswordStore.Item>> =
            combine(
                repository.items,
                _query,
            ) { items, query -> Search.rank(query, items) { it.name }.take(MAX_RESULTS) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        private var chosen: String? = null

        /**
         * The username and password an autofill save request offered, taken
         * from [me.parham1995.passwords.autofill.SaveOffers] once and kept here
         * so a rotated dialog still has them.
         */
        var offered: Pair<String, String>? = null

        init {
            viewModelScope.launch { if (repository.items.value.isEmpty()) repository.refresh() }
        }

        fun search(text: String) {
            _query.value = text
        }

        /** Unlock and decrypt [name], then report it as [Outcome.Filled]. */
        fun fill(name: String) {
            if (chosen != null) return
            chosen = name
            unlock(name)
        }

        override fun onUnlocked(passphrase: CharArray?) {
            val name = chosen ?: return
            viewModelScope.launch {
                _outcome.value =
                    try {
                        val opened = repository.openFollowing(name, passphrase)
                        val item = PasswordStore.Item(name, repository.store.file(name))
                        // The login the entry records, else the account its file is named after.
                        val username = opened.entry.username ?: CredentialMatcher.usernameOf(item)
                        val otp =
                            opened.entry.otp
                                ?.let(Totp::parse)
                                ?.code(System.currentTimeMillis())
                        Outcome.Filled(username, opened.password, otp)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: PgpException) {
                        Outcome.Failed(e.message ?: "Could not decrypt")
                    } catch (e: BrokenReference) {
                        Outcome.Failed(e.message ?: "Broken reference")
                    } catch (e: Exception) {
                        // An entry a sync removed, an unreadable file: the asking app still needs an answer.
                        Outcome.Failed(e.message ?: "Could not open $name")
                    } finally {
                        passphrase?.fill('\u0000')
                    }
            }
        }

        override fun onUnlockFailed(message: String) {
            _outcome.value = if (message == "Locked") Outcome.Cancelled else Outcome.Failed(message)
        }

        /**
         * Where a login offered by [target] should go: next to the site's
         * existing accounts when it has any, otherwise in a folder named after
         * the site.
         */
        fun suggestName(
            target: CredentialMatcher.Target?,
            id: String,
        ): String {
            val account = id.ifBlank { "login" }.replace('/', '_')
            val existing = target?.let { CredentialMatcher.find(repository.items.value, it).firstOrNull() }
            if (existing != null) return existing.item.folder + "/" + account
            val site =
                when (target) {
                    is CredentialMatcher.Target.Web ->
                        target.host
                            .substringAfter(
                                "://",
                            ).substringBefore('/')
                            .removePrefix("www.")
                    is CredentialMatcher.Target.App -> target.packageName
                    null -> "unsorted"
                }
            return "$site/$account"
        }

        fun save(
            name: String,
            id: String,
            password: String,
            url: String?,
        ) = viewModelScope.launch {
            _saving.value = true
            val fields =
                buildList {
                    if (id.isNotBlank()) add(PassEntry.Field("login", id))
                    url?.let { add(PassEntry.Field("url", it)) }
                }
            _outcome.value =
                runCatching { repository.save(name.trim().trim('/'), PassEntry(password, fields, null, "")) }.fold(
                    onSuccess = { Outcome.Saved },
                    onFailure = { e ->
                        Outcome.Failed(
                            if (e is MissingRecipients) {
                                "No public key for ${e.ids.joinToString()}"
                            } else {
                                e.message
                                    ?: "Could not save"
                            },
                        )
                    },
                )
            _saving.value = false
        }

        fun cancel() {
            _outcome.value = Outcome.Cancelled
        }

        private companion object {
            const val MAX_RESULTS = 60
        }
    }
