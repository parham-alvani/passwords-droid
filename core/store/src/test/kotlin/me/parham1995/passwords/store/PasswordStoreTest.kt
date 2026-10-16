package me.parham1995.passwords.store

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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
    fun `item splits folder and leaf`() {
        val item = PasswordStore.Item("aut/email/parham", File("x"))
        assertThat(item.folder).isEqualTo("aut/email")
        assertThat(item.leaf).isEqualTo("parham")
    }
}
