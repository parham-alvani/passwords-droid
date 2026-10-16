package me.parham1995.passwords.data

import me.parham1995.passwords.crypto.PgpCrypto
import me.parham1995.passwords.data.security.SecretBox
import org.bouncycastle.openpgp.api.OpenPGPCertificate
import org.bouncycastle.openpgp.api.OpenPGPKey
import java.io.File

/**
 * The OpenPGP keys imported into the app.
 *
 * Kept in one file, sealed by the device's at-rest key on top of whatever
 * passphrase the keys already carry. Secret keys are what decrypt the store;
 * public certificates are what it is encrypted for, and gopass's exported
 * `.public-keys` add the other recipients without importing them here.
 */
class KeyRing(
    private val file: File,
    private val box: SecretBox,
    private val crypto: PgpCrypto,
) {
    @Volatile
    private var cached: List<OpenPGPCertificate>? = null

    @Synchronized
    fun all(): List<OpenPGPCertificate> =
        cached ?: (if (file.isFile) crypto.readKeys(box.open(file.readBytes())) else emptyList()).also { cached = it }

    fun secretKeys(): List<OpenPGPKey> = all().filterIsInstance<OpenPGPKey>()

    /**
     * Adds every key in [bytes]. A key already present is replaced, so
     * importing a secret key over its own public certificate upgrades it.
     * Returns what was added.
     */
    @Synchronized
    fun import(bytes: ByteArray): List<OpenPGPCertificate> {
        val incoming = crypto.readKeys(bytes)
        require(incoming.isNotEmpty()) { "no OpenPGP key found in that file" }
        val incomingIds = incoming.map { it.keyIdentifier }.toSet()
        // A secret key wins over a public copy of the same key, wherever it came from.
        val merged =
            (all().filterNot { it.keyIdentifier in incomingIds } + incoming)
                .groupBy { it.keyIdentifier }
                .map { (_, copies) -> copies.firstOrNull { it.isSecretKey } ?: copies.first() }
        write(merged)
        return incoming
    }

    @Synchronized
    fun remove(fingerprint: String) {
        write(all().filterNot { it.fingerprintHex() == fingerprint })
    }

    private fun write(keys: List<OpenPGPCertificate>) {
        file.parentFile?.mkdirs()
        val armored = keys.joinToString("\n") { it.toAsciiArmoredString() }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(box.seal(armored.toByteArray()))
        check(tmp.renameTo(file)) { "could not replace ${file.name}" }
        cached = keys
    }
}

fun OpenPGPCertificate.fingerprintHex(): String =
    primaryKey.pgpPublicKey.fingerprint.joinToString("") {
        "%02X".format(it)
    }

/** `Name <email>` of the primary user id, or the fingerprint when there is none. */
fun OpenPGPCertificate.label(): String = primaryKey.userIDs.firstOrNull()?.userId ?: fingerprintHex()
