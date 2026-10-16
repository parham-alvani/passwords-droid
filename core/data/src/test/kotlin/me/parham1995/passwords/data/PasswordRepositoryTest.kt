package me.parham1995.passwords.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import me.parham1995.passwords.crypto.PgpCrypto
import me.parham1995.passwords.crypto.WrongPassphrase
import me.parham1995.passwords.data.git.StoreGit
import me.parham1995.passwords.data.security.SecretBox
import me.parham1995.passwords.store.PassEntry
import me.parham1995.passwords.store.PasswordStore
import org.eclipse.jgit.api.Git
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.pgpainless.PGPainless
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/**
 * The whole write path against a repository on disk: clone, decrypt what the
 * desktop wrote, save from the phone, and find the commit on the other side.
 */
@RunWith(RobolectricTestRunner::class)
class PasswordRepositoryTest {
    private lateinit var root: File
    private lateinit var origin: File
    private lateinit var workTree: File
    private val crypto = PgpCrypto()
    private val api = PGPainless.getInstance()
    private val me = api.generateKey().modernKeyRing("Me <me@example.com>", "pass")
    private val friend = api.generateKey().modernKeyRing("Friend <friend@example.com>", null)

    /** No AndroidKeyStore on the JVM; sealing is the identity here. */
    private val clearBox =
        object : SecretBox {
            override fun seal(plaintext: ByteArray) = plaintext

            override fun open(sealed: ByteArray) = sealed
        }

    @Before
    fun setUp() {
        root = Files.createTempDirectory("repo").toFile()
        origin = File(root, "origin").apply { mkdirs() }
        workTree = File(root, "work")
        Git.init().setDirectory(origin).setInitialBranch("main").call().use { git ->
            File(origin, ".gpg-id").writeText("0x" + me.fingerprintHex().takeLast(16) + "\n")
            File(
                origin,
                "shared/.gpg-id",
            ).apply { parentFile?.mkdirs() }.writeText("me@example.com\nfriend@example.com\n")
            File(origin, ".public-keys/friend")
                .apply {
                    parentFile?.mkdirs()
                }.writeText(friend.toCertificate().toAsciiArmoredString())
            File(origin, "github.com/parham.gpg")
                .apply { parentFile?.mkdirs() }
                .writeBytes(crypto.encrypt("hunter2\nlogin: parham\n".toByteArray(), listOf(me.toCertificate())))
            git.add().addFilepattern(".").call()
            git
                .commit()
                .setMessage("seed")
                .setAuthor("Desk", "desk@example.com")
                .setSign(false)
                .call()
        }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun repository(): PasswordRepository {
        val ring =
            KeyRing(
                File(root, "keyring"),
                clearBox,
                crypto,
            ).apply { import(me.toAsciiArmoredString().toByteArray()) }
        val git = StoreGit(workTree, origin.toURI().toString(), "main", keys = null, configDir = File(root, "config"))
        return PasswordRepository(PasswordStore(workTree), ring, crypto, {
            git
        }, { Settings(authorName = "Phone", authorEmail = "p@x") })
    }

    @Test
    fun `clones, opens, saves and pushes`() =
        runTest {
            val repo = repository()
            assertThat(repo.sync().cloned).isTrue()
            assertThat(repo.items.value.map { it.name }).containsExactly("github.com/parham")

            val key = repo.keyFor("github.com/parham")!!
            assertThat(repo.needsPassphrase(key)).isTrue()
            assertThat(repo.open("github.com/parham", "pass".toCharArray()).username).isEqualTo("parham")

            repo.save("bank/mellat", PassEntry.parse("s3cret\nlogin: me\n"))

            Git.open(origin).use { git ->
                val head =
                    git
                        .log()
                        .setMaxCount(1)
                        .call()
                        .first()
                assertThat(head.fullMessage).isEqualTo("Add given password for bank/mellat to store.")
                assertThat(head.authorIdent.name).isEqualTo("Phone")
            }
            assertThat(repo.open("bank/mellat", "pass".toCharArray()).password).isEqualTo("s3cret")
        }

    @Test
    fun `a shared folder is encrypted for every recipient, using exported public keys`() =
        runTest {
            val repo = repository()
            repo.sync()
            repo.save("shared/wifi", PassEntry.parse("letmein\n"))

            val cipher = File(workTree, "shared/wifi.gpg").readBytes()
            assertThat(String(crypto.decrypt(cipher, listOf(friend), null))).startsWith("letmein")
        }

    @Test
    fun `refuses to encrypt for fewer people than the gpg-id names`() =
        runTest {
            val repo = repository()
            repo.sync()
            File(workTree, "shared/.public-keys").delete()
            File(workTree, ".public-keys/friend").delete()
            val error = runCatching { repo.save("shared/wifi", PassEntry.parse("x\n")) }.exceptionOrNull()
            assertThat(error).isInstanceOf(MissingRecipients::class.java)
            assertThat((error as MissingRecipients).ids).containsExactly("friend@example.com")
        }

    @Test
    fun `rename and delete are single commits`() =
        runTest {
            val repo = repository()
            repo.sync()
            val entry = repo.open("github.com/parham", "pass".toCharArray())
            repo.save("dev/github.com/parham", entry, previousName = "github.com/parham")
            assertThat(repo.items.value.map { it.name }).containsExactly("dev/github.com/parham")
            repo.delete("dev/github.com/parham")
            assertThat(repo.items.value).isEmpty()

            Git.open(origin).use { git ->
                val messages = git.log().call().map { it.fullMessage }
                assertThat(messages.take(2))
                    .containsExactly(
                        "Remove dev/github.com/parham from store.",
                        "Rename github.com/parham to dev/github.com/parham.",
                    ).inOrder()
            }
        }

    @Test
    fun `a wrong passphrase does not decrypt`() =
        runTest {
            val repo = repository()
            repo.sync()
            assertThrows(WrongPassphrase::class.java) {
                kotlinx.coroutines.runBlocking { repo.open("github.com/parham", "nope".toCharArray()) }
            }
        }
}
