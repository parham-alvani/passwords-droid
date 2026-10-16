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
    fun `gopass references are followed, and loops are refused`() =
        runTest {
            val repo = repository()
            repo.sync()
            repo.save("alias/one", PassEntry.parse("gopass://alias/two\nlogin: x\n"))
            repo.save("alias/two", PassEntry.parse("gopass://github.com/parham\n"))

            val opened = repo.openFollowing("alias/one", "pass".toCharArray())
            assertThat(opened.password).isEqualTo("hunter2")
            assertThat(opened.via).containsExactly("alias/two", "github.com/parham").inOrder()
            assertThat(opened.entry.username).isEqualTo("x")

            repo.save("loop/a", PassEntry.parse("gopass://loop/b\n"))
            repo.save("loop/b", PassEntry.parse("gopass://loop/a\n"))
            val error = runCatching { repo.openFollowing("loop/a", "pass".toCharArray()) }.exceptionOrNull()
            assertThat(error).isInstanceOf(BrokenReference::class.java)
        }

    @Test
    fun `edits and deletes through symlinks touch the right files`() =
        runTest {
            Git.open(origin).use { git ->
                java.nio.file.Files
                    .createSymbolicLink(File(origin, "gh").toPath(), File("github.com").toPath())
                File(origin, "alias").mkdirs()
                java.nio.file.Files.createSymbolicLink(
                    File(origin, "alias/parham.gpg").toPath(),
                    File("../github.com/parham.gpg").toPath(),
                )
                git.add().addFilepattern(".").call()
                git
                    .commit()
                    .setMessage("aliases")
                    .setAuthor("Desk", "desk@example.com")
                    .setSign(false)
                    .call()
            }
            val repo = repository()
            repo.sync()
            assertThat(
                repo.items.value.map { it.name },
            ).containsExactly("alias/parham", "gh/parham", "github.com/parham")

            repo.save("gh/parham", PassEntry.parse("changed\nlogin: parham\n"), previousName = "gh/parham")
            assertThat(repo.open("github.com/parham", "pass".toCharArray()).password).isEqualTo("changed")

            repo.delete("alias/parham")
            assertThat(File(workTree, "github.com/parham.gpg").isFile).isTrue()
            Git.open(origin).use { git ->
                val head =
                    git
                        .log()
                        .setMaxCount(2)
                        .call()
                        .toList()
                assertThat(head[0].fullMessage).isEqualTo("Remove alias/parham from store.")
                assertThat(head[1].fullMessage).isEqualTo("Edit password for gh/parham using passwords-droid.")
            }
            assertThat(repo.items.value.map { it.name }).containsExactly("gh/parham", "github.com/parham")
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
