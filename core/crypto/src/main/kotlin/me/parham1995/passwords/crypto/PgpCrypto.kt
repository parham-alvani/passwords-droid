package me.parham1995.passwords.crypto

import org.bouncycastle.openpgp.api.OpenPGPCertificate
import org.bouncycastle.openpgp.api.OpenPGPKey
import org.bouncycastle.openpgp.api.OpenPGPKeyReader
import org.bouncycastle.util.io.Streams
import org.pgpainless.PGPainless
import org.pgpainless.decryption_verification.ConsumerOptions
import org.pgpainless.encryption_signing.EncryptionOptions
import org.pgpainless.encryption_signing.ProducerOptions
import org.pgpainless.exception.MissingDecryptionMethodException
import org.pgpainless.exception.WrongPassphraseException
import org.pgpainless.key.protection.SecretKeyRingProtector
import org.pgpainless.util.Passphrase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * OpenPGP for a password store, on PGPainless.
 *
 * Kept to what `pass` itself does: decrypt a file with a secret key, encrypt
 * a file for every recipient in `.gpg-id`, and read keys in whatever shape
 * they arrive -- an armored export, a binary keyring, or an OpenKeychain or
 * `gpg --symmetric` backup locked with a code.
 *
 * Nothing here keeps state or touches Android. Secrets are passed as
 * [CharArray] so callers can wipe them; PGPainless copies what it needs.
 */
class PgpCrypto {
    private val api = PGPainless.getInstance()

    /** Every key or certificate in [bytes], secret keys first. Empty when there are none. */
    fun readKeys(bytes: ByteArray): List<OpenPGPCertificate> =
        runCatching {
            OpenPGPKeyReader()
                .parseKeysOrCertificates(ByteArrayInputStream(bytes))
                .sortedByDescending { it.isSecretKey }
        }.getOrDefault(emptyList())

    /**
     * Opens a backup made with a passphrase rather than a key -- what
     * OpenKeychain's "Backup key" and `gpg --symmetric` both produce -- and
     * returns what was inside.
     */
    fun decryptSymmetric(
        ciphertext: ByteArray,
        passphrase: CharArray,
    ): ByteArray = process(ciphertext) { it.addMessagePassphrase(Passphrase(passphrase)) }

    /** Decrypts a `.gpg` file with whichever of [keys] it was encrypted for. */
    fun decrypt(
        ciphertext: ByteArray,
        keys: List<OpenPGPKey>,
        passphrase: CharArray?,
    ): ByteArray {
        if (keys.isEmpty()) throw NoSecretKey("no secret key has been imported")
        val protector = SecretKeyRingProtector.unlockAnyKeyWith(Passphrase(passphrase))
        return process(ciphertext) { options -> keys.forEach { options.addDecryptionKey(it, protector) } }
    }

    private fun process(
        ciphertext: ByteArray,
        configure: (ConsumerOptions) -> Unit,
    ): ByteArray =
        try {
            val options = ConsumerOptions.get(api).also(configure)
            val out = ByteArrayOutputStream()
            api
                .processMessage()
                .onInputStream(ByteArrayInputStream(ciphertext))
                .withOptions(options)
                .use { Streams.pipeAll(it, out) }
            out.toByteArray()
        } catch (e: WrongPassphraseException) {
            throw WrongPassphrase(e)
        } catch (e: MissingDecryptionMethodException) {
            throw NoSecretKey("none of the imported keys can open this file", e)
        }

    /**
     * Encrypts [plaintext] for every certificate in [recipients], binary as
     * `pass` and gopass write it. Every encryption-capable subkey is used, so
     * a recipient with one subkey per device can open it on any of them.
     */
    fun encrypt(
        plaintext: ByteArray,
        recipients: List<OpenPGPCertificate>,
    ): ByteArray {
        require(recipients.isNotEmpty()) { "no recipients to encrypt for" }
        val encryption = EncryptionOptions.encryptCommunications(api)
        recipients.forEach { encryption.addRecipient(it, EncryptionOptions.encryptToAllCapableSubkeys()) }
        val out = ByteArrayOutputStream()
        api
            .generateMessage()
            .onOutputStream(out)
            .withOptions(ProducerOptions.encrypt(encryption).setAsciiArmor(false))
            .use { Streams.pipeAll(ByteArrayInputStream(plaintext), it) }
        return out.toByteArray()
    }

    /** True when at least one decryption subkey of [key] is locked with a passphrase. */
    fun needsPassphrase(key: OpenPGPKey): Boolean =
        key.secretKeys.values.any { it.isEncryptionKey && !it.pgpSecretKey.isPrivateKeyEmpty && it.isLocked }

    /** Checks [passphrase] against a decryption subkey without decrypting anything. */
    fun isPassphraseCorrect(
        key: OpenPGPKey,
        passphrase: CharArray?,
    ): Boolean =
        key.secretKeys.values
            .filter { it.isEncryptionKey && !it.pgpSecretKey.isPrivateKeyEmpty }
            .any { it.isPassphraseCorrect(passphrase) }

    companion object {
        /**
         * Whether [id] -- a line of a `.gpg-id` -- names [certificate]. gopass
         * writes `0x`-prefixed long key ids; `pass` accepts fingerprints,
         * short or long ids of the primary key or any subkey, and email
         * addresses. All of them are recognised.
         */
        fun matches(
            certificate: OpenPGPCertificate,
            id: String,
        ): Boolean {
            val wanted =
                id
                    .trim()
                    .removePrefix("0x")
                    .removePrefix("0X")
                    .uppercase()
            if (wanted.isEmpty()) return false
            if ('@' in id || ' ' in id) {
                val needle = id.trim().removeSurrounding("<", ">").lowercase()
                return certificate.primaryKey.userIDs.any { needle in it.userId.lowercase() }
            }
            return certificate.keys.any { key ->
                val fingerprint = Hex.of(key.pgpPublicKey.fingerprint)
                fingerprint == wanted || fingerprint.endsWith(wanted) && wanted.length >= SHORT_ID_LENGTH
            }
        }

        private const val SHORT_ID_LENGTH = 8
    }
}

internal object Hex {
    fun of(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it) }
}

sealed class PgpException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class WrongPassphrase(
    cause: Throwable? = null,
) : PgpException("wrong passphrase", cause)

class NoSecretKey(
    message: String,
    cause: Throwable? = null,
) : PgpException(message, cause)
