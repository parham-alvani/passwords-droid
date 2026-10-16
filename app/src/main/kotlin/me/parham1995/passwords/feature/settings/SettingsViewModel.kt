package me.parham1995.passwords.feature.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.parham1995.passwords.data.KeyRing
import me.parham1995.passwords.data.PassphraseCache
import me.parham1995.passwords.data.Settings
import me.parham1995.passwords.data.SettingsStore
import me.parham1995.passwords.data.fingerprintHex
import me.parham1995.passwords.data.git.SshKeyStore
import me.parham1995.passwords.data.label
import me.parham1995.passwords.data.security.KeystoreBox
import me.parham1995.passwords.feature.keys.KeyImport
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val store: SettingsStore,
        private val keyRing: KeyRing,
        private val keyImport: KeyImport,
        private val sshKeys: SshKeyStore,
        private val passphrases: PassphraseCache,
        private val biometricBox: KeystoreBox,
    ) : ViewModel() {
        data class Key(
            val label: String,
            val fingerprint: String,
            val secret: Boolean,
        )

        val settings: StateFlow<Settings> = store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

        private val _keys = MutableStateFlow<List<Key>>(emptyList())
        val keys: StateFlow<List<Key>> = _keys.asStateFlow()

        private val _message = MutableStateFlow<String?>(null)
        val message: StateFlow<String?> = _message.asStateFlow()

        private val _askBackupCode = MutableStateFlow(false)
        val askBackupCode: StateFlow<Boolean> = _askBackupCode.asStateFlow()
        private var pendingBackup: ByteArray? = null

        val sshPublicKey: String? get() = sshKeys.publicKeyLine()
        val sshFingerprint: String? get() = sshKeys.fingerprint()

        /** Whether the at-rest key landed in StrongBox (Knox Vault) rather than the TEE. */
        val strongBox: Boolean by lazy { KeystoreBox.atRest().isStrongBox() }

        init {
            reloadKeys()
        }

        private fun reloadKeys() =
            viewModelScope.launch {
                _keys.value =
                    withContext(Dispatchers.IO) {
                        keyRing.all().map { Key(it.label(), it.fingerprintHex(), it.isSecretKey) }
                    }
            }

        fun update(transform: (Settings) -> Settings) = viewModelScope.launch { store.update(transform) }

        fun setBiometric(enabled: Boolean) =
            viewModelScope.launch {
                store.update { it.copy(biometricUnlock = enabled) }
                if (!enabled) {
                    passphrases.dropAllSealed()
                    biometricBox.delete()
                }
            }

        fun importKey(uri: Uri) =
            viewModelScope.launch {
                val bytes =
                    withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                bytes?.let { handle(it, null) }
            }

        fun submitBackupCode(code: CharArray) = viewModelScope.launch { pendingBackup?.let { handle(it, code) } }

        fun dismissBackupCode() {
            pendingBackup = null
            _askBackupCode.value = false
        }

        private suspend fun handle(
            bytes: ByteArray,
            code: CharArray?,
        ) {
            when (val result = withContext(Dispatchers.Default) { keyImport.import(bytes, code) }) {
                KeyImport.Result.NeedsBackupCode -> {
                    pendingBackup = bytes
                    _askBackupCode.value = true
                }
                is KeyImport.Result.Failed -> _message.value = result.message
                is KeyImport.Result.Imported -> {
                    dismissBackupCode()
                    _message.value = "Imported " + result.labels.joinToString()
                    reloadKeys()
                }
            }
        }

        fun removeKey(fingerprint: String) =
            viewModelScope.launch {
                withContext(Dispatchers.IO) { keyRing.remove(fingerprint) }
                passphrases.dropSealed(fingerprint)
                passphrases.forget(fingerprint)
                reloadKeys()
            }

        fun forgetPassphrases() {
            passphrases.forgetAll()
            passphrases.dropAllSealed()
            _message.value = "Passphrases forgotten"
        }

        fun messageShown() {
            _message.value = null
        }

        /** Everything: keys, SSH key, the clone, settings. Git history on the server is untouched. */
        fun reset(onDone: () -> Unit) =
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    passphrases.forgetAll()
                    passphrases.dropAllSealed()
                    biometricBox.delete()
                    sshKeys.delete()
                    File(context.filesDir, "store").deleteRecursively()
                    File(context.filesDir, "keys").deleteRecursively()
                }
                store.update { Settings() }
                onDone()
            }
    }
