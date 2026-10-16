package me.parham1995.passwords.store

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WifiQrTest {
    @Test
    fun `a WPA network`() {
        val network = WifiQr.networkOf("wifi/parham-main", PassEntry.parse("s3cret\n"))
        assertThat(WifiQr.payload(network)).isEqualTo("WIFI:T:WPA;S:parham-main;P:s3cret;;")
    }

    @Test
    fun `special characters are escaped so the fields cannot run together`() {
        val network = WifiQr.Network("""Café;"5G"""", """a;b,c:d\e"f""", WifiQr.Security.WPA)
        assertThat(WifiQr.payload(network)).isEqualTo("""WIFI:T:WPA;S:Café\;\"5G\";P:a\;b\,c\:d\\e\"f;;""")
    }

    @Test
    fun `the ssid field wins over the entry name, and fields set security and hidden`() {
        val entry = PassEntry.parse("pw\nssid: NOBITEX Office\nsecurity: WEP\nhidden: yes\n")
        val network = WifiQr.networkOf("wifi/NOBITEX", entry)
        assertThat(network.ssid).isEqualTo("NOBITEX Office")
        assertThat(WifiQr.payload(network)).isEqualTo("""WIFI:T:WEP;S:NOBITEX Office;P:pw;H:true;;""")
    }

    @Test
    fun `no password is an open network, with no P field`() {
        val network = WifiQr.networkOf("wifi/guest", PassEntry.parse("\n"))
        assertThat(WifiQr.payload(network)).isEqualTo("WIFI:T:nopass;S:guest;;")
    }

    @Test
    fun `wifi entries are recognised by folder or field`() {
        assertThat(WifiQr.isWifi("wifi/guest", PassEntry.parse("x\n"))).isTrue()
        assertThat(WifiQr.isWifi("home/router", PassEntry.parse("x\nssid: Home\n"))).isTrue()
        assertThat(WifiQr.isWifi("bank/wifi", PassEntry.parse("x\n"))).isFalse()
    }
}
