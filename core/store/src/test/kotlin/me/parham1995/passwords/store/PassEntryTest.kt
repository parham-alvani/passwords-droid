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
    fun `an otp stored as a field is recognised`() {
        val entry = PassEntry.parse("pw\ntotp: otpauth://totp/x?secret=JBSWY3DPEHPK3PXP\n")
        assertThat(entry.otpUri).isEqualTo("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP")
        assertThat(entry.fields).isEmpty()
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
