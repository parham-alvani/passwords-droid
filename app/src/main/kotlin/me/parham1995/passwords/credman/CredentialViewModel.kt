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
import me.parham1995.passwords.data.CommitMessages
import me.parham1995.passwords.data.MissingRecipients
import me.parham1995.passwords.data.PasswordRepository
import me.parham1995.passwords.store.CredentialMatcher
import me.parham1995.passwords.store.PasswordStore
import me.parham1995.passwords.store.SavePlanner
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
                /**
                 * Set for an SSO / social login with no password of its own:
                 * only the username is filled, and [password] is empty.
                 */
                val loginMethod: String? = null,
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
            if (updating != null) {
                viewModelScope.launch { finishUpdate(passphrase) }
                return
            }
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
                        val loginMethod =
                            opened.entry.loginMethod?.takeIf {
                                opened.entry.isPasswordless && opened.password.isBlank()
                            }
                        Outcome.Filled(username, opened.password, otp, loginMethod)
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
            if (updating != null) {
                // Back to the save dialog; a dismissed prompt is not a failed save.
                updating = null
                if (message != "Locked") _outcome.value = Outcome.Failed(message)
                return
            }
            _outcome.value = if (message == "Locked") Outcome.Cancelled else Outcome.Failed(message)
        }

        private val _plan = MutableStateFlow<SavePlanner.Plan?>(null)

        /** What saving the offered login would do: update an entry the store has, or add one. */
        val plan: StateFlow<SavePlanner.Plan?> = _plan.asStateFlow()

        /** The entry whose password is being replaced, and the new password, while it is unlocked. */
        private var updating: Pair<String, String>? = null

        fun planSave(
            target: CredentialMatcher.Target?,
            id: String,
            password: String,
        ) {
            if (_plan.value != null) return
            viewModelScope.launch {
                if (repository.items.value.isEmpty()) repository.refresh()
                _plan.value =
                    SavePlanner.plan(repository.items.value, target, id, password, repository.createTemplates())
            }
        }

        /** Adds the offered login as a new entry called [name]. */
        fun create(
            name: String,
            text: String,
        ) = viewModelScope.launch {
            val clean = name.trim().trim('/')
            if (repository.exists(clean)) {
                // A name the store has: replace only its password, never the whole entry.
                _plan.value = _plan.value?.copy(update = clean)
                return@launch
            }
            _saving.value = true
            _outcome.value =
                try {
                    repository.saveText(clean, text, CommitMessages.insert(clean))
                    Outcome.Saved
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Outcome.Failed(saveFailure(e))
                }
            _saving.value = false
        }

        /**
         * Replaces the password of [name] with [password], keeping every other
         * line of it. Decrypting it needs an unlock, so this asks for one first.
         */
        fun update(
            name: String,
            password: String,
        ) {
            if (updating != null) return
            updating = name to password
            unlock(name)
        }

        private suspend fun finishUpdate(passphrase: CharArray?) {
            val (name, password) = updating ?: return
            _saving.value = true
            _outcome.value =
                try {
                    val entry = repository.open(name, passphrase)
                    when {
                        entry.reference != null ->
                            Outcome.Failed("$name takes its password from ${entry.reference}; update that entry")
                        entry.password == password -> Outcome.Saved
                        else -> {
                            repository.save(name, entry.copy(password = password), previousName = name)
                            Outcome.Saved
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Outcome.Failed(saveFailure(e))
                } finally {
                    passphrase?.fill('\u0000')
                }
            _saving.value = false
        }

        private fun saveFailure(e: Exception): String =
            when (e) {
                is MissingRecipients -> "No public key for ${e.ids.joinToString()}"
                is PgpException -> e.message ?: "Could not decrypt"
                else -> e.message ?: "Could not save"
            }

        fun cancel() {
            _outcome.value = Outcome.Cancelled
        }

        private companion object {
            const val MAX_RESULTS = 60
        }
    }
