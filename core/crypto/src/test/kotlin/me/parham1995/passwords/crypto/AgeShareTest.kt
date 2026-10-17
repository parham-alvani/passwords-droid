package me.parham1995.passwords.crypto

import com.google.common.truth.Truth.assertThat
import kage.Age
import kage.crypto.x25519.X25519Identity
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class AgeShareTest {
    private val secret = "hunter2\nlogin: me\nürl: x\n".toByteArray()

    @Test
    fun `round trip`() {
        val sealed = AgeShare.seal(secret)

        val identity = X25519Identity.decode(String(sealed.identity))
        val out = ByteArrayOutputStream()
        Age.decryptStream(listOf(identity), sealed.ciphertext.inputStream(), out)

        assertThat(out.toByteArray()).isEqualTo(secret)
    }

    @Test
    fun `binary age v1, not armored`() {
        val header = String(AgeShare.seal(secret).ciphertext, 0, HEADER.length)
        assertThat(header).isEqualTo(HEADER)
    }

    /** The share page's own pattern, from its app.js. */
    @Test
    fun `the key has the shape the page accepts`() {
        val key = String(AgeShare.seal(secret).identity)
        assertThat(key).matches("AGE-SECRET-KEY-1[0-9A-Z]{58}")
    }

    @Test
    fun `every seal has its own key`() {
        assertThat(String(AgeShare.seal(secret).identity)).isNotEqualTo(String(AgeShare.seal(secret).identity))
    }

    @Test
    fun `ids are 32 lower-case hex characters`() {
        val ids = List(50) { AgeShare.newId() }
        ids.forEach {
            assertThat(it).matches("[0-9a-f]{32}")
            assertThat(AgeShare.isId(it)).isTrue()
        }
        assertThat(ids.toSet()).hasSize(ids.size)
        assertThat(AgeShare.isId("../../etc")).isFalse()
        assertThat(AgeShare.isId(ids.first().uppercase())).isFalse()
    }

    /** What the page does, done by the reference implementation. Skipped without `age` on the PATH. */
    @Test
    fun `the age command decrypts it`() {
        val age = which("age")
        assumeTrue("age is not on the PATH", age != null)
        val dir = Files.createTempDirectory("age-share").toFile()
        try {
            val sealed = AgeShare.seal(secret)
            val file = File(dir, "share.age").apply { writeBytes(sealed.ciphertext) }
            val key = File(dir, "key.txt").apply { writeText(String(sealed.identity) + "\n") }

            val process =
                ProcessBuilder(age!!, "-d", "-i", key.path, file.path)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start()
            val plain = process.inputStream.readBytes()
            assertThat(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()

            assertThat(process.exitValue()).isEqualTo(0)
            assertThat(plain).isEqualTo(secret)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun which(command: String): String? =
        (System.getenv("PATH").orEmpty().split(File.pathSeparator) + "/opt/homebrew/bin" + "/usr/local/bin")
            .map { File(it, command) }
            .firstOrNull { it.canExecute() }
            ?.path

    private companion object {
        const val HEADER = "age-encryption.org/v1\n"
        const val TIMEOUT_SECONDS = 30L
    }
}
