package me.parham1995.passwords.store

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LoginMethodsTest {
    @Test
    fun `gopass's template options read as provider names`() {
        assertThat(LoginMethods.label("google")).isEqualTo("Google")
        assertThat(LoginMethods.label("apple")).isEqualTo("Apple")
        assertThat(LoginMethods.label("github")).isEqualTo("GitHub")
        assertThat(LoginMethods.label("microsoft")).isEqualTo("Microsoft")
        assertThat(LoginMethods.label("sso")).isEqualTo("SSO")
        assertThat(LoginMethods.label("magic-link")).isEqualTo("Magic link")
    }

    @Test
    fun `case and spaces do not matter, and anything else is shown as written`() {
        assertThat(LoginMethods.label(" GitHub ")).isEqualTo("GitHub")
        assertThat(LoginMethods.label("Okta")).isEqualTo("Okta")
        assertThat(LoginMethods.signIn("google")).isEqualTo("Sign in with Google")
    }

    @Test
    fun `a suggestion names the account and the provider`() {
        assertThat(LoginMethods.suggestion("me@x.com", "google")).isEqualTo("me@x.com · Google")
        assertThat(LoginMethods.suggestion("", "sso")).isEqualTo("SSO")
    }

    @Test
    fun `the label applies to what the SSO template writes`() {
        val entry = PassEntry.parse("\nurl: https://x.com\nusername: me@x.com\nlogin-method: magic-link\n")
        assertThat(entry.isPasswordless).isTrue()
        assertThat(LoginMethods.label(entry.loginMethod!!)).isEqualTo("Magic link")
    }
}
