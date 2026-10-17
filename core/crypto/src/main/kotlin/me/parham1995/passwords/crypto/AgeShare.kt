package me.parham1995.passwords.crypto

import kage.Age
import kage.crypto.x25519.X25519Identity
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/**
 * One-off share links: a secret encrypted with age to a key made for it alone.
 *
 * The ciphertext is standard age v1 in binary form, X25519 only -- what
 * `age -r` writes and what typage decrypts in a browser. The identity
 * travels in the link's fragment, so whoever holds the link can read the
 * secret and nobody else can, including the server that hosts the file.
 * Never a passphrase (`age -p`): the files are public, and a passphrase
 * could be guessed offline.
 */
object AgeShare {
    class Sealed(
        val ciphertext: ByteArray,
        /** `AGE-SECRET-KEY-1…`, upper case, as `age-keygen` prints it. Wipe it once the link is made. */
        val identity: CharArray,
    )

    private val random = SecureRandom()

    /** Encrypts [plain] to a fresh X25519 identity; the caller wipes [plain]. */
    fun seal(plain: ByteArray): Sealed {
        val identity = X25519Identity.new()
        val out = ByteArrayOutputStream()
        Age.encryptStream(listOf(identity.recipient()), out, false).use { it.write(plain) }
        // kage hands the key out only as a String; it is copied out at once
        // and the String left for the collector, which is the best a JVM allows.
        return Sealed(out.toByteArray(), identity.encodeToString().uppercase().toCharArray())
    }

    /** A share's name: 16 random bytes in lower-case hex, as `openssl rand -hex 16`. */
    fun newId(): String {
        val bytes = ByteArray(ID_BYTES).also(random::nextBytes)
        return bytes.joinToString("") { "%02x".format(it) }.also { bytes.fill(0) }
    }

    /** Whether [id] has the shape [newId] makes, so it can be put into a path. */
    fun isId(id: String): Boolean = id.length == ID_BYTES * 2 && id.all { it in '0'..'9' || it in 'a'..'f' }

    private const val ID_BYTES = 16
}
