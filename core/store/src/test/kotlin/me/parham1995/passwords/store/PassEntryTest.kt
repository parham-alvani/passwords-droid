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
        assertThat(entry.fields.first { it.key == "recovery" }.value).isEqualTo("aaaa-bbbb\ncccc-dddd\n\neeee-ffff")
        assertThat(entry.fields.first { it.key == "address" }.value).isEqualTo("1 Main Street")
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
    fun `format round-trips`() {
        val original = PassEntry.parse("pw\nlogin: a\nurl: b\notpauth://totp/x?secret=AA\nsome\nnotes\n")
        assertThat(PassEntry.parse(original.format())).isEqualTo(original)
    }

    @Test
    fun `windows line endings do not leak into the password`() {
        assertThat(PassEntry.parse("pw\r\nlogin: a\r\n").password).isEqualTo("pw")
    }
}
