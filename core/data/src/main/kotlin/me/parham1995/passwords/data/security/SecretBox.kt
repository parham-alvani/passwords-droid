package me.parham1995.passwords.data.security

/**
 * Seals bytes so that only this device can open them.
 *
 * An interface so the code that stores sealed things can be tested on the
 * JVM, where there is no AndroidKeyStore; the real one is [KeystoreBox].
 */
interface SecretBox {
    fun seal(plaintext: ByteArray): ByteArray

    fun open(sealed: ByteArray): ByteArray
}
