package me.parham1995.passwords.crypto

import com.google.common.truth.Truth.assertThat
import org.bouncycastle.openpgp.api.OpenPGPKey
import org.junit.Assert.assertThrows
import org.junit.Test
import org.pgpainless.PGPainless

class PgpCryptoTest {
    private val crypto = PgpCrypto()
    private val api = PGPainless.getInstance()

    private fun key(
        uid: String,
        passphrase: String?,
    ): OpenPGPKey = api.generateKey().modernKeyRing(uid, passphrase)

    private val alice = key("Alice <alice@example.com>", "alice-pass")
    private val bob = key("Bob <bob@example.com>", null)

    @Test
    fun `round trip for several recipients`() {
        val cipher =
            crypto.encrypt(
                "hunter2\nlogin: me\n".toByteArray(),
                listOf(alice.toCertificate(), bob.toCertificate()),
            )

        val byAlice = crypto.decrypt(cipher, listOf(alice), "alice-pass".toCharArray())
        val byBob = crypto.decrypt(cipher, listOf(bob), null)

        assertThat(String(byAlice)).isEqualTo("hunter2\nlogin: me\n")
        assertThat(String(byBob)).isEqualTo("hunter2\nlogin: me\n")
    }

    @Test
    fun `a wrong passphrase is reported as such`() {
        val cipher = crypto.encrypt("x".toByteArray(), listOf(alice.toCertificate()))
        assertThrows(WrongPassphrase::class.java) {
            crypto.decrypt(cipher, listOf(alice), "nope".toCharArray())
        }
    }

    @Test
    fun `a file for someone else is not ours to open`() {
        val cipher = crypto.encrypt("x".toByteArray(), listOf(bob.toCertificate()))
        assertThrows(NoSecretKey::class.java) {
            crypto.decrypt(cipher, listOf(alice), "alice-pass".toCharArray())
        }
    }

    @Test
    fun `reads armored keys and knows which need a passphrase`() {
        val armored =
            alice.toAsciiArmoredString().toByteArray() + bob.toCertificate().toAsciiArmoredString().toByteArray()
        val read = crypto.readKeys(armored)

        assertThat(read).hasSize(2)
        assertThat(read.first().isSecretKey).isTrue()
        val secret = read.first() as OpenPGPKey
        assertThat(crypto.needsPassphrase(secret)).isTrue()
        assertThat(crypto.isPassphraseCorrect(secret, "alice-pass".toCharArray())).isTrue()
        assertThat(crypto.isPassphraseCorrect(secret, "wrong".toCharArray())).isFalse()
    }

    @Test
    fun `opens a symmetric backup`() {
        val backup = api.generateMessage()
        val out = java.io.ByteArrayOutputStream()
        val options =
            org.pgpainless.encryption_signing.EncryptionOptions
                .encryptCommunications(api)
                .addMessagePassphrase(
                    org.pgpainless.util.Passphrase
                        .fromPassword("1234-5678"),
                )
        backup
            .onOutputStream(out)
            .withOptions(
                org.pgpainless.encryption_signing.ProducerOptions
                    .encrypt(options),
            ).use { it.write(alice.toAsciiArmoredString().toByteArray()) }

        val restored = crypto.readKeys(crypto.decryptSymmetric(out.toByteArray(), "1234-5678".toCharArray()))
        assertThat(restored.single().keyIdentifier).isEqualTo(alice.keyIdentifier)
    }

    @Test
    fun `gpg-id lines match by long id, fingerprint and email`() {
        val cert = alice.toCertificate()
        val fingerprint = Hex.of(cert.primaryKey.pgpPublicKey.fingerprint)
        val subkeyId =
            Hex
                .of(
                    cert.keys
                        .last()
                        .pgpPublicKey.fingerprint,
                ).takeLast(16)

        assertThat(PgpCrypto.matches(cert, fingerprint)).isTrue()
        assertThat(PgpCrypto.matches(cert, "0x" + fingerprint.takeLast(16))).isTrue()
        assertThat(PgpCrypto.matches(cert, "0x$subkeyId")).isTrue()
        assertThat(PgpCrypto.matches(cert, "alice@example.com")).isTrue()
        assertThat(PgpCrypto.matches(cert, "bob@example.com")).isFalse()
        assertThat(PgpCrypto.matches(cert, "0xDEADBEEFDEADBEEF")).isFalse()
    }
}
