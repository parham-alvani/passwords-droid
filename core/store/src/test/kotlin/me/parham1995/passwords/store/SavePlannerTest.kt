package me.parham1995.passwords.store

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class SavePlannerTest {
    private fun items(vararg names: String) = names.map { PasswordStore.Item(it, File(it)) }

    private val store =
        items(
            "websites/github.com/1995parham",
            "huma/github.com/parham-huma",
            "telegram/main",
        )

    private fun plan(
        target: CredentialMatcher.Target?,
        id: String,
        templates: List<CreateWizard.Template> = CreateWizard.defaults,
    ) = SavePlanner.plan(store, target, id, "s3cret!", templates)

    @Test
    fun `a login the store has is updated, not replaced`() {
        val plan = plan(CredentialMatcher.Target.Web("https://github.com/login"), "Parham-Huma")
        assertThat(plan.update).isEqualTo("huma/github.com/parham-huma")
    }

    @Test
    fun `a new account on a known site is not an update`() {
        val plan = plan(CredentialMatcher.Target.Web("github.com"), "someone-else")
        assertThat(plan.update).isNull()
    }

    @Test
    fun `a new web login is made the way gopass create makes a website login`() {
        val plan = plan(CredentialMatcher.Target.Web("https://www.example.org/login"), "me@example.org")

        assertThat(plan.create.name).isEqualTo("websites/www.example.org/me@example.org")
        assertThat(plan.create.text)
            .isEqualTo("s3cret!\nurl: https://www.example.org/login\nusername: me@example.org\n")
    }

    @Test
    fun `the website template is the one that fits, not the sso or pin one`() {
        val website =
            CreateWizard.defaults.first {
                it.prefix == "websites" &&
                    it.attributes.any { a -> a.type == "password" }
            }
        assertThat(SavePlanner.websiteTemplate(CreateWizard.defaults.reversed())).isEqualTo(website)
    }

    @Test
    fun `an app, or a store without a fitting template, falls back to a folder per site`() {
        val app = plan(CredentialMatcher.Target.App("org.telegram.messenger"), "+4400")
        assertThat(app.create.name).isEqualTo("telegram/+4400")
        assertThat(app.create.text).isEqualTo("s3cret!\nusername: +4400\n")

        val bare = plan(CredentialMatcher.Target.Web("www.example.org"), "me", templates = emptyList())
        assertThat(bare.create.name).isEqualTo("example.org/me")
        assertThat(bare.create.text).isEqualTo("s3cret!\nusername: me\nurl: https://www.example.org\n")
    }

    @Test
    fun `a sign-in without an account name cannot fill the template and falls back`() {
        val plan = plan(CredentialMatcher.Target.Web("example.org"), "")
        assertThat(plan.update).isNull()
        assertThat(plan.create.name).isEqualTo("example.org/login")
    }
}
