package me.parham1995.passwords.store

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OtpQrTest {
    @Test
    fun `keeps a totp uri as it was scanned, trimmed`() {
        val uri = "otpauth://totp/GitHub:parham?secret=JBSWY3DPEHPK3PXP&issuer=GitHub"
        val result = OtpQr.read("  $uri\n") as OtpQr.Result.Usable
        assertThat(result.uri).isEqualTo(uri)
        assertThat(result.totp.issuer).isEqualTo("GitHub")
    }

    @Test
    fun `stores an upper-case scheme in lower case, where a pass entry looks for it`() {
        val result = OtpQr.read("OTPAUTH://TOTP/X?SECRET=JBSWY3DPEHPK3PXP") as OtpQr.Result.Usable
        assertThat(result.uri).isEqualTo("otpauth://TOTP/X?SECRET=JBSWY3DPEHPK3PXP")
        assertThat(PassEntry.parse("pw\n${result.uri}\n").otp).isEqualTo(result.uri)
    }

    @Test
    fun `refuses what is not a usable time-based code`() {
        listOf(
            "otpauth-migration://offline?data=CjEKCkhlbGxvId6tvu8",
            "otpauth://hotp/x?secret=JBSWY3DPEHPK3PXP&counter=1",
            "otpauth://totp/x",
            "https://example.com/2fa",
            "WIFI:T:WPA;S:home;P:secret;;",
        ).forEach { assertThat(OtpQr.read(it)).isInstanceOf(OtpQr.Result.Unusable::class.java) }
    }
}
