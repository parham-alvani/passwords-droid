package me.parham1995.passwords.store

/**
 * The decrypted contents of one `.gpg` file, read the way `pass` and gopass
 * write it.
 *
 * The first line is the password and nothing else. What follows comes in one
 * of gopass's two shapes:
 *
 * - **key-value** (gopass's default, and what `pass` users write by hand):
 *   `key: value` lines, an `otpauth://` URI on a line of its own, and free
 *   text, in any order.
 * - **YAML**, when the second line is `---`: a top-level mapping whose values
 *   may span lines (`key: |` and `key: >` block scalars, or a nested list or
 *   map, which is kept verbatim).
 *
 * Parsing never throws. A file nobody can make sense of is still a password
 * on its first line and notes after it, which is how `pass show` treats it.
 */
data class PassEntry(
    val password: String,
    val fields: List<Field>,
    val otpUri: String?,
    val notes: String,
    /** Read from, and written back as, a YAML body. */
    val yaml: Boolean = false,
) {
    data class Field(
        val key: String,
        val value: String,
        /**
         * A YAML value that is a nested list or map rather than a scalar. Kept
         * as its original indented text and written back unchanged, because a
         * phone has no business restructuring it.
         */
        val nested: Boolean = false,
    ) {
        /** A field whose value should be hidden until asked for, like the password itself. */
        val isSecret: Boolean get() = key.lowercase() in SECRET_KEYS

        val isMultiline: Boolean get() = '\n' in value

        /** The entry a `gopass://path` value points at, if it is one. */
        val reference: String? get() = referenceIn(value)
    }

    /** The username, from the first field that conventionally holds one. */
    val username: String? get() = field(USERNAME_KEYS)

    /** The site, from the first field that conventionally holds one. */
    val url: String? get() = field(URL_KEYS)

    /**
     * The entry whose password this one borrows: gopass's `gopass://path`
     * reference on the password line, followed when `core.follow-references`
     * is on.
     */
    val reference: String? get() = referenceIn(password)

    /**
     * The one-time-code configuration, wherever gopass would find it: an
     * `otpauth://` line, an `otpauth:` or `totp:` field holding a URI, or a
     * `totp:` field holding just the base32 secret.
     */
    val otp: String?
        get() =
            otpUri ?: fields.firstNotNullOfOrNull { field ->
                val key = field.key.lowercase()
                val value = field.value.trim()
                when {
                    key !in OTP_KEYS || value.isEmpty() || field.nested -> null
                    value.startsWith(OTP_SCHEME) -> value
                    key == "totp" && BASE32.matches(value.replace(" ", "")) ->
                        "otpauth://totp/totp?secret=" + value.replace(" ", "")
                    else -> null
                }
            }

    private fun field(keys: Set<String>): String? =
        fields.firstOrNull { it.key.lowercase() in keys && !it.nested }?.value

    /**
     * Writes the entry back in the shape it was read in. Parsing what this
     * produces gives back an equal entry, which is what keeps an edit on the
     * phone from slowly rewriting a file made on the desktop.
     */
    fun format(): String =
        buildString {
            append(password)
            if (yaml) {
                append('\n').append(YAML_SEPARATOR)
                fields.forEach { field ->
                    append('\n').append(field.key).append(':')
                    when {
                        field.nested -> append('\n').append(field.value)
                        field.isMultiline -> {
                            append(" |")
                            field.value.split('\n').forEach { line ->
                                append('\n')
                                if (line.isNotEmpty()) append(YAML_INDENT).append(line)
                            }
                        }
                        else -> append(' ').append(yamlScalar(field.value))
                    }
                }
            } else {
                fields.forEach { append('\n').append(it.key).append(": ").append(it.value) }
            }
            otpUri?.let { append('\n').append(it) }
            if (notes.isNotEmpty()) append('\n').append(notes)
            append('\n')
        }

    companion object {
        private val USERNAME_KEYS = setOf("login", "username", "user", "email")
        private val URL_KEYS = setOf("url", "website", "site", "uri")
        private val SECRET_KEYS = setOf("pin", "cvv", "cvc", "secret", "recovery", "passphrase", "password")
        private val OTP_KEYS = setOf("otpauth", "totp")
        private val FIELD = Regex("""^([A-Za-z0-9][A-Za-z0-9 _.-]{0,40}):(?:\s+(.*)|)$""")
        private val BASE32 = Regex("""^[A-Za-z2-7]{16,}=*$""")
        private val NUMBER = Regex("""^[-+]?(\d[\d_]*)?\.?\d*([eE][-+]?\d+)?$|^0[xob][0-9a-fA-F_]+$""")
        private val BLOCK = Regex("""^([|>])([+-]?)$""")
        private const val OTP_SCHEME = "otpauth://"
        private const val REFERENCE_SCHEME = "gopass://"
        private const val YAML_SEPARATOR = "---"
        private const val YAML_INDENT = "  "

        internal fun referenceIn(value: String): String? =
            value
                .trim()
                .takeIf { it.startsWith(REFERENCE_SCHEME) && ' ' !in it }
                ?.removePrefix(REFERENCE_SCHEME)
                ?.trim('/')
                ?.takeIf { it.isNotEmpty() }

        /**
         * Whether [key] reads back as a field name: parsing `key: value` with
         * any other key turns the line into a note.
         */
        fun isValidKey(key: String): Boolean = key == key.trim() && FIELD.matches("$key:")

        fun parse(text: String): PassEntry {
            val lines = text.replace("\r\n", "\n").split('\n')
            val body = lines.drop(1)
            val separator = body.indexOfFirst { it.isNotBlank() }
            return if (separator >= 0 && body[separator].trim() == YAML_SEPARATOR) {
                parseYaml(lines.first(), body.drop(separator + 1))
            } else {
                parseKeyValue(lines.first(), body)
            }
        }

        private fun parseKeyValue(
            password: String,
            body: List<String>,
        ): PassEntry {
            val fields = mutableListOf<Field>()
            val notes = mutableListOf<String>()
            var otp: String? = null
            body.forEach { line ->
                val trimmed = line.trim()
                val match = FIELD.matchEntire(trimmed)
                when {
                    trimmed.startsWith(OTP_SCHEME) && otp == null -> otp = trimmed
                    // A URL-looking value on its own ("https://x") would match
                    // as a field called "https"; it is a note.
                    match != null && !match.groupValues[2].startsWith("//") ->
                        fields += Field(match.groupValues[1].trim(), match.groupValues[2].trim())
                    else -> notes += line
                }
            }
            return PassEntry(password, fields, otp, notes.joinToString("\n").trim('\n'))
        }

        private fun parseYaml(
            password: String,
            body: List<String>,
        ): PassEntry {
            val fields = mutableListOf<Field>()
            val notes = mutableListOf<String>()
            var otp: String? = null
            var i = 0
            while (i < body.size) {
                val line = body[i]
                val match = if (line.startsWith(' ')) null else FIELD.matchEntire(line.trimEnd())
                if (match == null) {
                    if (line.trim().startsWith(OTP_SCHEME) && otp == null) otp = line.trim() else notes += line
                    i++
                    continue
                }
                val key = match.groupValues[1].trim()
                val inline = match.groupValues[2].trim()
                // The indented lines that belong to this key, if any.
                var end = i + 1
                while (end < body.size && (body[end].isBlank() || body[end].startsWith(' '))) end++
                // Trailing blank lines belong to whatever follows, not to this value.
                while (end > i + 1 && body[end - 1].isBlank()) end--
                val block = body.subList(i + 1, end)
                val style = BLOCK.matchEntire(inline)
                fields +=
                    when {
                        style != null -> Field(key, blockScalar(block, folded = style.groupValues[1] == ">"))
                        inline.isEmpty() && block.isNotEmpty() -> Field(key, block.joinToString("\n"), nested = true)
                        else -> Field(key, unquote(inline))
                    }
                i = if (style != null || inline.isEmpty()) end else i + 1
            }
            return PassEntry(password, fields, otp, notes.joinToString("\n").trim('\n'), yaml = true)
        }

        /** The text of a `|` or `>` block, its common indentation removed. */
        private fun blockScalar(
            lines: List<String>,
            folded: Boolean,
        ): String {
            val indent = lines.filter { it.isNotBlank() }.minOfOrNull { it.length - it.trimStart().length } ?: 0
            val content = lines.map { if (it.isBlank()) "" else it.substring(indent) }
            if (!folded) return content.joinToString("\n")
            // Folding: single line breaks become spaces, blank lines stay breaks.
            return content
                .joinToString("\n")
                .split("\n\n")
                .joinToString("\n") { paragraph -> paragraph.replace('\n', ' ') }
        }

        private fun unquote(value: String): String =
            when {
                value.length >= 2 && value.startsWith('"') && value.endsWith('"') ->
                    value.substring(1, value.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
                value.length >= 2 && value.startsWith('\'') && value.endsWith('\'') ->
                    value.substring(1, value.length - 1).replace("''", "'")
                else -> value
            }

        /** Quotes a YAML scalar only when leaving it bare would change what it means. */
        private fun yamlScalar(value: String): String {
            val plain =
                value.isNotEmpty() &&
                    value == value.trim() &&
                    value.first() !in "\"'&*!|>%@`#{}[],?-:" &&
                    ": " !in value &&
                    " #" !in value &&
                    value.lowercase() !in setOf("true", "false", "yes", "no", "null", "~", "on", "off") &&
                    // A PIN of 0042 is not the number 42.
                    !NUMBER.matches(value)
            return if (plain) value else "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
    }
}
