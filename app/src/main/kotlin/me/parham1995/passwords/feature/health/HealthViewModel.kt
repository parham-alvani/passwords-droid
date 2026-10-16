package me.parham1995.passwords.feature.health

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.parham1995.passwords.data.PasswordRepository
import me.parham1995.passwords.store.PasswordHealth
import me.parham1995.passwords.unlock.Unlocker
import me.parham1995.passwords.unlock.UnlockingViewModel
import javax.inject.Inject

@HiltViewModel
class HealthViewModel
    @Inject
    constructor(
        repository: PasswordRepository,
        unlocker: Unlocker,
    ) : UnlockingViewModel(repository, unlocker) {
        sealed interface State {
            data object Idle : State

            data class Running(
                val done: Int,
                val total: Int,
                val phase: String,
            ) : State

            data class Done(
                val report: PasswordHealth.Report,
                /** Breach counts by entry, or null when the online check was not run. */
                val pwned: Map<String, Int>?,
                val pwnedError: String?,
            ) : State

            data class Failed(
                val message: String,
            ) : State
        }

        private val _state = MutableStateFlow<State>(State.Idle)
        val state: StateFlow<State> = _state.asStateFlow()

        private var checkBreaches = false

        fun run(breaches: Boolean) {
            val any = repository.items.value.firstOrNull { it.aliasOf == null } ?: return
            checkBreaches = breaches
            _state.value = State.Running(0, 0, "Unlocking")
            unlock(any.name)
        }

        override fun onUnlocked(passphrase: CharArray?) {
            viewModelScope.launch {
                try {
                    val candidates =
                        repository.healthCandidates(passphrase) { done, total ->
                            _state.value = State.Running(done, total, "Decrypting")
                        }
                    _state.value = State.Running(candidates.size, candidates.size, "Analysing")
                    val report = PasswordHealth.analyze(candidates, System.currentTimeMillis())
                    var pwned: Map<String, Int>? = null
                    var pwnedError: String? = null
                    if (checkBreaches) {
                        _state.value = State.Running(candidates.size, candidates.size, "Checking breaches")
                        runCatching { repository.pwned(candidates) }
                            .onSuccess { pwned = it }
                            .onFailure { pwnedError = it.message ?: "network error" }
                    }
                    // `candidates` goes out of scope here; nothing keeps a password.
                    _state.value = State.Done(report, pwned, pwnedError)
                } catch (e: Exception) {
                    _state.value = State.Failed(e.message ?: "Could not check")
                } finally {
                    passphrase?.fill('\u0000')
                }
            }
        }

        override fun onUnlockFailed(message: String) {
            _state.value = if (message == "Locked") State.Idle else State.Failed(message)
        }
    }
