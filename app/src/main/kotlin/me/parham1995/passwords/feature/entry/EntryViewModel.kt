package me.parham1995.passwords.feature.entry

import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.parham1995.passwords.crypto.PgpException
import me.parham1995.passwords.data.BrokenReference
import me.parham1995.passwords.data.PasswordRepository
import me.parham1995.passwords.data.SettingsStore
import me.parham1995.passwords.data.share.ShareRepository
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
        private val shares: ShareRepository,
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

        /** A one-off share link on its way up, or made; null when none is being made. */
        sealed interface Sharing {
            data object Uploading : Sharing

            /** Holds the link's key: shown once, dropped with the dialog, never stored. */
            class Made(
                val link: String,
            ) : Sharing

            data class Failed(
                val message: String,
            ) : Sharing
        }

        private val _sharing = MutableStateFlow<Sharing?>(null)
        val sharing: StateFlow<Sharing?> = _sharing.asStateFlow()

        /** Whether sharing is set up; the Share action is hidden until it is. */
        val canShare: StateFlow<Boolean> =
            shares
                .configured(
                    settings.settings,
                ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), false)

        init {
            unlock(name)
        }

        fun retry() {
            _state.value = State.Locked
            unlock(name)
        }

        suspend fun clipboardSeconds(): Int = settings.current().clipboardSeconds

        /**
         * Shares the already-decrypted entry: its password, or its text
         * exactly as stored. Nothing is decrypted again, and the bytes handed
         * to age are wiped once sealed.
         */
        fun share(kind: ShareRepository.Kind) {
            val opened = (_state.value as? State.Shown)?.opened ?: return
            if (_sharing.value == Sharing.Uploading) return
            _sharing.value = Sharing.Uploading
            viewModelScope.launch {
                val plain =
                    when (kind) {
                        ShareRepository.Kind.PASSWORD -> opened.password
                        ShareRepository.Kind.ENTRY -> opened.text
                    }.toByteArray()
                _sharing.value =
                    try {
                        val made = shares.share(name, kind, plain)
                        Sharing.Made(String(made.link)).also { made.link.fill('\u0000') }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // A ShareException is worded for a person; anything else is still better shown than thrown.
                        Sharing.Failed(e.message ?: "Could not share $name")
                    } finally {
                        plain.fill(0)
                    }
            }
        }

        fun dismissSharing() {
            if (_sharing.value != Sharing.Uploading) _sharing.value = null
        }

        override fun onUnlocked(passphrase: CharArray?) {
            viewModelScope.launch {
                _state.value =
                    try {
                        State.Shown(repository.openFollowing(name, passphrase))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: PgpException) {
                        State.Failed(e.message ?: "Could not decrypt")
                    } catch (e: BrokenReference) {
                        State.Failed(e.message ?: "Broken reference")
                    } catch (e: Exception) {
                        State.Failed(e.message ?: "Could not open $name")
                    } finally {
                        passphrase?.fill('\u0000')
                    }
            }
        }

        override fun onUnlockFailed(message: String) {
            _state.value = State.Failed(message)
        }

        private companion object {
            const val STOP_TIMEOUT = 5_000L
        }
    }
