package me.parham1995.passwords.unlock

import android.security.keystore.KeyPermanentlyInvalidatedException
import me.parham1995.passwords.data.PassphraseCache
import me.parham1995.passwords.data.PasswordRepository
import me.parham1995.passwords.data.SettingsStore
import me.parham1995.passwords.data.fingerprintHex
import me.parham1995.passwords.data.label
import me.parham1995.passwords.data.security.KeystoreBox
import org.bouncycastle.openpgp.api.OpenPGPKey
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides how to get the passphrase for an OpenPGP key, cheapest first:
 * none needed, still remembered, a fingerprint, or typing it.
 */
@Singleton
class Unlocker
    @Inject
    constructor(
        private val repository: PasswordRepository,
        private val cache: PassphraseCache,
        private val box: KeystoreBox,
        private val settings: SettingsStore,
    ) {
        sealed interface Need {
            /** Ready: [passphrase] is null for a key without one. */
            class Ready(
                val passphrase: CharArray?,
            ) : Need

            class Biometric(
                val key: OpenPGPKey,
                val cipher: Cipher,
            ) : Need

            data class Typed(
                val key: OpenPGPKey,
                val label: String,
            ) : Need
        }

        suspend fun prepare(key: OpenPGPKey): Need {
            if (!repository.needsPassphrase(key)) return Need.Ready(null)
            val fingerprint = key.fingerprintHex()
            val config = settings.current()
            cache.recall(fingerprint, config.passphraseMinutes * MILLIS_PER_MINUTE)?.let { return Need.Ready(it) }
            val sealed = cache.sealed(fingerprint)
            if (config.biometricUnlock && sealed != null) {
                try {
                    return Need.Biometric(key, box.openingCipher(sealed))
                } catch (_: KeyPermanentlyInvalidatedException) {
                    // A fingerprint was enrolled since; Android destroyed the
                    // key, and the blob it sealed is now noise.
                    cache.dropAllSealed()
                    box.delete()
                }
            }
            return Need.Typed(key, key.label())
        }

        /** After a successful prompt on [Need.Biometric.cipher]. */
        fun fromBiometric(
            key: OpenPGPKey,
            cipher: Cipher,
        ): CharArray? {
            val fingerprint = key.fingerprintHex()
            val sealed = cache.sealed(fingerprint) ?: return null
            val passphrase = PassphraseCache.decode(box.open(cipher, sealed))
            cache.remember(fingerprint, passphrase)
            return passphrase
        }

        fun check(
            key: OpenPGPKey,
            passphrase: CharArray,
        ): Boolean = repository.isPassphraseCorrect(key, passphrase)

        fun remember(
            key: OpenPGPKey,
            passphrase: CharArray,
        ) = cache.remember(key.fingerprintHex(), passphrase)

        /** A cipher to show a prompt for, when the user asked to use a fingerprint from now on. */
        fun sealingCipher(): Cipher? =
            runCatching { box.sealingCipher() }
                .recoverCatching {
                    // An invalidated key cannot even start; make a new one.
                    box.delete()
                    box.sealingCipher()
                }.getOrNull()

        fun seal(
            key: OpenPGPKey,
            cipher: Cipher,
            passphrase: CharArray,
        ) {
            cache.storeSealed(key.fingerprintHex(), box.seal(cipher, PassphraseCache.encode(passphrase)))
        }

        private companion object {
            const val MILLIS_PER_MINUTE = 60_000L
        }
    }
