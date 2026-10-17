package me.parham1995.passwords.store

import com.google.common.truth.Truth.assertThat
import me.parham1995.passwords.store.CreateWizard.Answer
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CreateWizardTest {
    @get:Rule val tmp = TemporaryFolder()

    private val pin =
        """
        ---
        priority: 1
        name: "PIN Code (numerical)"
        prefix: "pin"
        name_from:
          - "authority"
          - "application"
        welcome: "🧪 Creating numerical PIN"
        attributes:
          - name: "authority"
            type: "string"
            prompt: "Authority"
            min: 1
          - name: "application"
            type: "string"
            prompt: "Entity"
            min: 1
          - name: "password"
            type: "password"
            prompt: "Pin"
            charset: "0123456789"
            min: 1
            max: 64
            always_prompt: true
          - name: "comment"
            type: "string"
        """.trimIndent()

    @Test
    fun `a template file reads like gopass reads it`() {
        val template = CreateWizard.parseTemplate(pin)
        assertThat(template.name).isEqualTo("PIN Code (numerical)")
        assertThat(template.priority).isEqualTo(1)
        assertThat(template.prefix).isEqualTo("pin")
        assertThat(template.nameFrom).containsExactly("authority", "application").inOrder()
        assertThat(template.welcome).isEqualTo("🧪 Creating numerical PIN")
        assertThat(template.attributes).hasSize(4)
        val password = template.attributes[2]
        assertThat(password.type).isEqualTo("password")
        assertThat(password.charset).isEqualTo("0123456789")
        assertThat(password.max).isEqualTo(64)
        assertThat(password.alwaysPrompt).isTrue()
        assertThat(template.attributes[0].max).isEqualTo(0)
        assertThat(template.attributes[3].label).isEqualTo("COMMENT")
    }

    @Test
    fun `optional, strict, flow lists and comments parse`() {
        val template =
            CreateWizard.parseTemplate(
                """
                # a comment
                name: optional pw   # trailing comment
                prefix: opt
                name_from: [url, 'user']
                welcome: |
                  two
                  lines
                attributes:
                - name: password
                  type: password
                  optional: true
                  strict: yes
                """.trimIndent(),
            )
        assertThat(template.name).isEqualTo("optional pw")
        assertThat(template.nameFrom).containsExactly("url", "user").inOrder()
        assertThat(template.welcome).isEqualTo("two\nlines\n")
        assertThat(template.attributes.single().optional).isTrue()
        assertThat(template.attributes.single().strict).isTrue()
    }

    @Test
    fun `bad files are skipped, the rest sorted by priority`() {
        val dir = tmp.newFolder(".gopass", "create")
        dir.resolve("b.yml").writeText(pin)
        dir.resolve("a.yaml").writeText("name: first\npriority: 0\n")
        dir.resolve("bad.yml").writeText("name: [unclosed\n")
        dir.resolve("worse.yml").writeText("priority: high\n")
        dir.resolve("notes.txt").writeText("name: ignored\n")
        val errors = mutableListOf<String>()
        val templates = CreateWizard.load(tmp.root) { file, _ -> errors += file.name }
        assertThat(templates.map { it.name }).containsExactly("first", "PIN Code (numerical)").inOrder()
        assertThat(errors).containsExactly("bad.yml", "worse.yml")
    }

    @Test
    fun `a store without templates gets gopass's defaults, not written`() {
        val templates = CreateWizard.load(tmp.root)
        assertThat(templates.map { it.name })
            .containsExactly("Website login", "PIN Code (numerical)", "SSO / passwordless login (e.g. Google)")
            .inOrder()
        assertThat(tmp.root.resolve(".gopass").exists()).isFalse()
        val sso = templates.last()
        assertThat(sso.attributes.none { it.type == CreateWizard.Attribute.PASSWORD }).isTrue()
        val method = sso.attributes.single { it.name == "login-method" }
        assertThat(method.type).isEqualTo("choice")
        assertThat(
            method.options,
        ).containsExactly("google", "apple", "github", "microsoft", "sso", "magic-link").inOrder()
    }

    @Test
    fun `a website login is built as gopass builds it`() {
        val website = CreateWizard.defaults[0]
        val created =
            CreateWizard.create(
                website,
                mapOf(
                    "url" to Answer.Text("https://accounts.example.com:8443/login?x=1"),
                    "username" to Answer.Text("parham@example.com"),
                    "password" to Answer.Text("hunter2"),
                ),
            )
        assertThat(created.name).isEqualTo("websites/accounts.example.com/parham@example.com")
        assertThat(created.text)
            .isEqualTo("hunter2\nurl: https://accounts.example.com:8443/login?x=1\nusername: parham@example.com\n")
        assertThat(created.entry.username).isEqualTo("parham@example.com")
        assertThat(created.commitMessage)
            .isEqualTo("Save secret websites/accounts.example.com/parham@example.com: Create new entry")
    }

    @Test
    fun `an sso login has no password and a login method`() {
        val sso = CreateWizard.defaults[2]
        val created =
            CreateWizard.create(
                sso,
                mapOf(
                    "url" to Answer.Text("figma.com"),
                    "username" to Answer.Text("me@example.com"),
                    "login-method" to Answer.Text("google"),
                ),
            )
        assertThat(created.name).isEqualTo("websites/figma.com/me@example.com")
        assertThat(
            created.text,
        ).isEqualTo("\nurl: figma.com\nusername: me@example.com\nlogin-method: google\ncomment: \n")
        assertThat(created.entry.isPasswordless).isTrue()
        assertThat(created.entry.loginMethod).isEqualTo("google")

        val bad =
            assertThrows(CreateWizard.InvalidAnswer::class.java) {
                CreateWizard.create(
                    sso,
                    mapOf(
                        "url" to Answer.Text("x.com"),
                        "username" to Answer.Text("me"),
                        "login-method" to Answer.Text("fax"),
                    ),
                )
            }
        assertThat(bad.message).startsWith("login-method must be one of")
    }

    @Test
    fun `an optional password may be declined, a required one not`() {
        val optional =
            CreateWizard.Template(
                name = "opt",
                prefix = "x",
                nameFrom = listOf("site"),
                attributes =
                    listOf(
                        CreateWizard.Attribute("site", "string"),
                        CreateWizard.Attribute("password", "password", optional = true),
                        CreateWizard.Attribute("notes", "multiline"),
                    ),
            )
        val created =
            CreateWizard.create(
                optional,
                mapOf(
                    "site" to Answer.Text("a b/c"),
                    "password" to Answer.Declined,
                    "notes" to Answer.Text("line 1\nline 2\n"),
                ),
            )
        assertThat(created.name).isEqualTo("x/a_b_c")
        assertThat(created.text).isEqualTo("\nsite: a b/c\nline 1\nline 2\n")
        assertThat(created.password).isEmpty()

        val required = optional.copy(attributes = optional.attributes.map { it.copy(optional = false) })
        assertThrows(CreateWizard.InvalidAnswer::class.java) {
            CreateWizard.create(required, mapOf("site" to Answer.Text("a"), "password" to Answer.Declined))
        }
    }

    @Test
    fun `min and max fail with gopass's messages, generated passwords are exempt`() {
        val template = CreateWizard.parseTemplate(pin)
        val short =
            assertThrows(CreateWizard.InvalidAnswer::class.java) {
                CreateWizard.create(template, mapOf("authority" to Answer.Text(""), "application" to Answer.Text("b")))
            }
        assertThat(short.message).isEqualTo("authority is too short (needs 1)")
        val long =
            assertThrows(CreateWizard.InvalidAnswer::class.java) {
                CreateWizard.create(
                    template,
                    mapOf(
                        "authority" to Answer.Text("a"),
                        "application" to Answer.Text("b"),
                        "password" to Answer.Text("1".repeat(65)),
                    ),
                )
            }
        assertThat(long.message).isEqualTo("password is too long (at most 64)")

        val pinCode = CreateWizard.generatePassword(template.attributes[2])
        assertThat(pinCode).matches("[0-9]{4}")
        val created =
            CreateWizard.create(
                template,
                mapOf(
                    "authority" to Answer.Text("Bank Mellat"),
                    "application" to Answer.Text("card"),
                    "password" to Answer.Generated(pinCode),
                ),
            )
        assertThat(created.name).isEqualTo("pin/Bank_Mellat/card")
        assertThat(created.generated).isTrue()
        assertThat(created.text).isEqualTo("$pinCode\nauthority: Bank Mellat\napplication: card\ncomment: \n")
    }

    @Test
    fun `strict charsets include every class they hold`() {
        repeat(200) {
            val pw = PasswordGenerator.fromCharset(4, "ab12XY#", strict = true)
            assertThat(pw).hasLength(4)
            assertThat(pw.any { it.isDigit() }).isTrue()
            assertThat(pw.any { it.isLowerCase() }).isTrue()
            assertThat(pw.any { it.isUpperCase() }).isTrue()
            assertThat(pw.any { it == '#' }).isTrue()
        }
    }

    @Test
    fun `hostnames and filenames are cleaned like gopass cleans them`() {
        mapOf(
            "" to "",
            "http://www.example.org/" to "www.example.org",
            "++#+++#jhlkadsrezu 33 553q ++++##\$§&" to "jhlkadsrezu_33_553q",
            "www.example.org/?foo=bar#abc" to "www.example.org",
            "a test" to "a_test",
            "http://example.com" to "example.com",
            "https://sub.example.com" to "sub.example.com",
            "ftp://example.com" to "example.com",
            "example.com" to "example.com",
            "invalid-url" to "invalid-url",
            "user@host.com:22" to "host.com",
        ).forEach { (input, expected) -> assertThat(CreateWizard.extractHostname(input)).isEqualTo(expected) }
        assertThat(CreateWizard.cleanFilename(" _a/b c_ ")).isEqualTo("a_b_c")
        assertThat(CreateWizard.cleanFilename("پرهام")).isEqualTo("")
    }

    @Test
    fun `the name shows as it is typed and matches what create gives`() {
        val website = CreateWizard.defaults[0]
        assertThat(CreateWizard.nameOf(website, emptyMap())).isNull()
        assertThat(CreateWizard.nameOf(website, mapOf("url" to Answer.Text("https://github.com/login"))))
            .isEqualTo("websites/github.com")
        val answers =
            mapOf(
                "url" to Answer.Text("https://github.com/login"),
                "username" to Answer.Text("me@x.com"),
                "password" to Answer.Text("hunter2"),
            )
        assertThat(CreateWizard.nameOf(website, answers)).isEqualTo(CreateWizard.create(website, answers).name)

        val sso = CreateWizard.defaults[2]
        val ssoAnswers =
            mapOf(
                "url" to Answer.Text("x.com"),
                "username" to Answer.Text("me"),
                "login-method" to Answer.Text("google"),
            )
        assertThat(CreateWizard.nameOf(sso, ssoAnswers)).isEqualTo("websites/x.com/me")
    }
}
