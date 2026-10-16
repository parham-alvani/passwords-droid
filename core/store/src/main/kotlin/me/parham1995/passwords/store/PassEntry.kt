package me.parham1995.passwords.store

/**
 * The decrypted contents of one `.gpg` file, read the way `pass` and gopass
 * write it.
 *
 * The first line is the password and nothing else. What follows is a mix of
 * `key: value` lines, an optional `otpauth://` URI, and free text. gopass can
 * also put a YAML document after a `---` line; its top-level scalars are read
 * as fields here, which is all a phone needs from them.
 *
 * Parsing never throws. A file nobody can make sense of is still a password
 * on its first line and notes after it, which is how `pass show` treats it.
 */
data class PassEntry(
    val password: String,
    val fields: List<Field>,
    val otpUri: String?,
    val notes: String,
) {
    data class Field(
        val key: String,
        val value: String,
    ) {
        /** A field whose value should be hidden until asked for, like the password itself. */
        val isSecret: Boolean get() = key.lowercase() in SECRET_KEYS
    }

    /** The username, from the first field that conventionally holds one. */
    val username: String? get() = field(USERNAME_KEYS)

    /** The site, from the first field that conventionally holds one. */
    val url: String? get() = field(URL_KEYS)

    private fun field(keys: Set<String>): String? = fields.firstOrNull { it.key.lowercase() in keys }?.value

    companion object {
        private val USERNAME_KEYS = setOf("login", "username", "user", "email")
        private val URL_KEYS = setOf("url", "website", "site", "uri")
        private val SECRET_KEYS = setOf("pin", "cvv", "cvc", "secret", "recovery", "passphrase")
        private val OTP_KEYS = setOf("otpauth", "totp")
        private val FIELD = Regex("""^([A-Za-z0-9][A-Za-z0-9 _.-]{0,40}):\s*(.*)$""")
        private const val OTP_SCHEME = "otpauth://"
        private const val YAML_SEPARATOR = "---"

        fun parse(text: String): PassEntry {
            val lines = text.replace("\r\n", "\n").split('\n')
            val password = lines.first()
            val fields = mutableListOf<Field>()
            val notes = mutableListOf<String>()
            var otp: String? = null

            lines.drop(1).forEach { line ->
                val trimmed = line.trim()
                when {
                    trimmed.startsWith(OTP_SCHEME) -> otp = otp ?: trimmed
                    trimmed == YAML_SEPARATOR && fields.isEmpty() && notes.all { it.isBlank() } -> Unit
                    else -> {
                        val match = FIELD.matchEntire(trimmed)
                        // A URL-looking value on its own ("https://x") would
                        // match as a field called "https"; it is a note.
                        if (match != null && !match.groupValues[2].startsWith("//")) {
                            val (key, value) = match.destructured
                            if (key.lowercase() in OTP_KEYS && value.trim().startsWith(OTP_SCHEME)) {
                                otp = otp ?: value.trim()
                            } else {
                                fields += Field(key.trim(), value.trim().unquoted())
                            }
                        } else {
                            notes += line
                        }
                    }
                }
            }
            return PassEntry(
                password = password,
                fields = fields,
                otpUri = otp,
                notes = notes.joinToString("\n").trim('\n'),
            )
        }

        /** YAML lets a value be quoted; the quotes are not part of it. */
        private fun String.unquoted(): String =
            if (length >= 2 && (startsWith('"') && endsWith('"') || startsWith('\'') && endsWith('\''))) {
                substring(1, length - 1)
            } else {
                this
            }
    }

    /**
     * Writes the entry back in the same shape it is read in. Parsing what this
     * produces gives back an equal entry, which is what keeps an edit on the
     * phone from slowly rewriting a file made on the desktop.
     */
    fun format(): String =
        buildString {
            append(password)
            fields.forEach { append('\n').append(it.key).append(": ").append(it.value) }
            otpUri?.let { append('\n').append(it) }
            if (notes.isNotEmpty()) append('\n').append(notes)
            append('\n')
        }
}
