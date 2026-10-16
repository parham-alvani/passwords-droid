package me.parham1995.passwords.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM under a key that lives in the AndroidKeyStore and never leaves
 * the secure hardware.
 *
 * Two keys, for two jobs:
 *
 * - [AT_REST] needs no authentication. It wraps the imported OpenPGP keys on
 *   disk, so a copy of the app's files -- a backup, a forensic image -- is
 *   ciphertext without this particular phone's hardware.
 * - [BIOMETRIC] can only be used right after a strong biometric, for that one
 *   operation, and dies when a fingerprint is enrolled. It seals the OpenPGP
 *   passphrase, so a fingerprint can stand in for typing it.
 *
 * StrongBox (the Galaxy's Knox Vault) is used when the phone has one *and* a
 * round trip through it works. Password Store had to turn StrongBox off
 * because some firmware generates the key happily and then fails to use it;
 * testing the key once, right after creating it, finds that out before
 * anything has been sealed with a key that cannot open it again.
 */
class KeystoreBox internal constructor(
    private val alias: String,
    private val requireBiometric: Boolean,
    private val preferStrongBox: Boolean,
) : SecretBox {
    private val keyStore: KeyStore by lazy { KeyStore.getInstance(PROVIDER).apply { load(null) } }

    /** A cipher ready to seal, for handing to a BiometricPrompt as its CryptoObject. */
    fun sealingCipher(): Cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }

    /** A cipher ready to open [sealed], for handing to a BiometricPrompt as its CryptoObject. */
    fun openingCipher(sealed: ByteArray): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 1, IV_BYTES))
        }

    fun seal(
        cipher: Cipher,
        plaintext: ByteArray,
    ): ByteArray = byteArrayOf(VERSION) + cipher.iv + cipher.doFinal(plaintext)

    fun open(
        cipher: Cipher,
        sealed: ByteArray,
    ): ByteArray {
        require(sealed.isNotEmpty() && sealed[0] == VERSION) { "unknown sealed format" }
        return cipher.doFinal(sealed, 1 + IV_BYTES, sealed.size - 1 - IV_BYTES)
    }

    override fun seal(plaintext: ByteArray): ByteArray = seal(sealingCipher(), plaintext)

    override fun open(sealed: ByteArray): ByteArray = open(openingCipher(sealed), sealed)

    fun delete() {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    /** Whether the key ended up in StrongBox; shown on the security screen. */
    fun isStrongBox(): Boolean =
        runCatching {
            val key = key()
            val info =
                javax.crypto.SecretKeyFactory
                    .getInstance(key.algorithm, PROVIDER)
                    .getKeySpec(key, android.security.keystore.KeyInfo::class.java) as android.security.keystore.KeyInfo
            info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
        }.getOrDefault(false)

    @Synchronized
    private fun key(): SecretKey {
        (keyStore.getKey(alias, null) as SecretKey?)?.let { return it }
        if (preferStrongBox) {
            val strong =
                runCatching { generate(strongBox = true) }
                    .onFailure { if (it !is StrongBoxUnavailableException) Log.w(TAG, "StrongBox refused $alias", it) }
                    .getOrNull()
            // A biometric-bound key cannot be exercised without a prompt, so
            // only the at-rest key is checked; the biometric one never asks
            // for StrongBox (see the call sites).
            if (strong != null && (requireBiometric || worksForRoundTrip(strong))) return strong
            delete()
        }
        return generate(strongBox = false)
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec =
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .setIsStrongBoxBacked(strongBox)
                .apply {
                    if (requireBiometric) {
                        setUserAuthenticationRequired(true)
                        // 0 seconds: every use needs its own prompt.
                        setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                        setInvalidatedByBiometricEnrollment(true)
                    }
                    setUnlockedDeviceRequired(true)
                }.build()
        return KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            .apply { init(spec) }
            .generateKey()
    }

    private fun worksForRoundTrip(key: SecretKey): Boolean =
        runCatching {
            val probe = byteArrayOf(1, 2, 3, 4)
            val enc = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
            val sealed = enc.doFinal(probe)
            val dec =
                Cipher.getInstance(TRANSFORMATION).apply {
                    init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, enc.iv))
                }
            dec.doFinal(sealed).contentEquals(probe)
        }.onFailure { Log.w(TAG, "StrongBox key for $alias failed its round trip; using the TEE", it) }
            .getOrDefault(false)

    companion object {
        private const val TAG = "KeystoreBox"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val TAG_BITS = 128
        private const val IV_BYTES = 12
        private const val VERSION: Byte = 1

        private const val AT_REST = "passwords.at-rest"
        private const val BIOMETRIC = "passwords.biometric"

        fun atRest(): KeystoreBox = KeystoreBox(AT_REST, requireBiometric = false, preferStrongBox = true)

        /**
         * TEE, not StrongBox: per-use biometric keys in StrongBox are where
         * the firmware bugs Password Store hit live, and the TEE is still
         * hardware the passphrase cannot be pulled out of.
         */
        fun biometric(): KeystoreBox = KeystoreBox(BIOMETRIC, requireBiometric = true, preferStrongBox = false)
    }
}
