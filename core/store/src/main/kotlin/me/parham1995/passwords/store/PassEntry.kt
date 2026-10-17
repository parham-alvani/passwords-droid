package me.parham1995.passwords.store

/**
 * The decrypted contents of one `.gpg` file, read the way `pass` and gopass
 * write it.
 *
 * The first line is the password and nothing else. What follows comes in one
 * of gopass's two shapes:
 *
 * - **key-value** (gopass's default "AKV", and what `pass` users write by
 *   hand): `key: value` lines, an `otpauth://` URI on a line of its own, and
 *   free text, in any order. A field is any line with `": "` in it whose key
 *   (the text before) is not empty, does not start with whitespace and has no
 *   colon -- so `path: //nas/share` is a field and a bare `https://x` is not.
 * - **YAML**: free text (gopass's "body", where an `otpauth://` line lives),
 *   then a `---` line, then a top-level mapping whose values may be quoted,
 *   span lines (`|`, `>` blocks with their chomping), or be a nested list or
 *   map, which is kept verbatim.
 *
 * Parsing never throws. A file nobody can make sense of is still a password
 * on its first line and notes after it, which is how `pass show` treats it.
 *
 * Formatting what was parsed gives back the same text, byte for byte; an edit
 * rewrites only the lines it touched. That is what [layout] is for.
 */
data class PassEntry(
    val password: String,
    val fields: List<Field>,
    val otpUri: String?,
    val notes: String,
    /** Read from, and written back as, a YAML body. */
    val yaml: Boolean = false,
    /**
     * Where each line of the parsed text was, so [format] can keep the order
     * and spelling of everything an edit did not touch. Carried along by
     * [copy]; ignored by [equals], which compares content only.
     */
    val layout: Layout = Layout.NONE,
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

    /**
     * The shape of the text an entry was parsed from. Opaque outside this
     * module, and always equal to any other layout: two entries with the same
     * content are the same entry, however their files were laid out.
     */
    class Layout internal constructor(
        internal val parsed: Boolean,
        internal val yaml: Boolean,
        internal val lines: List<Line>,
        internal val fields: List<Field>,
        internal val otpUri: String?,
        internal val notes: String,
        internal val endsWithNewline: Boolean,
    ) {
        override fun equals(other: Any?): Boolean = other is Layout

        override fun hashCode(): Int = 0

        override fun toString(): String = "Layout"

        companion object {
            val NONE = Layout(false, false, emptyList(), emptyList(), null, "", true)
        }
    }

    /** One or more consecutive lines of the body, by what they are. */
    internal sealed interface Line {
        val raw: List<String>

        /** The [index]th parsed field, with its block lines if it has any. */
        class FieldLine(
            val index: Int,
            override val raw: List<String>,
        ) : Line

        class OtpLine(
            override val raw: List<String>,
        ) : Line

        /** Free text, which makes up [notes]. */
        class NoteLine(
            override val raw: List<String>,
        ) : Line

        /** YAML's `---`, blank lines and comments inside the mapping: kept, never shown. */
        class Verbatim(
            override val raw: List<String>,
            val separator: Boolean = false,
        ) : Line
    }

    /** The username, from the first field that conventionally holds one. */
    val username: String? get() = field(USERNAME_KEYS)

    /** The site, from the first field that conventionally holds one. */
    val url: String? get() = field(URL_KEYS)

    /**
     * How a passwordless account signs in -- `google`, `apple`, `sso`,
     * `magic-link` -- from the `login-method` field gopass's "SSO /
     * passwordless login" template writes.
     */
    val loginMethod: String? get() = field(setOf(LOGIN_METHOD))?.takeIf { it.isNotBlank() }

    /** An account with no password of its own, signed into through [loginMethod]. */
    val isPasswordless: Boolean get() = password.isBlank() && loginMethod != null

    /**
     * The entry whose password this one borrows: gopass's `gopass://path`
     * reference on the password line, followed when `core.follow-references`
     * is on.
     */
    val reference: String? get() = referenceIn(password)

    /**
     * The one-time-code configuration, looked up in gopass's order: an
     * `otpauth` field, then an `otpauth://` line in the body, then a `totp`
     * field (a URI or just the base32 secret), then an `hotp` field holding a
     * URI.
     *
     * gopass matches field keys case-sensitively; this matches them in any
     * case, so a hand-written `TOTP:` still shows a code. A bare `hotp`
     * secret is not turned into a URI: [Totp] only does TOTP.
     */
    val otp: String?
        get() {
            otpField("otpauth")?.let { value ->
                if (value.startsWith("//")) return "otpauth:$value"
                if (value.startsWith(OTP_SCHEME)) return value
            }
            otpUri?.let { return it }
            otpField("totp")?.let { value ->
                val secret = value.replace(" ", "")
                if (value.startsWith(OTP_SCHEME)) return value
                if (BASE32.matches(secret)) return "otpauth://totp/totp?secret=$secret"
            }
            return otpField("hotp")?.takeIf { it.startsWith(OTP_SCHEME) }
        }

    private fun otpField(key: String): String? =
        fields
            .firstOrNull { it.key.lowercase() == key && !it.nested && it.value.isNotBlank() }
            ?.value
            ?.trim()

    private fun field(keys: Set<String>): String? =
        fields.firstOrNull { it.key.lowercase() in keys && !it.nested }?.value

    /**
     * Writes the entry back in the shape it was read in. Parsing what this
     * produces gives back an equal entry, and an entry that came from [parse]
     * and was not edited comes back as exactly the text it was parsed from.
     */
    fun format(): String =
        if (layout.parsed && layout.yaml == yaml) {
            formatOver(layout)
        } else {
            formatFresh()
        }

    private fun formatFresh(): String =
        buildString {
            append(password)
            if (yaml) {
                // gopass's layout: free text before the separator, the mapping after it.
                otpUri?.let { append('\n').append(it) }
                if (notes.isNotEmpty()) append('\n').append(notes)
                append('\n').append(YAML_SEPARATOR)
                fields.forEach { field -> yamlLines(field).forEach { append('\n').append(it) } }
            } else {
                fields.forEach { append('\n').append(keyValueLine(it)) }
                otpUri?.let { append('\n').append(it) }
                if (notes.isNotEmpty()) append('\n').append(notes)
            }
            append('\n')
        }

    /**
     * Replays [original]'s lines: untouched ones verbatim, edited fields in
     * place, removed ones dropped. New fields go after the last field, a new
     * OTP line after the fields (before the separator in YAML), and changed
     * notes replace the old free text at the end of the body.
     */
    private fun formatOver(original: Layout): String {
        val notesChanged = notes != original.notes
        val otpChanged = otpUri != original.otpUri
        val matched = matchFields(original.fields)
        val out = mutableListOf<String>()
        var afterFields: Int? = null
        var firstField: Int? = null
        var separator: Int? = null
        var hadOtp = false
        for (line in original.lines) {
            when (line) {
                is Line.FieldLine -> {
                    if (firstField == null) firstField = out.size
                    val current = matched[line.index]?.first ?: continue
                    out += if (current == original.fields[line.index]) line.raw else render(current)
                    afterFields = out.size
                }
                is Line.OtpLine -> {
                    hadOtp = true
                    if (!otpChanged) out += line.raw else otpUri?.let { out += it }
                }
                is Line.NoteLine -> if (!notesChanged) out += line.raw
                is Line.Verbatim -> {
                    if (line.separator) separator = out.size
                    out += line.raw
                }
            }
        }
        val used = matched.values.mapTo(mutableSetOf()) { it.second }
        val added = fields.filterIndexed { i, _ -> i !in used }
        val newOtp = if (otpChanged && !hadOtp) listOfNotNull(otpUri) else emptyList()
        val newNotes = if (notesChanged && notes.isNotEmpty()) listOf(notes) else emptyList()
        val fieldAt = afterFields ?: firstField ?: if (yaml) out.size else 0
        val fieldLines = added.flatMap { render(it) }
        if (yaml) {
            out.addAll(fieldAt, fieldLines)
            out.addAll(separator ?: 0, newOtp + newNotes)
        } else {
            out.addAll(fieldAt, fieldLines + newOtp)
            out.addAll(newNotes)
        }
        return buildString {
            append(password)
            out.forEach { append('\n').append(it) }
            if (original.endsWithNewline) append('\n')
        }
    }

    /**
     * Pairs each original field index with the current field that stands in
     * for it: the nth field with a key stands in for the nth original field
     * with that key. Values are (field, index in [fields]).
     */
    private fun matchFields(original: List<Field>): Map<Int, Pair<Field, Int>> {
        val byKey = original.indices.groupBy { original[it].key }.mapValues { it.value.toMutableList() }
        val result = mutableMapOf<Int, Pair<Field, Int>>()
        fields.forEachIndexed { i, field ->
            val slot = byKey[field.key]?.removeFirstOrNull() ?: return@forEachIndexed
            result[slot] = field to i
        }
        return result
    }

    private fun render(field: Field): List<String> = if (yaml) yamlLines(field) else listOf(keyValueLine(field))

    companion object {
        private val USERNAME_KEYS = setOf("login", "username", "user", "email")
        private val URL_KEYS = setOf("url", "website", "site", "uri")
        private val SECRET_KEYS = setOf("pin", "cvv", "cvc", "secret", "recovery", "passphrase", "password")
        private val BASE32 = Regex("""^[A-Za-z2-7]{16,}=*$""")
        private val SEPARATOR_LINE = Regex("""^---(?:[ \t]+#.*)?[ \t]*$""")
        private const val LOGIN_METHOD = "login-method"
        private const val OTP_SCHEME = "otpauth://"
        private const val REFERENCE_SCHEME = "gopass://"
        private const val YAML_SEPARATOR = "---"
        private const val KV_SEPARATOR = ": "

        /** The first line of gopass's short-lived MIME format (secparse/mime.go). */
        private const val MIME_IDENT = "GOPASS-SECRET-1.0"

        internal fun referenceIn(value: String): String? =
            value
                .trim()
                .takeIf { it.startsWith(REFERENCE_SCHEME) && ' ' !in it }
                ?.removePrefix(REFERENCE_SCHEME)
                ?.trim('/')
                ?.takeIf { it.isNotEmpty() }

        /**
         * Whether [key] can be written as a `key: value` line and read back as
         * the same key: gopass's rule (the text before `": "`), less what would
         * be trimmed or split differently -- not empty, no surrounding
         * whitespace, no colon, one line. Any script, parentheses and question
         * marks are fine.
         */
        fun isValidKey(key: String): Boolean =
            key.isNotEmpty() &&
                key == key.trim() &&
                ':' !in key &&
                '\n' !in key &&
                '\r' !in key

        private fun keyValueLine(field: Field) = field.key + KV_SEPARATOR + field.value

        private fun yamlLines(field: Field): List<String> =
            if (field.nested) {
                listOf(YamlText.keyText(field.key) + ":") + field.value.split('\n')
            } else {
                YamlText.field(field.key, field.value)
            }

        fun parse(text: String): PassEntry {
            val normalized = text.replace("\r\n", "\n")
            val endsWithNewline = normalized.endsWith('\n')
            val lines = (if (endsWithNewline) normalized.dropLast(1) else normalized).split('\n')
            if (lines.first().trim() == MIME_IDENT) return parseMime(lines.drop(1))
            return parseLines(lines, endsWithNewline)
        }

        private fun parseLines(
            lines: List<String>,
            endsWithNewline: Boolean,
        ): PassEntry {
            val password = lines.first()
            val body = lines.drop(1)
            val separator = body.indexOfFirst { SEPARATOR_LINE.matches(it) }
            return if (separator >= 0 && looksLikeMapping(body.drop(separator + 1))) {
                parseYaml(password, body, separator, endsWithNewline)
            } else {
                parseKeyValue(password, body, endsWithNewline)
            }
        }

        /**
         * Whether what follows a `---` line is a YAML mapping. gopass falls back
         * to key-value when the YAML does not decode; this is laxer -- free text
         * after the mapping is kept as notes -- but text that does not even start
         * as a mapping is not YAML.
         */
        private fun looksLikeMapping(lines: List<String>): Boolean {
            val first = lines.firstOrNull { it.isNotBlank() && !it.startsWith('#') } ?: return true
            return YamlText.keyOf(first) != null
        }

        /** The key and value of a gopass key-value line, or null when it is not one. */
        private fun keyValue(line: String): Field? {
            val at = line.indexOf(KV_SEPARATOR)
            if (at <= 0) return null
            val key = line.substring(0, at)
            if (key.first().isWhitespace() || ':' in key) return null
            // gopass keeps the value untrimmed (its issue #2873).
            return Field(key.trimEnd(), line.substring(at + KV_SEPARATOR.length))
        }

        private fun parseKeyValue(
            password: String,
            body: List<String>,
            endsWithNewline: Boolean,
        ): PassEntry {
            val fields = mutableListOf<Field>()
            val layout = mutableListOf<Line>()
            var otp: String? = null
            body.forEach { line ->
                val trimmed = line.trim()
                val field = keyValue(line)
                when {
                    trimmed.startsWith(OTP_SCHEME) && otp == null -> {
                        otp = trimmed
                        layout += Line.OtpLine(listOf(line))
                    }
                    field != null -> {
                        layout += Line.FieldLine(fields.size, listOf(line))
                        fields += field
                    }
                    else -> layout += Line.NoteLine(listOf(line))
                }
            }
            return build(password, fields, otp, layout, yaml = false, endsWithNewline)
        }

        private fun parseYaml(
            password: String,
            body: List<String>,
            separator: Int,
            endsWithNewline: Boolean,
        ): PassEntry {
            val fields = mutableListOf<Field>()
            val layout = mutableListOf<Line>()
            var otp: String? = null

            fun otpOrNote(line: String) {
                if (line.trim().startsWith(OTP_SCHEME) && otp == null) {
                    otp = line.trim()
                    layout += Line.OtpLine(listOf(line))
                } else {
                    layout += Line.NoteLine(listOf(line))
                }
            }

            // gopass's body: free text and the OTP line, before the separator.
            body.subList(0, separator).forEach(::otpOrNote)
            layout += Line.Verbatim(listOf(body[separator]), separator = true)

            val mapping = body.subList(separator + 1, body.size)
            var i = 0
            while (i < mapping.size) {
                val line = mapping[i]
                val entry = YamlText.keyOf(line)
                if (line.isBlank() || line.startsWith('#') || line.trimEnd() == "...") {
                    layout += Line.Verbatim(listOf(line))
                    i++
                    continue
                }
                if (entry == null) {
                    // Text after the mapping that is not part of it stays readable.
                    otpOrNote(line)
                    i++
                    continue
                }
                val (key, inline) = entry
                val header = YamlText.blockHeader(inline)
                val empty = YamlText.stripComment(inline).isEmpty()
                var end = i + 1
                while (end < mapping.size && belongsTo(mapping[end], compactSequence = empty)) end++
                // Trailing blank lines belong to whatever follows, unless a `|+` keeps them.
                if (header?.chomp != '+') while (end > i + 1 && mapping[end - 1].isBlank()) end--
                val block = mapping.subList(i + 1, end)
                val field =
                    when {
                        header != null -> Field(key, YamlText.blockScalar(block, header))
                        empty && block.isNotEmpty() -> Field(key, block.joinToString("\n"), nested = true)
                        empty -> Field(key, "")
                        else -> Field(key, YamlText.scalar(listOf(inline) + block))
                    }
                layout += Line.FieldLine(fields.size, mapping.subList(i, end).toList())
                fields += field
                i = end
            }
            return build(password, fields, otp, layout, yaml = true, endsWithNewline)
        }

        /**
         * Whether [line] continues the value of the key above it: indented, blank,
         * or -- under a key with nothing after its colon -- a `- item` of a list
         * written at the key's own column, as gopass's YAML encoder does not but
         * people do.
         */
        private fun belongsTo(
            line: String,
            compactSequence: Boolean,
        ): Boolean =
            line.isBlank() ||
                line.startsWith(' ') ||
                (compactSequence && (line == "-" || line.startsWith("- ")))

        private fun build(
            password: String,
            fields: List<Field>,
            otp: String?,
            lines: List<Line>,
            yaml: Boolean,
            endsWithNewline: Boolean,
        ): PassEntry {
            val notes =
                lines
                    .filterIsInstance<Line.NoteLine>()
                    .flatMap { it.raw }
                    .joinToString("\n")
                    .trim('\n')
            val layout = Layout(true, yaml, lines, fields.toList(), otp, notes, endsWithNewline)
            return PassEntry(password, fields.toList(), otp, notes, yaml, layout)
        }

        /**
         * gopass's legacy MIME secret: `GOPASS-SECRET-1.0`, then `Key: value`
         * headers (one of them `Password`) up to a blank line, then the body.
         * gopass converts it to key-value on read -- the password, the other
         * headers with lower-cased keys in sorted order, then the body -- and so
         * does this; saving writes the converted form, as gopass does.
         */
        private fun parseMime(body: List<String>): PassEntry {
            val headers = mutableListOf<Pair<String, String>>()
            var i = 0
            while (i < body.size && body[i].isNotBlank()) {
                val line = body[i]
                if ((line.startsWith(' ') || line.startsWith('\t')) && headers.isNotEmpty()) {
                    val (key, value) = headers.removeAt(headers.lastIndex)
                    headers += key to (value + " " + line.trim()).trim()
                } else if (':' in line) {
                    headers += line.substringBefore(':').trim() to line.substringAfter(':').trim()
                }
                i++
            }
            val rest = body.drop(i + 1)
            val password = headers.firstOrNull { it.first.equals("password", ignoreCase = true) }?.second.orEmpty()
            val converted =
                buildList {
                    add(password)
                    headers
                        .filterNot { it.first.equals("password", ignoreCase = true) }
                        .map { it.first.lowercase() to it.second }
                        .sortedBy { it.first }
                        .forEach { (key, value) -> add(key + KV_SEPARATOR + value) }
                    addAll(rest)
                }
            return parseLines(converted, endsWithNewline = true)
        }
    }
}
