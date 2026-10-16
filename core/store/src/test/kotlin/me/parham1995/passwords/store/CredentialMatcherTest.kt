package me.parham1995.passwords.store

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class CredentialMatcherTest {
    private fun items(vararg names: String) = names.map { PasswordStore.Item(it, File(it)) }

    private val store =
        items(
            "aveehealth/miro.com/parham",
            "huma/github.com/parham-huma",
            "websites/github.com/1995parham",
            "aut/accounts.aut.ac.ir/9531000",
            "telegram/main",
            "bank/mybank.com/me",
            "shop/amazon.co.uk/me",
            "dev/gitlab.com/parham/password",
            "bank",
        )

    private fun names(target: CredentialMatcher.Target) = CredentialMatcher.find(store, target).map { it.item.name }

    @Test
    fun `a web page matches its domain folder and its subdomains, but not lookalikes`() {
        assertThat(
            names(CredentialMatcher.Target.Web("https://www.miro.com/login")),
        ).containsExactly("aveehealth/miro.com/parham")
        assertThat(names(CredentialMatcher.Target.Web("eu.miro.com"))).containsExactly("aveehealth/miro.com/parham")
        assertThat(names(CredentialMatcher.Target.Web("bank.com"))).isEmpty()
        assertThat(names(CredentialMatcher.Target.Web("evilmiro.com"))).isEmpty()
    }

    @Test
    fun `every account for a site is offered, named by file`() {
        val matches = CredentialMatcher.find(store, CredentialMatcher.Target.Web("github.com"))
        assertThat(matches.map { it.username }).containsExactly("1995parham", "parham-huma")
    }

    @Test
    fun `sibling subdomains of a two-part suffix still match`() {
        assertThat(
            names(CredentialMatcher.Target.Web("portal.aut.ac.ir")),
        ).containsExactly("aut/accounts.aut.ac.ir/9531000")
        assertThat(names(CredentialMatcher.Target.Web("other.ac.ir"))).isEmpty()
        assertThat(names(CredentialMatcher.Target.Web("www.amazon.co.uk"))).containsExactly("shop/amazon.co.uk/me")
    }

    @Test
    fun `apps match through their reversed domain or a brand folder`() {
        assertThat(names(CredentialMatcher.Target.App("com.github.android")))
            .containsExactly("huma/github.com/parham-huma", "websites/github.com/1995parham")
        assertThat(names(CredentialMatcher.Target.App("org.telegram.messenger"))).containsExactly("telegram/main")
        assertThat(names(CredentialMatcher.Target.App("com.google.android.youtube"))).isEmpty()
    }

    @Test
    fun `one folder per account names the account by its folder`() {
        val match = CredentialMatcher.find(store, CredentialMatcher.Target.Web("gitlab.com")).single()
        assertThat(match.username).isEqualTo("parham")
    }

    @Test
    fun `an alias and its target are offered once`() {
        val aliased =
            listOf(
                PasswordStore.Item("snapp/mail/me", File("a")),
                PasswordStore.Item("snapp/mail.snapp.cab/me", File("b"), aliasOf = "snapp/mail/me"),
            )
        assertThat(CredentialMatcher.find(aliased, CredentialMatcher.Target.Web("mail.snapp.cab"))).hasSize(1)
    }
}
