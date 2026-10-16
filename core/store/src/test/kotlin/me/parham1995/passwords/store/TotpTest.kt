package me.parham1995.passwords.store

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TotpTest {
    // RFC 6238 appendix B, SHA-1, secret "12345678901234567890".
    private val rfc = "otpauth://totp/test?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&digits=8"

    @Test
    fun `matches the RFC 6238 test vectors`() {
        val totp = Totp.parse(rfc)!!
        assertThat(totp.code(59_000L)).isEqualTo("94287082")
        assertThat(totp.code(1_111_111_109_000L)).isEqualTo("07081804")
        assertThat(totp.code(2_000_000_000_000L)).isEqualTo("69279037")
    }

    @Test
    fun `reads issuer and account from the label`() {
        val totp = Totp.parse("otpauth://totp/GitHub:parham%40example.com?secret=jbsw y3dp ehpk 3pxp")!!
        assertThat(totp.issuer).isEqualTo("GitHub")
        assertThat(totp.account).isEqualTo("parham@example.com")
        assertThat(totp.digits).isEqualTo(6)
        assertThat(totp.code(0)).hasLength(6)
    }

    @Test
    fun `counts down to the next period`() {
        val totp = Totp.parse(rfc)!!
        assertThat(totp.secondsRemaining(59_000L)).isEqualTo(1)
        assertThat(totp.secondsRemaining(60_000L)).isEqualTo(30)
    }

    @Test
    fun `rejects what it cannot use`() {
        assertThat(Totp.parse("otpauth://hotp/x?secret=AA&counter=1")).isNull()
        assertThat(Totp.parse("https://example.com")).isNull()
        assertThat(Totp.parse("otpauth://totp/x")).isNull()
    }
}
