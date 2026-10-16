package me.parham1995.passwords.store

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class StoreTreeTest {
    private val items =
        listOf("bank", "aut/email/parham", "aut/email/work", "aut/vpn", "Zoo/x", "apple")
            .map { PasswordStore.Item(it, File(it)) }

    @Test
    fun `the root shows top-level folders first, with recursive counts, then entries`() {
        val root = StoreTree.list(items, "")
        assertThat(root.folders.map { it.name to it.count }).containsExactly("aut" to 3, "Zoo" to 1).inOrder()
        assertThat(root.entries.map { it.name }).containsExactly("apple", "bank").inOrder()
    }

    @Test
    fun `a folder shows only what it holds directly`() {
        val aut = StoreTree.list(items, "aut")
        assertThat(aut.folders.map { it.path }).containsExactly("aut/email")
        assertThat(aut.entries.map { it.name }).containsExactly("aut/vpn")
        assertThat(
            StoreTree.list(items, "aut/email").entries.map { it.leaf },
        ).containsExactly("parham", "work").inOrder()
    }

    @Test
    fun `a name that merely starts like a folder is not inside it`() {
        val listing = StoreTree.list(listOf(PasswordStore.Item("autumn/x", File("x"))), "aut")
        assertThat(listing.isEmpty).isTrue()
    }

    @Test
    fun `parents and breadcrumbs`() {
        assertThat(StoreTree.parent("aut/email")).isEqualTo("aut")
        assertThat(StoreTree.parent("aut")).isEqualTo("")
        assertThat(StoreTree.parent("")).isNull()
        assertThat(StoreTree.crumbs("aut/email")).containsExactly("aut" to "aut", "email" to "aut/email").inOrder()
    }
}
