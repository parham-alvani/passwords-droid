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
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Date

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
         * writes full fingerprints (older versions `0x`-prefixed long key
         * ids); `pass` accepts fingerprints, short or long ids of the primary
         * key or any subkey, and email addresses. All of them are recognised,
         * the way gpg reads them:
         *
         * - `<alice@example.com>` and a bare `alice@example.com` match a user
         *   id whose email is exactly that, never `malice@example.com`;
         * - `@example.com` matches part of an email;
         * - hex, with or without `0x`, spaces or gpg's trailing `!`, is a
         *   fingerprint or key id;
         * - anything else is part of a user id.
         */
        fun matches(
            certificate: OpenPGPCertificate,
            id: String,
        ): Boolean {
            val trimmed = id.substringBefore('#').trim()
            if (trimmed.isEmpty()) return false
            val hex = canonicalHex(trimmed)
            if (hex != null) {
                return certificate.keys.any { key ->
                    val fingerprint = Hex.of(key.pgpPublicKey.fingerprint)
                    fingerprint == hex || fingerprint.endsWith(hex)
                }
            }
            val userIds = certificate.primaryKey.userIDs.map { it.userId }
            return when {
                trimmed.startsWith('<') && trimmed.endsWith('>') -> {
                    val wanted = trimmed.removeSurrounding("<", ">").trim().lowercase()
                    userIds.any { email(it) == wanted }
                }
                trimmed.startsWith('@') -> {
                    val wanted = trimmed.lowercase()
                    userIds.any { uid -> email(uid)?.contains(wanted) == true }
                }
                '@' in trimmed && ' ' !in trimmed -> userIds.any { email(it) == trimmed.lowercase() }
                else -> userIds.any { trimmed.lowercase() in it.lowercase() }
            }
        }

        /**
         * [id] as upper-case hex without `0x`, spaces or a trailing `!`, or
         * null when it is not a fingerprint or key id at all.
         */
        fun canonicalHex(id: String): String? {
            val stripped =
                id
                    .trim()
                    .trimEnd('!', ' ')
                    .replace(" ", "")
                    .removePrefix("0x")
                    .removePrefix("0X")
                    .uppercase()
            return stripped.takeIf { it.length >= SHORT_ID_LENGTH && it.all { c -> c in HEX_DIGITS } }
        }

        /** The full fingerprint, upper-case hex, the way gopass writes new `.gpg-id` lines. */
        fun fingerprint(certificate: OpenPGPCertificate): String =
            Hex.of(certificate.primaryKey.pgpPublicKey.fingerprint)

        /**
         * Which of [candidates] to encrypt for [id]. A match that is revoked,
         * expired or has no encryption subkey is passed over for one that is
         * usable; when every match is unusable the answer says why, so the
         * caller can name the key instead of failing inside PGPainless.
         */
        fun resolve(
            candidates: List<OpenPGPCertificate>,
            id: String,
            now: Date = Date(),
        ): Recipient {
            val matching = candidates.filter { matches(it, id) }
            if (matching.isEmpty()) return Recipient.Missing
            matching.firstOrNull { usable(it, now) }?.let { return Recipient.Found(it) }
            val first = matching.first()
            return Recipient.Unusable(first, whyUnusable(first, now))
        }

        private fun usable(
            certificate: OpenPGPCertificate,
            now: Date,
        ): Boolean = runCatching { certificate.getEncryptionKeys(now).isNotEmpty() }.getOrDefault(false)

        private fun whyUnusable(
            certificate: OpenPGPCertificate,
            now: Date,
        ): String {
            val revoked = runCatching { certificate.getRevocation(now) != null }.getOrDefault(false)
            if (revoked) return "revoked"
            val expires = runCatching { certificate.getExpirationTime(now) }.getOrNull()
            if (expires != null && !expires.after(now)) return "expired on " + DATE.format(expires.toInstant())
            return "has no valid encryption subkey"
        }

        private fun email(userId: String): String? {
            val bracketed = EMAIL_IN_BRACKETS.find(userId)?.groupValues?.get(1)
            val raw = bracketed ?: userId.trim().takeIf { '@' in it && ' ' !in it }
            return raw?.trim()?.lowercase()
        }

        private const val SHORT_ID_LENGTH = 8
        private const val HEX_DIGITS = "0123456789ABCDEF"
        private val EMAIL_IN_BRACKETS = Regex("<([^>]*)>")
        private val DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC)
    }

    /** The outcome of [resolve]. */
    sealed interface Recipient {
        data class Found(
            val certificate: OpenPGPCertificate,
        ) : Recipient

        data class Unusable(
            val certificate: OpenPGPCertificate,
            val reason: String,
        ) : Recipient

        data object Missing : Recipient
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
