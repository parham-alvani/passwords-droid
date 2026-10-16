package me.parham1995.passwords.store

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DocumentsTest {
    @Test
    fun `a pem stored as text is a document, not a password`() {
        val pem = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n"
        val doc = Documents.of("vault/clients/vso.pem", pem)!!
        assertThat(doc.fileName).isEqualTo("vso.pem")
        assertThat(String(doc.bytes)).isEqualTo(pem)
        assertThat(doc.attachment).isFalse()
    }

    @Test
    fun `a file-named entry is a document even without a pem header`() {
        assertThat(Documents.of("vpn/me.ovpn", "client\ndev tun\n")?.fileName).isEqualTo("me.ovpn")
    }

    @Test
    fun `gopass attachments round-trip`() {
        val bytes = byteArrayOf(0, 1, 2, -1, 10, 13)
        val text = Documents.attachment("key.p12", bytes)
        val doc = Documents.of("certs/key.p12", text)!!
        assertThat(doc.attachment).isTrue()
        assertThat(doc.fileName).isEqualTo("key.p12")
        assertThat(doc.bytes).isEqualTo(bytes)
    }

    @Test
    fun `an ordinary login is not a document`() {
        assertThat(Documents.of("github.com/me", "hunter2\nlogin: me\n")).isNull()
    }
}
