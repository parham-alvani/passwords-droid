package me.parham1995.passwords.data.share

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.parham1995.passwords.data.PassphraseCache
import me.parham1995.passwords.data.security.SecretBox
import java.io.File

/**
 * The GitHub token shares are pushed with, sealed by [box] (the at-rest
 * Keystore key) like the SSH and OpenPGP keys. Write-only from the UI's side:
 * it can be saved, used and removed, never shown again.
 */
class ShareTokenStore(
    private val file: File,
    private val box: SecretBox,
) {
    private val _saved = MutableStateFlow(file.isFile)

    /** Whether a token is stored; files have nothing to subscribe to. */
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    /** Seals [token]; the caller wipes it. */
    @Synchronized
    fun save(token: CharArray) {
        val bytes = PassphraseCache.encode(token)
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeBytes(box.seal(bytes))
            check(tmp.renameTo(file)) { "could not store the token" }
        } finally {
            bytes.fill(0)
        }
        _saved.value = true
    }

    /** The token, unsealed, for the caller to wipe; null when there is none. */
    @Synchronized
    fun open(): CharArray? = file.takeIf { it.isFile }?.let { PassphraseCache.decode(box.open(it.readBytes())) }

    @Synchronized
    fun delete() {
        file.delete()
        _saved.value = false
    }
}
