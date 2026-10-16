package me.parham1995.passwords.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import me.parham1995.passwords.crypto.PgpCrypto
import me.parham1995.passwords.crypto.WrongPassphrase
import me.parham1995.passwords.data.git.StoreGit
import me.parham1995.passwords.data.security.SecretBox
import me.parham1995.passwords.store.Documents
import me.parham1995.passwords.store.PassEntry
import me.parham1995.passwords.store.PasswordHealth
import me.parham1995.passwords.store.PasswordStore
import me.parham1995.passwords.store.Pwned
import me.parham1995.passwords.store.StoreIcons
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
    fun `folder icons are committed to the store and read back`() =
        runTest {
            val repo = repository()
            repo.sync()
            repo.setFolderIcon("github.com", StoreIcons.Icon("github", "purple"))
            assertThat(repo.icons.value.folder("github.com")).isEqualTo(StoreIcons.Icon("github", "purple"))
            // Setting the same icon again is not another commit.
            repo.setFolderIcon("github.com", StoreIcons.Icon("github", "purple"))

            Git.open(origin).use { git ->
                val log =
                    git
                        .log()
                        .setMaxCount(2)
                        .call()
                        .toList()
                assertThat(log[0].fullMessage).isEqualTo("Set icon for github.com.")
                assertThat(log[1].fullMessage).isEqualTo("seed")
            }
            assertThat(File(workTree, ".icons.json").readText()).contains("\"github\"")
            assertThat(repo.items.value.map { it.name }).containsExactly("github.com/parham")

            repo.setFolderIcon("github.com", null)
            assertThat(repo.icons.value.folder("github.com")).isNull()
        }

    @Test
    fun `history lists an entry's versions and restore puts an old one back as a new commit`() =
        runTest {
            val repo = repository()
            repo.sync()
            repo.save(
                "github.com/parham",
                PassEntry.parse("second\nlogin: parham\n"),
                previousName = "github.com/parham",
            )
            repo.save(
                "github.com/parham",
                PassEntry.parse("third\nlogin: parham\n"),
                previousName = "github.com/parham",
            )

            val history = repo.history("github.com/parham")
            assertThat(history.map { it.message })
                .containsExactly(
                    "Edit password for github.com/parham using passwords-droid.",
                    "Edit password for github.com/parham using passwords-droid.",
                    "seed",
                ).inOrder()
            assertThat(
                repo.openAt("github.com/parham", history.last().commit, "pass".toCharArray())?.password,
            ).isEqualTo("hunter2")

            repo.restore("github.com/parham", history.last(), "pass".toCharArray())
            assertThat(repo.open("github.com/parham", "pass".toCharArray()).password).isEqualTo("hunter2")
            Git.open(origin).use { git ->
                assertThat(
                    git
                        .log()
                        .setMaxCount(1)
                        .call()
                        .first()
                        .fullMessage,
                ).startsWith("Restore github.com/parham to its version of ")
                // Nothing was rewritten: all four versions are still there.
                assertThat(
                    git
                        .log()
                        .addPath("github.com/parham.gpg")
                        .call()
                        .count(),
                ).isEqualTo(4)
            }
        }

    @Test
    fun `health sees logins with their age, and skips documents and references`() =
        runTest {
            val repo = repository()
            repo.sync()
            repo.save(
                "certs/vso.pem",
                PassEntry.parse("-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n"),
            )
            repo.save("alias/gh", PassEntry.parse("gopass://github.com/parham\n"))
            repo.save("shop/a", PassEntry.parse("hunter2\n"))

            val candidates = repo.healthCandidates("pass".toCharArray())
            assertThat(candidates.map { it.name }).containsExactly("github.com/parham", "shop/a")
            assertThat(candidates.all { it.lastChanged != null }).isTrue()

            val report = PasswordHealth.analyze(candidates, System.currentTimeMillis())
            assertThat(report.reused).containsExactly(listOf("github.com/parham", "shop/a"))
        }

    @Test
    fun `the breach check sends only hash prefixes`() =
        runTest {
            val asked = mutableListOf<String>()
            val hash = Pwned.sha1("hunter2")
            val client =
                PwnedClient { prefix ->
                    asked += prefix
                    if (prefix == hash.take(5)) hash.drop(5) + ":42\nAAAA:1\n" else "BBBB:1\n"
                }
            val repo = repository()
            val counts =
                repo.pwned(
                    listOf(
                        PasswordHealth.Candidate("a", "hunter2", null),
                        PasswordHealth.Candidate("b", "something-else-entirely", null),
                    ),
                    client,
                )
            assertThat(counts).containsExactly("a", 42)
            assertThat(asked.all { it.length == 5 }).isTrue()
            assertThat(asked).doesNotContain("hunter2")
        }

    @Test
    fun `documents open as files, and binary files are stored as gopass attachments`() =
        runTest {
            val repo = repository()
            repo.sync()
            val pem = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n"
            repo.saveText("vault/vso.pem", pem, "Add vso.pem.")
            val opened = repo.openFollowing("vault/vso.pem", "pass".toCharArray())
            assertThat(opened.document?.fileName).isEqualTo("vso.pem")
            assertThat(String(opened.document!!.bytes)).isEqualTo(pem)

            val binary = byteArrayOf(0, -1, 2, 3)
            repo.saveText("vault/key.p12", Documents.attachment("key.p12", binary))
            assertThat(repo.openFollowing("vault/key.p12", "pass".toCharArray()).document?.bytes).isEqualTo(binary)
        }

    @Test
    fun `changing recipients re-encrypts the folder, exports keys, and is one commit`() =
        runTest {
            val repo = repository()
            repo.sync()
            repo.save("mine/a", PassEntry.parse("a-secret\n"))
            repo.save("mine/b", PassEntry.parse("b-secret\n"))
            val friendId = "0x" + friend.fingerprintHex().takeLast(16)
            val meId = "0x" + me.fingerprintHex().takeLast(16)

            repo.setRecipients("", listOf(meId, friendId), "pass".toCharArray())

            // The friend can now read everything the root .gpg-id governs...
            assertThat(
                String(crypto.decrypt(File(workTree, "mine/a.gpg").readBytes(), listOf(friend), null)),
            ).startsWith("a-secret")
            // ...but not the shared/ folder, which has its own .gpg-id and was left alone.
            assertThat(File(workTree, ".gpg-id").readText().lines()).containsAtLeast(meId, friendId)
            assertThat(File(workTree, ".public-keys/$friendId").isFile).isTrue()
            Git.open(origin).use { git ->
                val head =
                    git
                        .log()
                        .setMaxCount(1)
                        .call()
                        .first()
                assertThat(head.fullMessage).isEqualTo("Reencrypt store for $meId, $friendId.")
            }
        }

    @Test
    fun `recipients that would lock this phone out are refused`() =
        runTest {
            val repo = repository()
            repo.sync()
            val friendId = "0x" + friend.fingerprintHex().takeLast(16)
            val error = runCatching { repo.setRecipients("", listOf(friendId), "pass".toCharArray()) }.exceptionOrNull()
            assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(File(workTree, ".gpg-id").readText()).doesNotContain(friendId)
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
