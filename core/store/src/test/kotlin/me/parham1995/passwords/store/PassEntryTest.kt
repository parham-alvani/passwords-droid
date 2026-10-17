package me.parham1995.passwords.store

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PassEntryTest {
    @Test
    fun `first line is the password and the rest are fields, otp and notes`() {
        val entry =
            PassEntry.parse(
                """
                hunter2
                login: parham
                url: https://github.com
                otpauth://totp/GitHub:parham?secret=JBSWY3DPEHPK3PXP&issuer=GitHub

                recovery codes are in the safe
                """.trimIndent(),
            )

        assertThat(entry.password).isEqualTo("hunter2")
        assertThat(entry.username).isEqualTo("parham")
        assertThat(entry.url).isEqualTo("https://github.com")
        assertThat(entry.otpUri).startsWith("otpauth://totp/GitHub")
        assertThat(entry.notes).isEqualTo("recovery codes are in the safe")
    }

    @Test
    fun `a password alone is a whole entry`() {
        val entry = PassEntry.parse("only-a-password\n")
        assertThat(entry.password).isEqualTo("only-a-password")
        assertThat(entry.fields).isEmpty()
        assertThat(entry.notes).isEmpty()
    }

    @Test
    fun `gopass yaml body is read as fields`() {
        val entry = PassEntry.parse("pw\n---\nusername: \"me@example.com\"\npin: 1234\n")
        assertThat(entry.username).isEqualTo("me@example.com")
        assertThat(entry.fields.single { it.key == "pin" }.isSecret).isTrue()
    }

    @Test
    fun `a bare url line is a note, not a field called https`() {
        val entry = PassEntry.parse("pw\nhttps://example.com/login\n")
        assertThat(entry.fields).isEmpty()
        assertThat(entry.notes).isEqualTo("https://example.com/login")
    }

    @Test
    fun `an otp stored as a field is recognised and kept where it was`() {
        val entry = PassEntry.parse("pw\ntotp: otpauth://totp/x?secret=JBSWY3DPEHPK3PXP\n")
        assertThat(entry.otp).isEqualTo("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP")
        assertThat(entry.otpUri).isNull()
        assertThat(entry.format()).isEqualTo("pw\ntotp: otpauth://totp/x?secret=JBSWY3DPEHPK3PXP\n")
    }

    @Test
    fun `a bare base32 totp secret is enough, as in gopass`() {
        val entry = PassEntry.parse("pw\ntotp: JBSW Y3DP EHPK 3PXP\n")
        val totp = Totp.parse(entry.otp!!)
        assertThat(totp).isNotNull()
        assertThat(totp!!.code(0)).hasLength(6)
    }

    @Test
    fun `gopass references on the password line and in fields`() {
        val entry = PassEntry.parse("gopass://shared/wifi/home\nbackup: gopass://bank/mellat/\nnote: see gopass docs\n")
        assertThat(entry.reference).isEqualTo("shared/wifi/home")
        assertThat(entry.fields.first { it.key == "backup" }.reference).isEqualTo("bank/mellat")
        assertThat(entry.fields.first { it.key == "note" }.reference).isNull()
        assertThat(PassEntry.parse("plain\n").reference).isNull()
    }

    @Test
    fun `yaml block scalars span lines`() {
        val entry =
            PassEntry.parse(
                """
                pw
                ---
                user: parham
                recovery: |
                  aaaa-bbbb
                  cccc-dddd

                  eeee-ffff
                address: >
                  1 Main
                  Street
                trailing: |-
                  x
                """.trimIndent(),
            )
        assertThat(entry.yaml).isTrue()
        assertThat(entry.username).isEqualTo("parham")
        // `|` and `>` keep one final newline (YAML's "clip"), `|-` none.
        assertThat(entry.fields.first { it.key == "recovery" }.value).isEqualTo("aaaa-bbbb\ncccc-dddd\n\neeee-ffff\n")
        assertThat(entry.fields.first { it.key == "address" }.value).isEqualTo("1 Main Street\n")
        assertThat(entry.fields.first { it.key == "trailing" }.value).isEqualTo("x")
    }

    @Test
    fun `nested yaml is kept verbatim and round-trips`() {
        val text =
            "pw\n---\nlogin: me\nquestions:\n  - q: pet\n    a: cat\n  - q: city\n    a: Tehran\nurl: https://x\n"
        val entry = PassEntry.parse(text)
        val nested = entry.fields.first { it.key == "questions" }
        assertThat(nested.nested).isTrue()
        assertThat(entry.url).isEqualTo("https://x")
        assertThat(entry.format()).isEqualTo(text)
    }

    @Test
    fun `yaml round-trips multi-line values and values that need quotes`() {
        val entry =
            PassEntry(
                password = "pw",
                fields =
                    listOf(
                        PassEntry.Field("recovery", "one\ntwo\n\nthree"),
                        PassEntry.Field("answer", "yes"),
                        PassEntry.Field("hint", "a: b # c"),
                        PassEntry.Field("pin", "0042"),
                    ),
                otpUri = null,
                notes = "",
                yaml = true,
            )
        assertThat(PassEntry.parse(entry.format())).isEqualTo(entry)
        assertThat(entry.format()).contains("pin: \"0042\"")
    }

    @Test
    fun `an edited yaml field is written back as yaml, in place`() {
        val entry = PassEntry.parse("pw\n---\nusername: me\npin: \"0042\"\nnote: |\n  a\n  b\n")
        val edited =
            entry.copy(
                fields =
                    entry.fields.map { if (it.key == "pin") it.copy(value = "0043") else it } +
                        PassEntry.Field("hint", "yes"),
            )

        assertThat(
            edited.format(),
        ).isEqualTo("pw\n---\nusername: me\npin: \"0043\"\nnote: |\n  a\n  b\nhint: \"yes\"\n")
        assertThat(PassEntry.parse(edited.format())).isEqualTo(edited)
    }

    @Test
    fun `format round-trips`() {
        val original = PassEntry.parse("pw\nlogin: a\nurl: b\notpauth://totp/x?secret=AA\nsome\nnotes\n")
        assertThat(PassEntry.parse(original.format())).isEqualTo(original)
    }

    @Test
    fun `windows line endings do not leak into the password`() {
        assertThat(PassEntry.parse("pw\r\nlogin: a\r\n").password).isEqualTo("pw")
    }

    // --- gopass fidelity -------------------------------------------------

    private fun assertVerbatim(text: String) {
        val entry = PassEntry.parse(text)
        assertThat(entry.format()).isEqualTo(text)
        // Without its layout the entry still reads back equal.
        val fresh = entry.copy(layout = PassEntry.Layout.NONE)
        assertThat(PassEntry.parse(fresh.format())).isEqualTo(fresh)
    }

    @Test
    fun `desktop files come back byte for byte`() {
        listOf(
            // key-value, in the order people write it
            "pw\nfree text first\nlogin: x\notpauth://totp/x?secret=JBSWY3DPEHPK3PXP\n",
            "pw\notpauth://totp/x?secret=AA\n\nnote\nlogin: me\nurl:  https://x  \n",
            "pw\nlogin: a\nlogin: b\n",
            "pw\n\n\nleading and trailing blanks\n\n",
            "pw\nno final newline: here",
            "pw",
            "",
            "pw\n",
            "pw\nhttps://example.com\npath: //nas/share\n",
            "pw\nنام کاربری: پرهام\nsecurity question (1)?: cat\n",
            "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n",
            // YAML
            "pw\n---\nuser: me\n",
            "pw\nsome note\notpauth://totp/x?secret=AA\n---\nuser: me # mine\npin: \"0042\"\n",
            "pw\n---\n# comment\nuser: me\n\nrecovery: |\n  a\n  b\n\nempty:\nkeep: |+\n  x\n\n",
            "pw\n---\nfolded: >-\n  one\n  two\nquoted: 'it''s'\nesc: \"a\\tb\"\n",
            "pw\n---\ntags:\n- a\n- b\nnext: x\n",
            "pw\n---\nlogin: me\nquestions:\n  - q: pet\n    a: cat\nurl: https://x\nstray text\n",
            "pw\n---\nlong: a plain\n  value that wraps\n\"true\": quoted key\n",
            "pw\n--- # gopass\nindented: |2\n    two extra\n  base\n",
        ).forEach(::assertVerbatim)
    }

    @Test
    fun `an edited value is rewritten in place and the rest is untouched`() {
        val text = "pw\nfree text\nlogin: old\notpauth://totp/x?secret=AA\nmore text\n"
        val entry = PassEntry.parse(text)
        val edited = entry.copy(fields = listOf(PassEntry.Field("login", "new")))
        assertThat(edited.format()).isEqualTo("pw\nfree text\nlogin: new\notpauth://totp/x?secret=AA\nmore text\n")
        assertThat(entry.copy(password = "pw2").format()).isEqualTo("pw2" + text.removePrefix("pw"))
    }

    @Test
    fun `added fields follow the last field and removed ones disappear`() {
        val entry = PassEntry.parse("pw\nnote\nlogin: me\nurl: x\notpauth://totp/x?secret=AA\n")
        val added = entry.copy(fields = entry.fields + PassEntry.Field("pin", "1"))
        assertThat(added.format()).isEqualTo("pw\nnote\nlogin: me\nurl: x\npin: 1\notpauth://totp/x?secret=AA\n")
        val removed = entry.copy(fields = entry.fields.filterNot { it.key == "login" })
        assertThat(removed.format()).isEqualTo("pw\nnote\nurl: x\notpauth://totp/x?secret=AA\n")
        val fromNothing = PassEntry.parse("pw\nonly a note\n").copy(fields = listOf(PassEntry.Field("login", "me")))
        assertThat(fromNothing.format()).isEqualTo("pw\nlogin: me\nonly a note\n")
    }

    @Test
    fun `changed notes and otp replace the old ones`() {
        val entry = PassEntry.parse("pw\nold note\nlogin: me\notpauth://totp/x?secret=AA\n")
        assertThat(entry.copy(notes = "new\nnote").format())
            .isEqualTo("pw\nlogin: me\notpauth://totp/x?secret=AA\nnew\nnote\n")
        assertThat(entry.copy(otpUri = "otpauth://totp/y?secret=BB").format())
            .isEqualTo("pw\nold note\nlogin: me\notpauth://totp/y?secret=BB\n")
        assertThat(entry.copy(otpUri = null).format()).isEqualTo("pw\nold note\nlogin: me\n")
        val noOtp = PassEntry.parse("pw\nlogin: me\nnote\n").copy(otpUri = "otpauth://totp/z?secret=CC")
        assertThat(noOtp.format()).isEqualTo("pw\nlogin: me\notpauth://totp/z?secret=CC\nnote\n")
    }

    @Test
    fun `yaml edits keep the body before the separator`() {
        val entry = PassEntry.parse("pw\nbody\n---\nuser: me\npin: 1\n")
        assertThat(entry.copy(fields = entry.fields + PassEntry.Field("url", "https://x")).format())
            .isEqualTo("pw\nbody\n---\nuser: me\npin: 1\nurl: https://x\n")
        assertThat(entry.copy(notes = "changed", otpUri = "otpauth://totp/x?secret=AA").format())
            .isEqualTo("pw\notpauth://totp/x?secret=AA\nchanged\n---\nuser: me\npin: 1\n")
        val edited = entry.copy(fields = listOf(PassEntry.Field("user", "true"), PassEntry.Field("pin", "1")))
        assertThat(edited.format()).isEqualTo("pw\nbody\n---\nuser: \"true\"\npin: 1\n")
    }

    @Test
    fun `new yaml entries put notes and otp before the separator, as gopass does`() {
        val entry =
            PassEntry(
                password = "pw",
                fields = listOf(PassEntry.Field("user", "me")),
                otpUri = "otpauth://totp/x?secret=AA",
                notes = "a note",
                yaml = true,
            )
        assertThat(entry.format()).isEqualTo("pw\notpauth://totp/x?secret=AA\na note\n---\nuser: me\n")
        assertThat(PassEntry.parse(entry.format())).isEqualTo(entry)
    }

    @Test
    fun `free text before the separator is the body, not fields`() {
        val entry = PassEntry.parse("pw\nsome note\nlogin: not-a-field\notpauth://totp/x?secret=AA\n---\nuser: me\n")
        assertThat(entry.yaml).isTrue()
        assertThat(entry.notes).isEqualTo("some note\nlogin: not-a-field")
        assertThat(entry.otpUri).isEqualTo("otpauth://totp/x?secret=AA")
        assertThat(entry.fields).containsExactly(PassEntry.Field("user", "me"))
    }

    @Test
    fun `a separator followed by no mapping is not yaml`() {
        val entry = PassEntry.parse("pw\n---\njust text\n")
        assertThat(entry.yaml).isFalse()
        assertThat(entry.notes).isEqualTo("---\njust text")
    }

    @Test
    fun `yaml comments, escapes and quoted keys are read like yaml`() {
        val entry =
            PassEntry.parse(
                "pw\n---\npin: 1234 # old\nhash: a#b\n" +
                    "esc: \"tab\\there\\nnl \\\"q\\\" \\\\ \\/ \\u00e9 \\x41 \\0\" # c\n" +
                    "single: 'it''s # not a comment'\n\"true\": yes\n'123': n\n",
            )

        fun value(key: String) = entry.fields.single { it.key == key }.value
        assertThat(value("pin")).isEqualTo("1234")
        assertThat(value("hash")).isEqualTo("a#b")
        assertThat(value("esc")).isEqualTo("tab\there\nnl \"q\" \\ / é A \u0000")
        assertThat(value("single")).isEqualTo("it's # not a comment")
        assertThat(value("true")).isEqualTo("yes")
        assertThat(value("123")).isEqualTo("n")
    }

    @Test
    fun `values and keys that yaml would misread are quoted, and read back the same`() {
        val awkward =
            listOf(
                "ends with:",
                "tab\there",
                "bell\u0007",
                ".inf",
                "-.inf",
                ".NaN",
                "~",
                " leading",
                "trailing ",
                "0042",
                "true",
                "null",
                "a: b",
                "x #y",
                "#hash",
                "- dash",
                "",
                "\n",
                "line\r",
                "نام",
            )
        val entry =
            PassEntry(
                password = "pw",
                fields =
                    awkward.map { PassEntry.Field("k$it", it) } +
                        listOf("true", "123", "a: b", "-x", "ends:").map { PassEntry.Field(it, "v") },
                otpUri = null,
                notes = "",
                yaml = true,
            )
        val text = entry.format()
        assertThat(PassEntry.parse(text)).isEqualTo(entry)
        assertThat(text).contains("\n\"ktab\\there\": \"tab\\there\"\n")
        assertThat(text).contains("\n\"true\": v\n")
        assertThat(text).contains("\n\"123\": v\n")
        assertThat(text).contains("\nkنام: نام\n")
    }

    @Test
    fun `block chomping is kept so gopass reads the same bytes`() {
        val entry =
            PassEntry.parse(
                "pw\n---\nstrip: |-\n  x\n  y\nclip: |\n  x\nkeep: |+\n  x\n\n\nfolded: >-\n  a\n  b\n\n  c\n" +
                    "indicator: |2-\n    deeper\n  base\n",
            )

        fun value(key: String) = entry.fields.single { it.key == key }.value
        assertThat(value("strip")).isEqualTo("x\ny")
        assertThat(value("clip")).isEqualTo("x\n")
        assertThat(value("keep")).isEqualTo("x\n\n\n")
        assertThat(value("folded")).isEqualTo("a b\nc")
        assertThat(value("indicator")).isEqualTo("  deeper\nbase")

        // Rewritten from scratch, each one picks the header that keeps its bytes.
        val fresh = entry.copy(layout = PassEntry.Layout.NONE)
        val text = fresh.format()
        assertThat(text).contains("strip: |-\n  x\n  y\n")
        assertThat(text).contains("clip: |\n  x\n")
        assertThat(text).contains("keep: |+\n  x\n\n\n")
        assertThat(text).contains("folded: |-\n  a b\n  c\n")
        assertThat(text).contains("indicator: |2-\n    deeper\n  base\n")
        assertThat(PassEntry.parse(text)).isEqualTo(fresh)
    }

    @Test
    fun `a compact list under an empty key is the key's value, not notes`() {
        val entry = PassEntry.parse("pw\n---\ntags:\n- a\n- b\nuser: me\n")
        assertThat(entry.notes).isEmpty()
        assertThat(
            entry.fields.first { it.key == "tags" },
        ).isEqualTo(PassEntry.Field("tags", "- a\n- b", nested = true))
        assertThat(entry.username).isEqualTo("me")
    }

    @Test
    fun `key-value keys follow gopass`() {
        val entry =
            PassEntry.parse(
                "pw\nنام کاربری: پرهام\nsecurity question (what pet?): cat\npath: //nas/share\nhttps://x\n",
            )
        assertThat(entry.fields.map { it.key })
            .containsExactly("نام کاربری", "security question (what pet?)", "path")
            .inOrder()
        assertThat(entry.fields.last().value).isEqualTo("//nas/share")
        assertThat(entry.notes).isEqualTo("https://x")
        // Indented lines and keys with a colon are free text.
        assertThat(PassEntry.parse("pw\n  indented: x\na:b: c\n").fields).isEmpty()

        assertThat(PassEntry.isValidKey("نام کاربری")).isTrue()
        assertThat(PassEntry.isValidKey("question (1)?")).isTrue()
        assertThat(PassEntry.isValidKey("a much longer key than forty characters, written by a person")).isTrue()
        assertThat(PassEntry.isValidKey("")).isFalse()
        assertThat(PassEntry.isValidKey(" lead")).isFalse()
        assertThat(PassEntry.isValidKey("trail ")).isFalse()
        assertThat(PassEntry.isValidKey("a:b")).isFalse()
        assertThat(PassEntry.isValidKey("two\nlines")).isFalse()
    }

    @Test
    fun `otp is looked up in gopass's order`() {
        val body = "otpauth://totp/body?secret=JBSWY3DPEHPK3PXP"
        assertThat(PassEntry.parse("pw\ntotp: JBSWY3DPEHPK3PXPJBSW\n$body\notpauth: //totp/field?secret=AA\n").otp)
            .isEqualTo("otpauth://totp/field?secret=AA")
        assertThat(PassEntry.parse("pw\ntotp: otpauth://totp/t?secret=AA\n$body\n").otp).isEqualTo(body)
        assertThat(PassEntry.parse("pw\nhotp: otpauth://hotp/h?secret=AA\ntotp: otpauth://totp/t?secret=AA\n").otp)
            .isEqualTo("otpauth://totp/t?secret=AA")
        assertThat(PassEntry.parse("pw\nhotp: otpauth://hotp/h?secret=AA&counter=1\n").otp)
            .isEqualTo("otpauth://hotp/h?secret=AA&counter=1")
    }

    @Test
    fun `legacy gopass mime secrets are read`() {
        val entry =
            PassEntry.parse(
                "GOPASS-SECRET-1.0\nPassword: hunter2\nLogin: parham\nUrl: https://x\n\nfree text\n",
            )
        assertThat(entry.password).isEqualTo("hunter2")
        assertThat(entry.username).isEqualTo("parham")
        assertThat(entry.url).isEqualTo("https://x")
        assertThat(entry.notes).isEqualTo("free text")
        // Saved back the way gopass converts it.
        assertThat(entry.format()).isEqualTo("hunter2\nlogin: parham\nurl: https://x\nfree text\n")
    }

    @Test
    fun `sso entries have a login method and no password`() {
        val sso = PassEntry.parse("\nurl: https://figma.com\nusername: me@example.com\nlogin-method: google\n")
        assertThat(sso.loginMethod).isEqualTo("google")
        assertThat(sso.isPasswordless).isTrue()
        val both = PassEntry.parse("pw\nlogin-method: sso\n")
        assertThat(both.loginMethod).isEqualTo("sso")
        assertThat(both.isPasswordless).isFalse()
        assertThat(PassEntry.parse("\nlogin: me\n").isPasswordless).isFalse()
        assertThat(PassEntry.parse("pw\n---\nlogin-method:\n- a\n").loginMethod).isNull()
    }

    @Test
    fun `entries compare by content, not layout`() {
        val parsed = PassEntry.parse("pw\nlogin: me\n")
        assertThat(parsed).isEqualTo(PassEntry("pw", listOf(PassEntry.Field("login", "me")), null, ""))
        assertThat(parsed.hashCode())
            .isEqualTo(PassEntry("pw", listOf(PassEntry.Field("login", "me")), null, "").hashCode())
    }
}
