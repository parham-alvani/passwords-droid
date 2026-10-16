package me.parham1995.passwords.store

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class PasswordStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(vararg files: Pair<String, String>): PasswordStore {
        files.forEach { (path, text) -> File(tmp.root, path).apply { parentFile.mkdirs() }.writeText(text) }
        return PasswordStore(tmp.root)
    }

    @Test
    fun `lists gpg files by pass name and skips hidden directories`() {
        val store =
            store(
                "github.com/parham.gpg" to "",
                "bank.gpg" to "",
                ".git/objects/x.gpg" to "",
                ".public-keys/0xABC" to "",
                "notes.txt" to "",
            )
        assertThat(store.items().map { it.name }).containsExactly("bank", "github.com/parham").inOrder()
    }

    @Test
    fun `recipients come from the nearest gpg-id`() {
        val store =
            store(
                ".gpg-id" to "0xAAAA\n# comment\n\n0xBBBB\n",
                "shared/.gpg-id" to "0xCCCC\n",
                "shared/deep/x.gpg" to "",
                "mine.gpg" to "",
            )
        assertThat(store.recipientsFor("mine")).containsExactly("0xAAAA", "0xBBBB")
        assertThat(store.recipientsFor("shared/deep/x")).containsExactly("0xCCCC")
    }

    @Test
    fun `symlinked folders are listed under both names, like pass ls`() {
        val store = store("mail/me.gpg" to "", ".gpg-id" to "0xAAAA\n")
        Files.createSymbolicLink(File(tmp.root, "mail.example.com").toPath(), File("mail").toPath())

        val items = store.items()
        assertThat(items.map { it.name }).containsExactly("mail.example.com/me", "mail/me").inOrder()
        assertThat(items.single { it.name == "mail.example.com/me" }.aliasOf).isEqualTo("mail/me")
        assertThat(store.realName("mail.example.com/me")).isEqualTo("mail/me")
        assertThat(store.recipientsFor("mail.example.com/me")).containsExactly("0xAAAA")
    }

    @Test
    fun `a link out of the store is neither listed nor usable`() {
        val outside = tmp.newFolder("outside").apply { File(this, "stolen.gpg").writeText("") }
        val root = File(tmp.root, "store").apply { mkdirs() }
        File(root, "ok.gpg").writeText("")
        Files.createSymbolicLink(File(root, "escape").toPath(), outside.toPath())
        Files.createSymbolicLink(File(root, "leak.gpg").toPath(), File(outside, "stolen.gpg").toPath())
        val store = PasswordStore(root)

        assertThat(store.items().map { it.name }).containsExactly("ok")
        assertThrows(IllegalArgumentException::class.java) { store.file("escape/stolen") }
        assertThrows(IllegalArgumentException::class.java) { store.file("leak") }
        assertThrows(IllegalArgumentException::class.java) { store.file("../outside/stolen") }
    }

    @Test
    fun `a link back to an ancestor does not loop`() {
        val store = store("a/x.gpg" to "")
        Files.createSymbolicLink(File(tmp.root, "a/loop").toPath(), File("..").toPath())
        assertThat(store.items().map { it.name }).containsExactly("a/x")
    }

    @Test
    fun `a file alias is its own entry, and deleting it is not deleting the target`() {
        val store = store("real/p.gpg" to "")
        File(tmp.root, "other").mkdirs()
        Files.createSymbolicLink(File(tmp.root, "other/p.gpg").toPath(), File("../real/p.gpg").toPath())
        assertThat(store.isAlias("other/p")).isTrue()
        assertThat(store.ownName("other/p")).isEqualTo("other/p")
        assertThat(store.realName("other/p")).isEqualTo("real/p")
    }

    @Test
    fun `item splits folder and leaf`() {
        val item = PasswordStore.Item("aut/email/parham", File("x"))
        assertThat(item.folder).isEqualTo("aut/email")
        assertThat(item.leaf).isEqualTo("parham")
    }
}
