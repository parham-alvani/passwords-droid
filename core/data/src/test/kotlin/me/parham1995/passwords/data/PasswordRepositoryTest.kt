package me.parham1995.passwords.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import me.parham1995.passwords.crypto.PgpCrypto
import me.parham1995.passwords.crypto.WrongPassphrase
import me.parham1995.passwords.data.git.StoreGit
import me.parham1995.passwords.data.git.SyncConflict
import me.parham1995.passwords.data.security.SecretBox
import me.parham1995.passwords.store.Documents
import me.parham1995.passwords.store.PassEntry
import me.parham1995.passwords.store.PasswordHealth
import me.parham1995.passwords.store.PasswordStore
import me.parham1995.passwords.store.Pwned
import me.parham1995.passwords.store.StoreIcons
import org.bouncycastle.openpgp.api.OpenPGPCertificate
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand.ResetType
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

    private fun repository(
        scheduler: SyncScheduler = SyncScheduler.NONE,
        extra: List<OpenPGPCertificate> = emptyList(),
    ): PasswordRepository {
        val ring =
            KeyRing(
                File(root, "keyring"),
                clearBox,
                crypto,
            ).apply {
                import(me.toAsciiArmoredString().toByteArray())
                extra.forEach { import(it.toAsciiArmoredString().toByteArray()) }
            }
        val git = StoreGit(workTree, origin.toURI().toString(), "main", keys = null, configDir = File(root, "config"))
        return PasswordRepository(
            PasswordStore(workTree),
            ring,
            crypto,
            { git },
            { Settings(authorName = "Phone", authorEmail = "p@x") },
            scheduler,
        )
    }

    private fun originMessages(count: Int): List<String> =
        Git.open(origin).use { git ->
            git
                .log()
                .setMaxCount(count)
                .call()
                .map { it.fullMessage }
        }

    /** A commit made on the desktop, straight into the origin. */
    private fun desktopCommit(
        path: String,
        bytes: ByteArray,
        message: String,
    ) = Git.open(origin).use { git ->
        // The phone pushes into this checked-out branch; catch the tree up first.
        git.reset().setMode(ResetType.HARD).call()
        File(origin, path).apply { parentFile?.mkdirs() }.writeBytes(bytes)
        git.add().addFilepattern(path).call()
        git
            .commit()
            .setMessage(message)
            .setAuthor("Desk", "desk@example.com")
            .setSign(false)
            .call()
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
                assertThat(head.fullMessage).isEqualTo("Save secret bank/mellat: Insert user supplied password")
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
                        "Save secret dev/github.com/parham: Move from github.com/parham to dev/github.com/parham",
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
                assertThat(head[1].fullMessage).isEqualTo("Save secret gh/parham: Edit with passwords-droid")
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
                assertThat(log[0].fullMessage).isEqualTo("Save secret github.com: Set folder icon")
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
                    "Save secret github.com/parham: Edit with passwords-droid",
                    "Save secret github.com/parham: Edit with passwords-droid",
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
                ).startsWith("Save secret github.com/parham: Restore version of ")
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
    fun `passwordless accounts are not scored, and their login method is remembered in memory`() =
        runTest {
            val repo = repository()
            repo.sync()
            repo.saveText(
                "websites/x.com/me",
                "\nurl: x.com\nusername: me\nlogin-method: google\n",
                CommitMessages.create("websites/x.com/me"),
            )
            assertThat(originMessages(1)).containsExactly("Save secret websites/x.com/me: Create new entry")
            assertThat(repo.loginMethods.value).containsExactly("websites/x.com/me", "google")

            val candidates = repo.healthCandidates("pass".toCharArray())
            assertThat(candidates.map { it.name }).containsExactly("github.com/parham")

            repo.delete("websites/x.com/me")
            assertThat(repo.loginMethods.value).isEmpty()
            repo.open("github.com/parham", "pass".toCharArray())
            assertThat(repo.loginMethods.value).isEmpty()
        }

    @Test
    fun `wizard templates come from the store, or gopass's defaults`() =
        runTest {
            val repo = repository()
            repo.sync()
            assertThat(repo.createTemplates().map { it.name }).hasSize(3)
            File(workTree, ".gopass/create/card.yml")
                .apply { parentFile?.mkdirs() }
                .writeText("name: Card\nprefix: cards\nname_from: [bank]\nattributes:\n- name: bank\n  type: string\n")
            assertThat(repo.createTemplates().map { it.name }).containsExactly("Card")
        }

    @Test
    fun `an empty password is never sent to the breach check`() =
        runTest {
            val asked = mutableListOf<String>()
            val counts =
                repository().pwned(
                    listOf(PasswordHealth.Candidate("sso", "", null)),
                    PwnedClient { prefix ->
                        asked += prefix
                        ""
                    },
                )
            assertThat(counts).isEmpty()
            assertThat(asked).isEmpty()
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
            val meId = "0x" + me.fingerprintHex().takeLast(16)
            desktopCommit(".gpg-id", "# the owner\n$meId\n".toByteArray(), "comment")
            val carol = api.generateKey().modernKeyRing("Carol <carol@example.com>").toCertificate()
            val repo = repository(extra = listOf(carol))
            repo.sync()
            repo.save("mine/a", PassEntry.parse("a-secret\n"))
            repo.save("mine/b", PassEntry.parse("b-secret\n"))
            val friendId = "0x" + friend.fingerprintHex().takeLast(16)

            repo.setRecipients("", listOf(meId, friendId, carol.fingerprintHex()), "pass".toCharArray())

            // The friend can now read everything the root .gpg-id governs...
            assertThat(
                String(crypto.decrypt(File(workTree, "mine/a.gpg").readBytes(), listOf(friend), null)),
            ).startsWith("a-secret")
            // ...the comment and the existing line stay, new ones are full fingerprints, as gopass writes them...
            assertThat(File(workTree, ".gpg-id").readText())
                .isEqualTo("# the owner\n$meId\n${friend.fingerprintHex()}\n${carol.fingerprintHex()}\n")
            // ...a key gopass already exported is not exported twice; the others are named by fingerprint.
            assertThat(
                File(workTree, ".public-keys").list(),
            ).asList().containsExactly("friend", me.fingerprintHex(), carol.fingerprintHex())
            assertThat(originMessages(1).single())
                .isEqualTo(
                    "Add Recipient ${friend.fingerprintHex()}\n" +
                        "Add Recipient ${carol.fingerprintHex()}\n" +
                        "Update exported Public Keys",
                )

            repo.setRecipients("", listOf(meId, carol.fingerprintHex()), "pass".toCharArray())
            assertThat(
                File(workTree, ".gpg-id").readText(),
            ).isEqualTo("# the owner\n$meId\n${carol.fingerprintHex()}\n")
            assertThat(originMessages(1).single()).isEqualTo("Remove Recipient ${friend.fingerprintHex()}")
        }

    @Test
    fun `a failure part way through re-encryption puts every file back`() =
        runTest {
            val repo = repository()
            repo.sync()
            repo.save("mine/a", PassEntry.parse("a-secret\n"))
            repo.save("mine/b", PassEntry.parse("b-secret\n"))
            val before =
                listOf("github.com/parham.gpg", "mine/a.gpg", "mine/b.gpg", ".gpg-id").associateWith {
                    File(workTree, it).readBytes()
                }
            // The last file cannot be written: the first ones already were when that is found out.
            File(workTree, "mine/b.gpg").setWritable(false)
            val friendId = "0x" + friend.fingerprintHex().takeLast(16)
            val meId = "0x" + me.fingerprintHex().takeLast(16)

            val error =
                runCatching {
                    repo.setRecipients(
                        "",
                        listOf(meId, friendId),
                        "pass".toCharArray(),
                    )
                }.exceptionOrNull()

            assertThat(error).isNotNull()
            before.forEach { (path, bytes) -> assertThat(File(workTree, path).readBytes()).isEqualTo(bytes) }
            Git.open(workTree).use { git -> assertThat(git.status().call().isClean).isTrue() }
            // And the tree is still fit to pull.
            File(workTree, "mine/b.gpg").setWritable(true)
            repo.sync()
        }

    @Test
    fun `an unlock entry comes from the folder being changed`() =
        runTest {
            val repo = repository()
            repo.sync()
            repo.save("shared/wifi", PassEntry.parse("x\n"))
            assertThat(repo.governedEntries("shared")).containsExactly("shared/wifi")
            assertThat(repo.governedEntries("")).containsExactly("github.com/parham")
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

    @Test
    fun `commit messages follow gopass for typed, generated and renamed entries`() =
        runTest {
            val repo = repository()
            repo.sync()
            repo.save("a", PassEntry.parse("typed\n"))
            repo.save("b", PassEntry.parse("generated\n"), generated = true)
            repo.save("a", PassEntry.parse("edited\n"), previousName = "a")
            repo.saveText("c", "file\n", CommitMessages.file("c", "c.txt"))
            assertThat(originMessages(4))
                .containsExactly(
                    "Save secret c: Copy file c.txt",
                    "Save secret a: Edit with passwords-droid",
                    "Save secret b: Generate Password",
                    "Save secret a: Insert user supplied password",
                ).inOrder()
        }

    @Test
    fun `renaming a file alias moves the alias and leaves its entry alone`() =
        runTest {
            Git.open(origin).use { git ->
                File(origin, "alias").mkdirs()
                Files.createSymbolicLink(
                    File(origin, "alias/parham.gpg").toPath(),
                    File("../github.com/parham.gpg").toPath(),
                )
                git.add().addFilepattern(".").call()
                git
                    .commit()
                    .setMessage("alias")
                    .setAuthor("Desk", "desk@example.com")
                    .setSign(false)
                    .call()
            }
            val repo = repository()
            repo.sync()

            val entry = repo.open("alias/parham", "pass".toCharArray())
            repo.save("links/gh", entry, previousName = "alias/parham")

            assertThat(File(workTree, "github.com/parham.gpg").isFile).isTrue()
            assertThat(Files.isSymbolicLink(File(workTree, "github.com/parham.gpg").toPath())).isFalse()
            assertThat(File(workTree, "alias/parham.gpg").exists()).isFalse()
            val link = File(workTree, "links/gh.gpg").toPath()
            assertThat(Files.isSymbolicLink(link)).isTrue()
            assertThat(Files.readSymbolicLink(link).toString()).isEqualTo("../github.com/parham.gpg")
            assertThat(repo.open("links/gh", "pass".toCharArray()).password).isEqualTo("hunter2")
            assertThat(repo.items.value.map { it.name }).containsExactly("github.com/parham", "links/gh")
            assertThat(originMessages(1).single()).isEqualTo("Save secret links/gh: Move from alias/parham to links/gh")
            Git.open(workTree).use { git -> assertThat(git.status().call().isClean).isTrue() }
        }

    @Test
    fun `a revoked recipient key is named rather than failing inside the crypto`() =
        runTest {
            val revoked =
                api
                    .modify(friend)
                    .revoke(
                        org.pgpainless.key.protection.SecretKeyRingProtector
                            .unprotectedKeys(),
                    ).done()
            desktopCommit(".public-keys/friend", revoked.toCertificate().toAsciiArmoredString().toByteArray(), "revoke")
            val repo = repository()
            repo.sync()
            val error = runCatching { repo.save("shared/wifi", PassEntry.parse("x\n")) }.exceptionOrNull()
            assertThat(error).isInstanceOf(MissingRecipients::class.java)
            assertThat((error as MissingRecipients).ids).containsExactly("friend@example.com")
            assertThat(error.reasons.getValue("friend@example.com")).contains("revoked")
            assertThat(error.message).contains("Friend <friend@example.com> is revoked")
        }

    @Test
    fun `a push that fails is scheduled, and the background sync rebases over desktop merges`() =
        runTest {
            var scheduled = 0
            val repo = repository(scheduler = { scheduled++ })
            repo.sync()
            val away = File(root, "away")
            check(origin.renameTo(away))

            // Offline: the save still works, and the push is handed to the background.
            repo.save("bank/mellat", PassEntry.parse("s3cret\n"))
            assertThat(scheduled).isEqualTo(1)
            assertThat(repo.syncStatus.value.pending).isEqualTo(1)
            assertThat(repo.syncStatus.value.error).isNotNull()
            assertThat(repo.pushPending()).isEqualTo(PasswordRepository.PushOutcome.RETRY)

            // Meanwhile the desktop committed on a branch and merged it.
            check(away.renameTo(origin))
            Git.open(origin).use { git ->
                git.reset().setMode(ResetType.HARD).call()
                git.branchCreate().setName("side").call()
                git.checkout().setName("side").call()
            }
            desktopCommit("side/one.gpg", crypto.encrypt("one\n".toByteArray(), listOf(me.toCertificate())), "side")
            Git.open(origin).use { git ->
                git.checkout().setName("main").call()
            }
            desktopCommit("main/two.gpg", crypto.encrypt("two\n".toByteArray(), listOf(me.toCertificate())), "main")
            Git.open(origin).use { git ->
                git
                    .merge()
                    .include(git.repository.resolve("side"))
                    .setFastForward(org.eclipse.jgit.api.MergeCommand.FastForwardMode.NO_FF)
                    .setMessage("Merge side")
                    .call()
            }

            assertThat(repo.pushPending()).isEqualTo(PasswordRepository.PushOutcome.DONE)
            assertThat(repo.syncStatus.value).isEqualTo(PasswordRepository.SyncStatus())
            assertThat(originMessages(2))
                .containsExactly("Save secret bank/mellat: Insert user supplied password", "Merge side")
                .inOrder()
            assertThat(repo.items.value.map { it.name }).containsAtLeast("bank/mellat", "main/two", "side/one")
            assertThat(repo.history("side/one").map { it.message }).containsExactly("side")
        }

    @Test
    fun `a desktop push in between is rebased over without bothering the background`() =
        runTest {
            var scheduled = 0
            val repo = repository(scheduler = { scheduled++ })
            repo.sync()
            desktopCommit("desk/x.gpg", crypto.encrypt("x\n".toByteArray(), listOf(me.toCertificate())), "desk")
            repo.save("phone/y", PassEntry.parse("y\n"))
            assertThat(scheduled).isEqualTo(0)
            assertThat(originMessages(2))
                .containsExactly("Save secret phone/y: Insert user supplied password", "desk")
                .inOrder()
        }

    @Test
    fun `a pull over uncommitted changes says so instead of failing to abort`() =
        runTest {
            val repo = repository()
            repo.sync()
            desktopCommit(
                "github.com/parham.gpg",
                crypto.encrypt("desk\n".toByteArray(), listOf(me.toCertificate())),
                "desk edit",
            )
            File(workTree, "github.com/parham.gpg").writeBytes(byteArrayOf(1, 2, 3))
            val error = runCatching { repo.sync() }.exceptionOrNull()
            assertThat(error).isInstanceOf(SyncConflict::class.java)
            Git.open(workTree).use { git ->
                assertThat(git.repository.repositoryState).isEqualTo(org.eclipse.jgit.lib.RepositoryState.SAFE)
            }
        }
}
