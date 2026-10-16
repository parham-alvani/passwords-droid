package me.parham1995.passwords.store

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PasswordHealthTest {
    private val now = 2_000_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `finds weak, reused and old passwords`() {
        val report =
            PasswordHealth.analyze(
                listOf(
                    PasswordHealth.Candidate("bank/a", "password123", now),
                    PasswordHealth.Candidate("shop/b", "Tr0ub4dor&3-horse-battery-staple-xyzzy", now - 1000 * day),
                    PasswordHealth.Candidate("shop/c", "Tr0ub4dor&3-horse-battery-staple-xyzzy", now - 10 * day),
                    PasswordHealth.Candidate("empty", "", null),
                ),
                now,
            )
        assertThat(report.checked).isEqualTo(3)
        assertThat(report.weak.map { it.name }).containsExactly("bank/a")
        assertThat(report.reused).containsExactly(listOf("shop/b", "shop/c"))
        assertThat(report.old.map { it.first }).containsExactly("shop/b")
    }

    @Test
    fun `pwned range responses are matched by suffix`() {
        val hash = Pwned.sha1("password")
        assertThat(hash).isEqualTo("5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8")
        val response = "0018A45C4D1DEF81644B54AB7F969B88D65:1\r\n1E4C9B93F3F0682250B6CF8331B7EE68FD8:9659365\r\n"
        assertThat(Pwned.count(hash, response)).isEqualTo(9_659_365)
        assertThat(Pwned.count(Pwned.sha1("not-in-the-list"), response)).isEqualTo(0)
    }
}
