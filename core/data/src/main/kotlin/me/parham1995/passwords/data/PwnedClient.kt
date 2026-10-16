package me.parham1995.passwords.data

import me.parham1995.passwords.store.Pwned
import java.net.HttpURLConnection
import java.net.URI

/** Fetches one Have I Been Pwned range; an interface so tests need no network. */
fun interface PwnedClient {
    fun range(prefix: String): String

    companion object {
        /**
         * The real API. `Add-Padding` makes every response a similar size, so
         * someone watching the traffic cannot tell from its length which
         * prefix was asked for.
         */
        val HIBP =
            PwnedClient { prefix ->
                require(prefix.length == Pwned.PREFIX_LENGTH && prefix.all { it.isLetterOrDigit() })
                val connection =
                    URI(
                        "https://api.pwnedpasswords.com/range/$prefix",
                    ).toURL().openConnection() as HttpURLConnection
                try {
                    connection.setRequestProperty("Add-Padding", "true")
                    connection.setRequestProperty("User-Agent", "passwords-droid")
                    connection.connectTimeout = TIMEOUT
                    connection.readTimeout = TIMEOUT
                    connection.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    connection.disconnect()
                }
            }

        private const val TIMEOUT = 15_000
    }
}
