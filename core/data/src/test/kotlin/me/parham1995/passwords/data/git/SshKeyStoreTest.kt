package me.parham1995.passwords.data.git

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import me.parham1995.passwords.data.security.SecretBox
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/** Under Robolectric for the same reason as [SshKeyGeneratorTest]: sshd's EdDSA registry. */
@RunWith(RobolectricTestRunner::class)
class SshKeyStoreTest {
    private val root = Files.createTempDirectory("ssh").toFile()
    private val sshDir = File(root, "ssh")

    /** No AndroidKeyStore here; flipping every bit is enough to show nothing is on disk in the clear. */
    private val box =
        object : SecretBox {
            override fun seal(plaintext: ByteArray) = ByteArray(plaintext.size) { plaintext[it].toInt().inv().toByte() }

            override fun open(sealed: ByteArray) = seal(sealed)
        }

    private fun store() = SshKeyStore(sshDir, box, File(root, "git"))

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun anyFileContains(text: String): Boolean =
        sshDir.walk().filter { it.isFile }.any { String(it.readBytes(), Charsets.ISO_8859_1).contains(text) }

    @Test
    fun `a generated key is sealed on disk and handed over from memory`() =
        runTest {
            val line = store().generate()

            assertThat(anyFileContains("OPENSSH PRIVATE KEY")).isFalse()
            // A fresh instance -- a new process -- unseals it again.
            val fresh = store()
            assertThat(fresh.exists()).isTrue()
            val pair = fresh.keyPair()!!
            assertThat(PublicKeyEntry.toString(pair.public)).isEqualTo(line.substringBeforeLast(' '))
        }

    @Test
    fun `a plaintext key from an older version is sealed and deleted on first use`() {
        val pair = SshKeyGenerator.generate().pair
        sshDir.mkdirs()
        File(sshDir, "id_store").outputStream().use {
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(pair, "old", null, it)
        }
        File(sshDir, "id_store.pub").writeText(PublicKeyEntry.toString(pair.public) + " old\n")
        val keys = store()
        assertThat(keys.exists()).isTrue()

        val loaded = keys.keyPair()!!

        assertThat(loaded.public).isEqualTo(pair.public)
        assertThat(File(sshDir, "id_store").exists()).isFalse()
        assertThat(anyFileContains("OPENSSH PRIVATE KEY")).isFalse()
        assertThat(store().keyPair()!!.public).isEqualTo(pair.public)
    }

    @Test
    fun `deleting forgets the key`() =
        runTest {
            val keys = store()
            keys.generate()
            keys.delete()
            assertThat(keys.exists()).isFalse()
            assertThat(keys.keyPair()).isNull()
        }
}
