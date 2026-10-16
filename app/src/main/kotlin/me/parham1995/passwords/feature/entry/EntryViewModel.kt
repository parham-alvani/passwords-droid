package me.parham1995.passwords.feature.entry

import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.parham1995.passwords.crypto.PgpException
import me.parham1995.passwords.data.BrokenReference
import me.parham1995.passwords.data.PasswordRepository
import me.parham1995.passwords.data.SettingsStore
import me.parham1995.passwords.unlock.Unlocker
import me.parham1995.passwords.unlock.UnlockingViewModel

@HiltViewModel(assistedFactory = EntryViewModel.Factory::class)
class EntryViewModel
    @AssistedInject
    constructor(
        @Assisted val name: String,
        repository: PasswordRepository,
        unlocker: Unlocker,
        private val settings: SettingsStore,
    ) : UnlockingViewModel(repository, unlocker) {
        @AssistedFactory
        interface Factory {
            fun create(name: String): EntryViewModel
        }

        sealed interface State {
            data object Locked : State

            data class Shown(
                val opened: PasswordRepository.Opened,
            ) : State

            data class Failed(
                val message: String,
            ) : State
        }

        private val _state = MutableStateFlow<State>(State.Locked)
        val state: StateFlow<State> = _state.asStateFlow()

        init {
            unlock(name)
        }

        fun retry() {
            _state.value = State.Locked
            unlock(name)
        }

        suspend fun clipboardSeconds(): Int = settings.current().clipboardSeconds

        override fun onUnlocked(passphrase: CharArray?) {
            viewModelScope.launch {
                _state.value =
                    try {
                        State.Shown(repository.openFollowing(name, passphrase))
                    } catch (e: PgpException) {
                        State.Failed(e.message ?: "Could not decrypt")
                    } catch (e: BrokenReference) {
                        State.Failed(e.message ?: "Broken reference")
                    } finally {
                        passphrase?.fill('\u0000')
                    }
            }
        }

        override fun onUnlockFailed(message: String) {
            _state.value = State.Failed(message)
        }
    }
