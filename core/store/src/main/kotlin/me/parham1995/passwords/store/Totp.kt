package me.parham1995.passwords.store

import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A time-based one-time password generator (RFC 6238), configured by an
 * `otpauth://totp/...` URI as authenticator apps and gopass write it.
 *
 * Only TOTP. HOTP needs a counter written back to the store after every use,
 * which turns reading a code into a commit; none of the accounts in a pass
 * store use it in practice.
 */
class Totp private constructor(
    private val secret: ByteArray,
    val digits: Int,
    val periodSeconds: Long,
    val algorithm: String,
    val issuer: String?,
    val account: String?,
) {
    fun code(epochMillis: Long): String {
        val counter = epochMillis / MILLIS_PER_SECOND / periodSeconds
        val mac = Mac.getInstance(algorithm).apply { init(SecretKeySpec(secret, algorithm)) }
        val hash = mac.doFinal(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(counter).array())
        val offset = hash.last().toInt() and LOW_NIBBLE
        val binary =
            (hash[offset].toInt() and 0x7f shl 24) or
                (hash[offset + 1].toInt() and 0xff shl 16) or
                (hash[offset + 2].toInt() and 0xff shl 8) or
                (hash[offset + 3].toInt() and 0xff)
        val modulo = POWERS[digits]
        return (binary % modulo).toString().padStart(digits, '0')
    }

    /** Seconds until [code] changes, for the countdown next to it. */
    fun secondsRemaining(epochMillis: Long): Long = periodSeconds - (epochMillis / MILLIS_PER_SECOND) % periodSeconds

    companion object {
        private const val MILLIS_PER_SECOND = 1000L
        private const val LOW_NIBBLE = 0x0f
        private const val DEFAULT_DIGITS = 6
        private const val DEFAULT_PERIOD = 30L
        private val POWERS =
            IntArray(10) {
                var p = 1
                repeat(it) { p *= 10 }
                p
            }

        /** Null when [uri] is not a TOTP URI this can use. */
        fun parse(uri: String): Totp? =
            runCatching {
                // Pasted secrets often keep the spaces they were displayed with.
                val parsed = URI(uri.trim().replace(" ", "%20"))
                if (parsed.scheme != "otpauth" || parsed.host?.lowercase() != "totp") return null
                val query =
                    parsed.rawQuery
                        .orEmpty()
                        .split('&')
                        .filter { '=' in it }
                        .associate { part ->
                            val (k, v) = part.split('=', limit = 2)
                            k.lowercase() to URLDecoder.decode(v, Charsets.UTF_8)
                        }
                val label = parsed.path.orEmpty().removePrefix("/")
                val digits = query["digits"]?.toInt() ?: DEFAULT_DIGITS
                require(digits in 6..9) { "digits out of range" }
                Totp(
                    secret = Base32.decode(query.getValue("secret")),
                    digits = digits,
                    periodSeconds = query["period"]?.toLong()?.takeIf { it > 0 } ?: DEFAULT_PERIOD,
                    algorithm =
                        when (query["algorithm"]?.uppercase()) {
                            "SHA256" -> "HmacSHA256"
                            "SHA512" -> "HmacSHA512"
                            else -> "HmacSHA1"
                        },
                    issuer = query["issuer"] ?: label.substringBefore(':', "").ifEmpty { null },
                    account = label.substringAfter(':').ifEmpty { null },
                )
            }.getOrNull()
    }
}

/** RFC 4648 base32, forgiving of the spaces, lowercase and missing padding people paste. */
internal object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val BITS_PER_CHAR = 5
    private const val BITS_PER_BYTE = 8

    fun decode(text: String): ByteArray {
        val clean = text.uppercase().filterNot { it == ' ' || it == '-' || it == '=' }
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        clean.forEach { char ->
            val value = ALPHABET.indexOf(char)
            require(value >= 0) { "not base32: $char" }
            buffer = (buffer shl BITS_PER_CHAR) or value
            bits += BITS_PER_CHAR
            if (bits >= BITS_PER_BYTE) {
                out.write(buffer shr (bits - BITS_PER_BYTE) and 0xff)
                bits -= BITS_PER_BYTE
            }
        }
        return out.toByteArray()
    }
}
