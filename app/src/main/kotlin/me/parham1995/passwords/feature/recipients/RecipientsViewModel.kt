package me.parham1995.passwords.feature.recipients

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.parham1995.passwords.crypto.PgpCrypto
import me.parham1995.passwords.data.PasswordRepository
import me.parham1995.passwords.data.fingerprintHex
import me.parham1995.passwords.data.label
import me.parham1995.passwords.unlock.Unlocker
import me.parham1995.passwords.unlock.UnlockingViewModel
import javax.inject.Inject

@HiltViewModel
class RecipientsViewModel
    @Inject
    constructor(
        repository: PasswordRepository,
        unlocker: Unlocker,
    ) : UnlockingViewModel(repository, unlocker) {
        data class Candidate(
            /** How the id is written in `.gpg-id`: the current line, or `0x` + long id for a new one. */
            val id: String,
            val label: String,
            val fingerprint: String?,
            val secret: Boolean,
        )

        data class State(
            val folder: String = "",
            /** The folder whose `.gpg-id` actually governs [folder]. */
            val governedBy: String = "",
            val candidates: List<Candidate> = emptyList(),
            val selected: Set<String> = emptySet(),
            val current: Set<String> = emptySet(),
            val entries: Int = 0,
            val progress: Pair<Int, Int>? = null,
            val message: String? = null,
            /** An entry [governedBy] decides, to unlock with; null when it has none. */
            val unlockWith: String? = null,
        ) {
            val changed: Boolean get() = selected != current && selected.isNotEmpty()
        }

        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state.asStateFlow()

        init {
            load("")
        }

        fun load(folder: String) {
            viewModelScope.launch { reload(folder) }
        }

        private suspend fun reload(folder: String) {
            val dir = folder.trim().trim('/')
            val (governedBy, ids) = repository.recipients(dir)
            val certs = repository.knownCertificates()
            val listed =
                ids.map { id ->
                    val cert = certs.firstOrNull { PgpCrypto.matches(it, id) }
                    Candidate(id, cert?.label() ?: "Unknown key", cert?.fingerprintHex(), cert?.isSecretKey == true)
                }
            // New recipients are written as full fingerprints, as gopass does.
            val others =
                certs
                    .filter { cert -> ids.none { PgpCrypto.matches(cert, it) } }
                    .map { Candidate(it.fingerprintHex(), it.label(), it.fingerprintHex(), it.isSecretKey) }
            val entries = repository.governedEntries(governedBy)
            _state.value =
                State(
                    folder = dir,
                    governedBy = governedBy,
                    candidates = listed + others,
                    selected = ids.toSet(),
                    current = ids.toSet(),
                    entries = entries.size,
                    unlockWith = entries.firstOrNull(),
                )
        }

        fun toggle(id: String) {
            val s = _state.value
            _state.value = s.copy(selected = if (id in s.selected) s.selected - id else s.selected + id, message = null)
        }

        fun apply() {
            val s = _state.value
            _state.value = s.copy(progress = 0 to s.entries)
            // Unlock with an entry this folder governs: that is what will be
            // decrypted. A folder with no entries has nothing to decrypt.
            val entry = s.unlockWith
            if (entry == null) onUnlocked(null) else unlock(entry)
        }

        override fun onUnlocked(passphrase: CharArray?) {
            val s = _state.value
            // Keep the order of .gpg-id: existing lines first, new ones after.
            val ids = s.candidates.map { it.id }.filter { it in s.selected }
            viewModelScope.launch {
                try {
                    repository.setRecipients(s.governedBy, ids, passphrase) { done, total ->
                        _state.value =
                            _state.value.copy(progress = done to total)
                    }
                    reload(s.folder)
                    _state.value = _state.value.copy(message = "Re-encrypted ${s.entries} entries and pushed.")
                } catch (e: Exception) {
                    _state.value = _state.value.copy(progress = null, message = e.message ?: "Could not re-encrypt")
                } finally {
                    passphrase?.fill('\u0000')
                }
            }
        }

        override fun onUnlockFailed(message: String) {
            _state.value = _state.value.copy(progress = null, message = if (message == "Locked") null else message)
        }
    }
