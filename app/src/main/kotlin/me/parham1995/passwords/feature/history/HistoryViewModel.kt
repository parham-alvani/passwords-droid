package me.parham1995.passwords.feature.history

import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.parham1995.passwords.data.PasswordRepository
import me.parham1995.passwords.data.git.StoreGit
import me.parham1995.passwords.store.PassEntry
import me.parham1995.passwords.unlock.Unlocker
import me.parham1995.passwords.unlock.UnlockingViewModel

@HiltViewModel(assistedFactory = HistoryViewModel.Factory::class)
class HistoryViewModel
    @AssistedInject
    constructor(
        @Assisted val name: String,
        repository: PasswordRepository,
        unlocker: Unlocker,
    ) : UnlockingViewModel(repository, unlocker) {
        @AssistedFactory
        interface Factory {
            fun create(name: String): HistoryViewModel
        }

        data class Viewing(
            val revision: StoreGit.Revision,
            val entry: PassEntry?,
            val error: String? = null,
        )

        private val _revisions = MutableStateFlow<List<StoreGit.Revision>?>(null)
        val revisions: StateFlow<List<StoreGit.Revision>?> = _revisions.asStateFlow()

        private val _viewing = MutableStateFlow<Viewing?>(null)
        val viewing: StateFlow<Viewing?> = _viewing.asStateFlow()

        private val _restored = MutableStateFlow(false)
        val restored: StateFlow<Boolean> = _restored.asStateFlow()

        private var pending: StoreGit.Revision? = null
        private var restoring = false
        private var passphrase: CharArray? = null

        init {
            // The list of versions is commit metadata; it needs no passphrase.
            viewModelScope.launch {
                _revisions.value =
                    runCatching { repository.history(name) }.getOrDefault(emptyList())
            }
        }

        fun view(revision: StoreGit.Revision) {
            pending = revision
            restoring = false
            unlock(name)
        }

        fun restore() {
            val revision = _viewing.value?.revision ?: return
            pending = revision
            restoring = true
            unlock(name)
        }

        fun close() {
            _viewing.value = null
        }

        override fun onUnlocked(passphrase: CharArray?) {
            val revision = pending ?: return
            viewModelScope.launch {
                try {
                    if (restoring) {
                        repository.restore(name, revision, passphrase)
                        _restored.value = true
                    } else {
                        _viewing.value = Viewing(revision, repository.openAt(name, revision.commit, passphrase))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _viewing.value = Viewing(revision, null, e.message ?: "Could not open this version")
                } finally {
                    passphrase?.fill('\u0000')
                }
            }
        }

        override fun onUnlockFailed(message: String) {
            if (message != "Locked") pending?.let { _viewing.value = Viewing(it, null, message) }
        }
    }
