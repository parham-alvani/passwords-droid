package me.parham1995.passwords.feature.create

import com.google.common.truth.Truth.assertThat
import me.parham1995.passwords.store.CreateWizard
import me.parham1995.passwords.store.CreateWizard.Answer
import org.junit.Test

class WizardFormTest {
    private val website = CreateWizard.defaults[0]
    private val pin = CreateWizard.defaults[1]
    private val sso = CreateWizard.defaults[2]
    private val optional =
        CreateWizard.parseTemplate(
            """
            name: Optional
            prefix: sites
            name_from: [url]
            attributes:
            - name: url
              type: hostname
            - name: password
              type: password
              optional: true
            """.trimIndent(),
        )

    private fun attribute(
        template: CreateWizard.Template,
        name: String,
    ) = template.attributes.single { it.name == name }

    @Test
    fun `a new form generates its password and follows the answers for its name`() {
        var form = WizardForm.start(website)
        val password = form.password(attribute(website, "password"))
        assertThat(password.value).hasLength(24)
        assertThat(password.generated).isEqualTo(password.value)
        assertThat(form.name).isEmpty()

        form = form.withText(attribute(website, "url"), "https://github.com/login")
        form = form.withText(attribute(website, "username"), "me@x.com")
        assertThat(form.name).isEqualTo("websites/github.com/me@x.com")

        val created = CreateWizard.create(website, form.answers())
        assertThat(created.generated).isTrue()
        assertThat(created.name).isEqualTo(form.name)
    }

    @Test
    fun `a typed name stays until it is cleared`() {
        var form = WizardForm.start(website).withText(attribute(website, "url"), "x.com")
        form = form.withName("mine/x")
        form = form.withText(attribute(website, "username"), "me")
        assertThat(form.name).isEqualTo("mine/x")
        form = form.withName("")
        assertThat(form.nameEdited).isFalse()
        assertThat(form.name).isEqualTo("websites/x.com/me")
    }

    @Test
    fun `a charset password is four characters by default, or the length asked for`() {
        val attribute = attribute(pin, "password")
        var form = WizardForm.start(pin)
        assertThat(form.password(attribute).value).matches("[0-9]{4}")
        form = form.withPassword(attribute) { it.copy(length = "6") }.generate(attribute)
        assertThat(form.password(attribute).value).matches("[0-9]{6}")
    }

    @Test
    fun `an edited password is typed, not generated`() {
        val attribute = attribute(website, "password")
        val form = WizardForm.start(website).withPassword(attribute) { it.copy(value = it.value + "x") }
        assertThat(form.answers()["password"]).isInstanceOf(Answer.Text::class.java)
        val empty = form.withPassword(attribute) { it.copy(value = "") }
        assertThat(empty.answers()).doesNotContainKey("password")
    }

    @Test
    fun `an optional password switched off is declined and the entry has none`() {
        val attribute = attribute(optional, "password")
        val form =
            WizardForm
                .start(optional)
                .withText(attribute(optional, "url"), "x.com")
                .withPassword(attribute) { it.copy(present = false) }
        assertThat(form.answers()["password"]).isEqualTo(Answer.Declined)
        val created = CreateWizard.create(optional, form.answers())
        assertThat(created.password).isEmpty()
        assertThat(created.text).isEqualTo("\nurl: x.com\n")
    }

    @Test
    fun `the sso template starts on the first login method`() {
        val form =
            WizardForm
                .start(sso)
                .withText(attribute(sso, "url"), "x.com")
                .withText(attribute(sso, "username"), "me@x.com")
        val created = CreateWizard.create(sso, form.answers())
        assertThat(created.entry.isPasswordless).isTrue()
        assertThat(created.entry.loginMethod).isEqualTo("google")
        assertThat(created.commitMessage).isEqualTo("Save secret websites/x.com/me@x.com: Create new entry")
    }

    @Test
    fun `length limits count bytes, as gopass does`() {
        val url = attribute(website, "url")
        assertThat(WizardForm.lengthError(url, "")).isNull()
        assertThat(WizardForm.lengthError(url, "a".repeat(256))).isEqualTo("Too long (at most 255)")
        val username = attribute(website, "username")
        assertThat(WizardForm.lengthError(username, "é")).isNull()
    }
}
