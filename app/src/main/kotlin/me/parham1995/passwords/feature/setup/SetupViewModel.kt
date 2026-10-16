package me.parham1995.passwords.feature.setup

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.parham1995.passwords.data.KeyRing
import me.parham1995.passwords.data.PasswordRepository
import me.parham1995.passwords.data.SettingsStore
import me.parham1995.passwords.data.git.SshKeyStore
import me.parham1995.passwords.data.label
import me.parham1995.passwords.feature.keys.KeyImport
import javax.inject.Inject

@HiltViewModel
class SetupViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val keyImport: KeyImport,
        private val keyRing: KeyRing,
        private val sshKeys: SshKeyStore,
        private val settings: SettingsStore,
        private val repository: PasswordRepository,
    ) : ViewModel() {
        data class State(
            val secretKeys: List<String> = emptyList(),
            val sshPublicKey: String? = null,
            val remoteUrl: String = "",
            val branch: String = "main",
            val authorName: String = "",
            val authorEmail: String = "",
            val askBackupCode: Boolean = false,
            val busy: Boolean = false,
            val message: String? = null,
            val done: Boolean = false,
        )

        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state.asStateFlow()
        private var pendingBackup: ByteArray? = null

        init {
            viewModelScope.launch {
                val current = settings.current()
                val secrets = withContext(Dispatchers.IO) { keyRing.secretKeys().map { it.label() } }
                _state.update {
                    it.copy(
                        secretKeys = secrets,
                        sshPublicKey = sshKeys.publicKeyLine(),
                        remoteUrl = current.remoteUrl,
                        branch = current.branch,
                        authorName = current.authorName,
                        authorEmail = current.authorEmail,
                    )
                }
            }
        }

        fun edit(transform: (State) -> State) = _state.update { transform(it).copy(message = null) }

        fun importKey(uri: Uri) =
            viewModelScope.launch {
                val bytes =
                    withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                if (bytes == null) {
                    _state.update { it.copy(message = "Could not read that file") }
                    return@launch
                }
                handle(bytes, null)
            }

        fun submitBackupCode(code: CharArray) =
            viewModelScope.launch {
                val bytes = pendingBackup ?: return@launch
                handle(bytes, code)
            }

        fun dismissBackupCode() {
            pendingBackup = null
            _state.update { it.copy(askBackupCode = false) }
        }

        private suspend fun handle(
            bytes: ByteArray,
            code: CharArray?,
        ) {
            val result = withContext(Dispatchers.Default) { keyImport.import(bytes, code) }
            when (result) {
                KeyImport.Result.NeedsBackupCode -> {
                    pendingBackup = bytes
                    _state.update { it.copy(askBackupCode = true) }
                }
                is KeyImport.Result.Failed -> _state.update { it.copy(message = result.message) }
                is KeyImport.Result.Imported -> {
                    pendingBackup = null
                    val secrets = withContext(Dispatchers.IO) { keyRing.secretKeys().map { it.label() } }
                    _state.update {
                        it.copy(
                            askBackupCode = false,
                            secretKeys = secrets,
                            message =
                                if (result.secret) null else PUBLIC_ONLY,
                        )
                    }
                }
            }
        }

        fun generateSshKey() =
            viewModelScope.launch {
                _state.update { it.copy(busy = true) }
                runCatching { sshKeys.generate() }
                    .onSuccess { line -> _state.update { it.copy(busy = false, sshPublicKey = line) } }
                    .onFailure { e ->
                        _state.update { it.copy(busy = false, message = "Could not make a key: ${e.message}") }
                    }
            }

        /** GitHub's "add deploy key" page for the repository in [State.remoteUrl], if it is on GitHub. */
        fun deployKeyPage(): String? =
            githubRepository(_state.value.remoteUrl)?.let {
                "https://github.com/$it/settings/keys/new"
            }

        fun finish() =
            viewModelScope.launch {
                val s = _state.value
                settings.update {
                    it.copy(
                        remoteUrl = s.remoteUrl.trim(),
                        branch = s.branch.trim().ifEmpty { "main" },
                        authorName = s.authorName.trim(),
                        authorEmail = s.authorEmail.trim(),
                    )
                }
                _state.update { it.copy(busy = true, message = "Cloning…") }
                runCatching { repository.sync() }
                    .onSuccess { _state.update { it.copy(busy = false, done = true, message = null) } }
                    .onFailure { e -> _state.update { it.copy(busy = false, message = "Clone failed: ${e.message}") } }
            }

        companion object {
            private const val PUBLIC_ONLY = "That was a public key only; the store needs your secret key"
            private val GITHUB =
                Regex("""(?:git@|ssh://git@)(?:ssh\.)?github\.com[:/](?:443/)?([^/]+/[^/]+?)(?:\.git)?/?$""")

            fun githubRepository(url: String): String? = GITHUB.find(url.trim())?.groupValues?.get(1)
        }
    }
