package me.parham1995.passwords.store

/**
 * What a scanned QR code means for an entry's one-time code. A site that
 * turns on two-factor authentication shows an `otpauth://totp/...` URI as a
 * QR code; anything else the camera sees is said plainly, so a wrong code is
 * never saved as the entry's OTP.
 */
object OtpQr {
    sealed interface Result {
        /** A URI [Totp] reads, trimmed, to store as it is. */
        data class Usable(
            val uri: String,
            val totp: Totp,
        ) : Result

        data class Unusable(
            val reason: String,
        ) : Result
    }

    fun read(text: String): Result {
        val trimmed = text.trim()
        val scheme = trimmed.substringBefore(':').lowercase()
        val kind =
            trimmed
                .substringAfter("://", "")
                .substringBefore('/')
                .substringBefore('?')
                .lowercase()
        return when {
            scheme == "otpauth-migration" ->
                Result.Unusable("This is an authenticator export with many accounts. Scan the site's own QR code.")
            scheme != "otpauth" -> Result.Unusable("This QR code does not hold a one-time code.")
            kind == "hotp" -> Result.Unusable("This is a counter-based (HOTP) code. Only time-based codes work here.")
            else -> {
                // QR codes in alphanumeric mode are upper case, but an entry's
                // OTP line is found by a lower-case `otpauth://` on the desktop
                // and on the phone alike.
                val uri = "otpauth" + trimmed.substring(scheme.length)
                Totp.parse(uri)?.let { Result.Usable(uri, it) }
                    ?: Result.Unusable("This one-time code QR is not one this app can read.")
            }
        }
    }
}
