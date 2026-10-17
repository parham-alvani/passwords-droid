package me.parham1995.passwords.crypto

import com.google.common.truth.Truth.assertThat
import org.bouncycastle.bcpg.KeyIdentifier
import org.bouncycastle.openpgp.PGPEncryptedDataList
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData
import org.bouncycastle.openpgp.api.OpenPGPCertificate
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory
import org.junit.Test
import org.pgpainless.PGPainless
import org.pgpainless.algorithm.KeyFlag
import org.pgpainless.key.generation.KeySpec
import org.pgpainless.key.generation.type.KeyType
import org.pgpainless.key.generation.type.eddsa_legacy.EdDSALegacyCurve
import org.pgpainless.key.generation.type.xdh_legacy.XDHLegacySpec
import java.util.Date

class RecipientMatchingTest {
    private val crypto = PgpCrypto()
    private val api = PGPainless.getInstance()

    private fun cert(uid: String): OpenPGPCertificate = api.generateKey().modernKeyRing(uid).toCertificate()

    private val alice = cert("Alice <alice@example.com>")
    private val malice = cert("Malice <malice@example.com>")

    @Test
    fun `an email in brackets or bare matches exactly, never as a substring`() {
        assertThat(PgpCrypto.matches(alice, "<alice@example.com>")).isTrue()
        assertThat(PgpCrypto.matches(malice, "<alice@example.com>")).isFalse()
        assertThat(PgpCrypto.matches(malice, "alice@example.com")).isFalse()
        assertThat(PgpCrypto.matches(alice, "ALICE@example.com")).isTrue()
        assertThat(PgpCrypto.matches(malice, "@example.com")).isTrue()
    }

    @Test
    fun `fingerprints match with spaces, 0x and a trailing bang`() {
        val fpr = PgpCrypto.fingerprint(alice)
        val spaced = fpr.chunked(4).joinToString(" ")
        assertThat(PgpCrypto.matches(alice, spaced)).isTrue()
        assertThat(PgpCrypto.matches(alice, "0x${fpr.takeLast(16)}!")).isTrue()
        assertThat(PgpCrypto.matches(alice, "$fpr! ")).isTrue()
        assertThat(PgpCrypto.matches(alice, fpr.lowercase())).isTrue()
        assertThat(PgpCrypto.matches(malice, fpr)).isFalse()
        assertThat(PgpCrypto.canonicalHex("0x${fpr.lowercase()}!")).isEqualTo(fpr)
    }

    @Test
    fun `an expired key is reported as such, and a valid match is preferred`() {
        // PGPainless will not make a key that is already expired, so this
        // one expires tomorrow and is looked at from next week.
        val expired =
            api
                .buildKey()
                .setPrimaryKey(
                    KeySpec.getBuilder(KeyType.EDDSA_LEGACY(EdDSALegacyCurve._Ed25519), KeyFlag.CERTIFY_OTHER),
                ).addSubkey(KeySpec.getBuilder(KeyType.XDH_LEGACY(XDHLegacySpec._X25519), KeyFlag.ENCRYPT_COMMS))
                .addUserId("Old <old@example.com>")
                .setExpirationDate(Date(System.currentTimeMillis() + DAY))
                .build()
                .toCertificate()
        val nextWeek = Date(System.currentTimeMillis() + 7 * DAY)

        val alone = PgpCrypto.resolve(listOf(expired), "old@example.com", nextWeek)
        assertThat(alone).isInstanceOf(PgpCrypto.Recipient.Unusable::class.java)
        assertThat((alone as PgpCrypto.Recipient.Unusable).reason).startsWith("expired")

        val fresh = cert("New <old@example.com>")
        val both = PgpCrypto.resolve(listOf(expired, fresh), "old@example.com", nextWeek)
        assertThat((both as PgpCrypto.Recipient.Found).certificate.keyIdentifier).isEqualTo(fresh.keyIdentifier)
        assertThat(PgpCrypto.resolve(listOf(fresh), "nobody@example.com")).isEqualTo(PgpCrypto.Recipient.Missing)
    }

    @Test
    fun `a certificate with two encryption subkeys is encrypted to both`() {
        val key =
            api
                .buildKey()
                .setPrimaryKey(
                    KeySpec.getBuilder(KeyType.EDDSA_LEGACY(EdDSALegacyCurve._Ed25519), KeyFlag.CERTIFY_OTHER),
                ).addSubkey(KeySpec.getBuilder(KeyType.XDH_LEGACY(XDHLegacySpec._X25519), KeyFlag.ENCRYPT_COMMS))
                .addSubkey(
                    KeySpec.getBuilder(
                        KeyType.XDH_LEGACY(XDHLegacySpec._X25519),
                        KeyFlag.ENCRYPT_COMMS,
                        KeyFlag.ENCRYPT_STORAGE,
                    ),
                ).addUserId("Two <two@example.com>")
                .build()
        val subkeys = key.toCertificate().encryptionKeys.map { it.keyIdentifier }
        assertThat(subkeys).hasSize(2)

        val cipher = crypto.encrypt("x".toByteArray(), listOf(key.toCertificate()))

        assertThat(recipientsOf(cipher)).containsExactlyElementsIn(subkeys)
        assertThat(String(crypto.decrypt(cipher, listOf(key), null))).isEqualTo("x")
    }

    private fun recipientsOf(cipher: ByteArray): Set<KeyIdentifier> {
        val factory = BcPGPObjectFactory(cipher)
        var next = factory.nextObject()
        while (next != null) {
            if (next is PGPEncryptedDataList) {
                return next.encryptedDataObjects
                    .asSequence()
                    .filterIsInstance<PGPPublicKeyEncryptedData>()
                    .map { it.keyIdentifier }
                    .toSet()
            }
            next = factory.nextObject()
        }
        return emptySet()
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
