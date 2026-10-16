package me.parham1995.passwords.data

import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Base64
import androidx.core.content.edit
import java.nio.ByteBuffer
import java.nio.CharBuffer

/**
 * Remembers OpenPGP passphrases, two ways.
 *
 * In memory, for a few minutes after it was last used, so opening three
 * entries in a row asks once. Wiped when the screen locks.
 *
 * Sealed under the biometric key, optionally and per OpenPGP key, so that a
 * fingerprint can stand in for typing the passphrase from then on. The sealed
 * blob is useless without a fresh biometric on this phone, and Android
 * destroys the key behind it when a new fingerprint is enrolled -- at which
 * point the passphrase is simply asked for again.
 */
class PassphraseCache(
    private val sealedStore: SharedPreferences,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private class Remembered(
        val passphrase: CharArray,
        var lastUsed: Long,
    )

    private val memory = mutableMapOf<String, Remembered>()

    @Synchronized
    fun recall(
        fingerprint: String,
        ttlMillis: Long,
    ): CharArray? {
        val entry = memory[fingerprint] ?: return null
        val now = clock()
        if (now - entry.lastUsed > ttlMillis) {
            forget(fingerprint)
            return null
        }
        entry.lastUsed = now
        return entry.passphrase.copyOf()
    }

    @Synchronized
    fun remember(
        fingerprint: String,
        passphrase: CharArray,
    ) {
        memory.remove(fingerprint)?.passphrase?.fill('\u0000')
        memory[fingerprint] = Remembered(passphrase.copyOf(), clock())
    }

    @Synchronized
    fun forget(fingerprint: String) {
        memory.remove(fingerprint)?.passphrase?.fill('\u0000')
    }

    /** Drops everything held in memory. Sealed passphrases stay sealed. */
    @Synchronized
    fun forgetAll() {
        memory.values.forEach { it.passphrase.fill('\u0000') }
        memory.clear()
    }

    fun sealed(fingerprint: String): ByteArray? =
        sealedStore.getString(fingerprint, null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    fun hasSealed(fingerprint: String): Boolean = sealedStore.contains(fingerprint)

    /** [sealed] must come from the biometric key, after a successful prompt. */
    fun storeSealed(
        fingerprint: String,
        sealed: ByteArray,
    ) {
        sealedStore.edit { putString(fingerprint, Base64.encodeToString(sealed, Base64.NO_WRAP)) }
    }

    fun dropSealed(fingerprint: String) {
        sealedStore.edit { remove(fingerprint) }
    }

    fun dropAllSealed() {
        sealedStore.edit { clear() }
    }

    companion object {
        fun encode(passphrase: CharArray): ByteArray {
            val buffer = Charsets.UTF_8.encode(CharBuffer.wrap(passphrase))
            return ByteArray(buffer.remaining()).also { buffer.get(it) }
        }

        fun decode(bytes: ByteArray): CharArray {
            val buffer = Charsets.UTF_8.decode(ByteBuffer.wrap(bytes))
            return CharArray(buffer.remaining()).also { buffer.get(it) }.also { bytes.fill(0) }
        }
    }
}
