package me.parham1995.passwords.unlock

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.parham1995.passwords.data.PasswordRepository
import me.parham1995.passwords.data.label
import org.bouncycastle.openpgp.api.OpenPGPKey
import javax.crypto.Cipher

/**
 * What a screen needs to get from "open this entry" to its decrypted text:
 * find the key, get the passphrase the cheapest way there is, decrypt.
 * Shared by the entry and edit screens; [UnlockPrompts] draws the dialogs.
 */
abstract class UnlockingViewModel(
    protected val repository: PasswordRepository,
    private val unlocker: Unlocker,
) : ViewModel() {
    sealed interface Prompt {
        data class Biometric(
            val key: OpenPGPKey,
            val cipher: Cipher,
        ) : Prompt

        data class Typed(
            val key: OpenPGPKey,
            val label: String,
            val wrong: Boolean,
        ) : Prompt

        /** The passphrase was right; a second prompt seals it for next time. */
        data class Seal(
            val key: OpenPGPKey,
            val cipher: Cipher,
            val passphrase: CharArray,
        ) : Prompt
    }

    private val _prompt = MutableStateFlow<Prompt?>(null)
    val prompt: StateFlow<Prompt?> = _prompt.asStateFlow()

    /** Called with the passphrase (null for an unprotected key) once one is known. */
    protected abstract fun onUnlocked(passphrase: CharArray?)

    protected abstract fun onUnlockFailed(message: String)

    protected fun unlock(name: String) =
        viewModelScope.launch {
            val key = repository.keyFor(name)
            if (key == null) {
                onUnlockFailed("No secret key is imported. Add one in Settings.")
                return@launch
            }
            when (val need = unlocker.prepare(key)) {
                is Unlocker.Need.Ready -> onUnlocked(need.passphrase)
                is Unlocker.Need.Biometric -> _prompt.value = Prompt.Biometric(need.key, need.cipher)
                is Unlocker.Need.Typed -> _prompt.value = Prompt.Typed(need.key, need.label, wrong = false)
            }
        }

    fun onBiometric(
        key: OpenPGPKey,
        cipher: Cipher?,
    ) {
        val passphrase = cipher?.let { runCatching { unlocker.fromBiometric(key, it) }.getOrNull() }
        if (passphrase != null) {
            _prompt.value = null
            onUnlocked(passphrase)
        } else {
            _prompt.value = Prompt.Typed(key, key.label(), wrong = false)
        }
    }

    fun onTyped(
        key: OpenPGPKey,
        passphrase: CharArray,
        useBiometric: Boolean,
    ) = viewModelScope.launch {
        val current = _prompt.value as? Prompt.Typed ?: return@launch
        if (!unlocker.check(key, passphrase)) {
            _prompt.value = current.copy(wrong = true)
            return@launch
        }
        unlocker.remember(key, passphrase)
        val sealing = if (useBiometric) unlocker.sealingCipher() else null
        if (sealing != null) {
            _prompt.value = Prompt.Seal(key, sealing, passphrase)
        } else {
            _prompt.value = null
            onUnlocked(passphrase)
        }
    }

    fun onSealed(
        prompt: Prompt.Seal,
        cipher: Cipher?,
    ) {
        if (cipher != null) runCatching { unlocker.seal(prompt.key, cipher, prompt.passphrase) }
        _prompt.value = null
        onUnlocked(prompt.passphrase)
    }

    fun onCancelled() {
        _prompt.value = null
        onUnlockFailed("Locked")
    }
}

@Composable
fun UnlockPrompts(viewModel: UnlockingViewModel) {
    val prompt by viewModel.prompt.collectAsState()
    val context = LocalContext.current
    when (val current = prompt) {
        null -> Unit
        is UnlockingViewModel.Prompt.Biometric ->
            LaunchedEffect(current) {
                val result = authenticate(context, "Unlock passwords", "Use your fingerprint", current.cipher)
                viewModel.onBiometric(current.key, result)
            }
        is UnlockingViewModel.Prompt.Seal ->
            LaunchedEffect(current) {
                val result =
                    authenticate(context, "Remember passphrase", "Touch the sensor to use it next time", current.cipher)
                viewModel.onSealed(current, result)
            }
        is UnlockingViewModel.Prompt.Typed ->
            PassphraseDialog(
                label = current.label,
                wrong = current.wrong,
                offerBiometric = true,
                onSubmit = { passphrase, useBiometric -> viewModel.onTyped(current.key, passphrase, useBiometric) },
                onDismiss = viewModel::onCancelled,
            )
    }
}
