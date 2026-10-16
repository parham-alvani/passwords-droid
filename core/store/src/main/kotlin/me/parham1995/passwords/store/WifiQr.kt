package me.parham1995.passwords.store

/**
 * The text of a Wi-Fi QR code, in the `WIFI:` format phone cameras read to
 * join a network without typing (originally ZXing's, now what Android and iOS
 * both generate and scan).
 *
 * `\ ; , : "` are escaped: a password with a `;` in it would otherwise end the
 * field early and the guest's phone would join with the wrong password.
 */
object WifiQr {
    enum class Security(
        val code: String,
    ) {
        WPA("WPA"),
        WEP("WEP"),
        OPEN("nopass"),
    }

    data class Network(
        val ssid: String,
        val password: String,
        val security: Security,
        val hidden: Boolean = false,
    )

    fun payload(network: Network): String =
        buildString {
            append("WIFI:")
            append("T:").append(network.security.code).append(';')
            append("S:").append(escape(network.ssid)).append(';')
            if (network.security != Security.OPEN) append("P:").append(escape(network.password)).append(';')
            if (network.hidden) append("H:true;")
            append(';')
        }

    /**
     * What an entry says about its network. The SSID is the `ssid` field when
     * there is one -- entry names are tidied (`wifi/parham-main`) while SSIDs
     * are what the router broadcasts -- and the entry's name otherwise. An
     * empty password means an open network.
     */
    fun networkOf(
        name: String,
        entry: PassEntry,
    ): Network {
        fun field(vararg keys: String) =
            entry.fields
                .firstOrNull { it.key.lowercase() in keys && !it.nested }
                ?.value
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        val ssid = field("ssid", "network", "name") ?: name.substringAfterLast('/')
        val security =
            when (field("security", "type", "encryption")?.lowercase()) {
                "wep" -> Security.WEP
                "open", "none", "nopass" -> Security.OPEN
                else -> if (entry.password.isEmpty()) Security.OPEN else Security.WPA
            }
        val hidden = field("hidden")?.lowercase() in setOf("true", "yes", "1")
        return Network(ssid, entry.password, security, hidden)
    }

    /** Entries that are Wi-Fi networks: under a `wifi` folder, or carrying an `ssid` field. */
    fun isWifi(
        name: String,
        entry: PassEntry,
    ): Boolean =
        name.split('/').dropLast(1).any { it.equals("wifi", ignoreCase = true) } ||
            entry.fields.any { it.key.equals("ssid", ignoreCase = true) }

    private fun escape(value: String): String =
        buildString {
            value.forEach { char ->
                if (char in SPECIAL) append('\\')
                append(char)
            }
        }

    private const val SPECIAL = "\\;,:\""
}
