package me.parham1995.passwords.store

/**
 * The small part of YAML that gopass secrets and `gopass create` templates
 * use, read and written by hand: there is no YAML library in this module, and
 * none is needed for a top-level mapping of scalars.
 *
 * Reading follows YAML 1.2 for scalars (plain, single- and double-quoted,
 * literal and folded blocks with their chomping and indentation indicators).
 * Writing quotes only when a bare value would read back differently.
 */
internal object YamlText {
    const val INDENT = "  "

    private val BLOCK_HEADER = Regex("""^([|>])(?:([1-9])([+-]?)|([+-])([1-9]?)|)$""")
    private val NUMBER = Regex("""^[-+]?(\d[\d_]*)?\.?\d*([eE][-+]?\d+)?$|^0[xob][0-9a-fA-F_]+$""")
    private val RESERVED =
        setOf("true", "false", "yes", "no", "y", "n", "on", "off", "null", "~", ".inf", "-.inf", "+.inf", ".nan")
    private const val INDICATORS = "\"'&*!|>%@`#{}[],?-:"
    private val NBSP = Char(0xa0)
    private val LINE_SEPARATOR = Char(0x2028)
    private val PARAGRAPH_SEPARATOR = Char(0x2029)
    private val BYTE_ORDER_MARK = Char(0xfeff)

    /** A block scalar header: `|`, `>-`, `|+`, `|2`, `>1-` and so on. */
    data class BlockHeader(
        val folded: Boolean,
        /** '-' strip, '+' keep, null clip. */
        val chomp: Char?,
        /** The explicit indentation indicator, or null to detect it. */
        val indent: Int?,
    )

    fun blockHeader(inline: String): BlockHeader? {
        val match = BLOCK_HEADER.matchEntire(stripComment(inline)) ?: return null
        val (style, digitFirst, chompAfter, chompFirst, digitAfter) = match.destructured
        val digit = digitFirst.ifEmpty { digitAfter }
        val chomp = chompAfter.ifEmpty { chompFirst }
        return BlockHeader(style == ">", chomp.firstOrNull(), digit.toIntOrNull())
    }

    /**
     * The value of a block scalar from its indented [lines] (blank lines
     * included, trailing ones too when [header] keeps them). [parentIndent]
     * is the indentation of the key that owns it.
     */
    fun blockScalar(
        lines: List<String>,
        header: BlockHeader,
        parentIndent: Int = 0,
    ): String {
        val indent =
            header.indent?.let { parentIndent + it }
                ?: lines.firstOrNull { it.isNotBlank() }?.let { it.length - it.trimStart(' ').length }
                ?: return ""
        val content = lines.map { if (it.length > indent && it.isNotBlank()) it.substring(indent) else "" }
        val last = content.indexOfLast { it.isNotEmpty() }
        if (last < 0) return if (header.chomp == '+') "\n".repeat(content.size) else ""
        val body = content.subList(0, last + 1)
        val trailing = content.size - last - 1
        val text = if (header.folded) fold(body) else body.joinToString("\n")
        return when (header.chomp) {
            '-' -> text
            '+' -> text + "\n" + "\n".repeat(trailing)
            else -> text + "\n"
        }
    }

    /** YAML's folding of a `>` block: single breaks become spaces, except around more-indented lines. */
    private fun fold(lines: List<String>): String {
        val out = StringBuilder()
        var empties = 0
        var started = false
        var previousMore = false
        for (line in lines) {
            if (line.isEmpty()) {
                empties++
                continue
            }
            val more = line.first() == ' ' || line.first() == '\t'
            when {
                !started -> out.append("\n".repeat(empties))
                more || previousMore -> out.append("\n".repeat(empties + 1))
                empties == 0 -> out.append(' ')
                else -> out.append("\n".repeat(empties))
            }
            out.append(line)
            started = true
            previousMore = more
            empties = 0
        }
        return out.toString()
    }

    /** Removes a trailing ` # comment` from plain text. */
    fun stripComment(text: String): String {
        if (text.startsWith('#')) return ""
        var i = 0
        while (i < text.length) {
            if (text[i] == '#' &&
                i > 0 &&
                (text[i - 1] == ' ' || text[i - 1] == '\t')
            ) {
                return text.substring(0, i).trimEnd()
            }
            i++
        }
        return text.trim()
    }

    /**
     * A flow scalar spread over [lines]: the text after `key:` and any
     * continuation lines. Quoted scalars are unescaped, plain ones lose their
     * comment, and line breaks fold the way YAML folds them.
     */
    fun scalar(lines: List<String>): String {
        val first = lines.first().trim()
        return when {
            first.startsWith('"') -> quoted(lines, '"') ?: plain(lines)
            first.startsWith('\'') -> quoted(lines, '\'') ?: plain(lines)
            else -> plain(lines)
        }
    }

    private fun plain(lines: List<String>): String {
        val parts = lines.map { stripComment(it.trim()) }
        val out = StringBuilder(parts.first().removePrefix("!!str ").trim())
        var empties = 0
        for (part in parts.drop(1)) {
            if (part.isEmpty()) {
                empties++
                continue
            }
            out.append(if (empties == 0) " " else "\n".repeat(empties)).append(part)
            empties = 0
        }
        return out.toString()
    }

    /** Null when the quote is never closed, so the caller can fall back to reading it plain. */
    private fun quoted(
        lines: List<String>,
        quote: Char,
    ): String? {
        val text = lines.joinToString("\n").trimStart()
        val out = StringBuilder()
        var i = 1
        while (i < text.length) {
            val c = text[i]
            when {
                c == quote && quote == '\'' && text.getOrNull(i + 1) == '\'' -> {
                    out.append('\'')
                    i += 2
                }
                c == quote -> return out.toString()
                c == '\\' && quote == '"' && text.getOrNull(i + 1) == '\n' -> {
                    // An escaped line break joins the lines with nothing between.
                    i += 2
                    while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
                }
                c == '\\' && quote == '"' -> i = escape(text, i, out)
                c == '\n' -> {
                    while (out.isNotEmpty() && (out.last() == ' ' || out.last() == '\t')) out.setLength(out.length - 1)
                    var empties = 0
                    i++
                    while (true) {
                        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
                        if (i < text.length && text[i] == '\n') {
                            empties++
                            i++
                        } else {
                            break
                        }
                    }
                    out.append(if (empties == 0) " " else "\n".repeat(empties))
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return null
    }

    /** Decodes the escape at [at] (a backslash) into [out]; returns the index after it. */
    private fun escape(
        text: String,
        at: Int,
        out: StringBuilder,
    ): Int {
        val code = text.getOrNull(at + 1) ?: return at + 1
        val simple =
            when (code) {
                '0' -> "\u0000"
                'a' -> "\u0007"
                'b' -> "\b"
                't', '\t' -> "\t"
                'n' -> "\n"
                'v' -> "\u000b"
                'f' -> "\u000c"
                'r' -> "\r"
                'e' -> "\u001b"
                ' ' -> " "
                '"' -> "\""
                '/' -> "/"
                '\\' -> "\\"
                'N' -> "\u0085"
                '_' -> NBSP.toString()
                'L' -> LINE_SEPARATOR.toString()
                'P' -> PARAGRAPH_SEPARATOR.toString()
                else -> null
            }
        if (simple != null) {
            out.append(simple)
            return at + 2
        }
        val digits =
            when (code) {
                'x' -> 2
                'u' -> 4
                'U' -> 8
                else -> 0
            }
        val hex = text.substring(at + 2, minOf(text.length, at + 2 + digits))
        val value = if (digits > 0 && hex.length == digits) hex.toIntOrNull(16) else null
        if (value == null || !Character.isValidCodePoint(value)) {
            // Not an escape YAML knows; keep it as written rather than guess.
            out.append('\\').append(code)
            return at + 2
        }
        out.appendCodePoint(value)
        return at + 2 + digits
    }

    /**
     * The key of a top-level mapping line and the text after its colon, or
     * null when [line] is not one. Quoted keys are unquoted.
     */
    fun keyOf(line: String): Pair<String, String>? {
        if (line.isEmpty() || line.first().isWhitespace() || line.first() == '#') return null
        if (line == "-" || line.startsWith("- ") || line.startsWith("? ")) return null
        val first = line.first()
        if (first == '"' || first == '\'') {
            val close = closingQuote(line, first) ?: return null
            if (close + 1 >= line.length || line[close + 1] != ':') return null
            val after = line.substring(close + 2)
            if (after.isNotEmpty() && !after.first().isWhitespace()) return null
            val key = quoted(listOf(line.substring(0, close + 1)), first) ?: return null
            return key to after.trim()
        }
        if (first in "[]{}&*!|>%@`,") return null
        var i = line.indexOf(':')
        while (i >= 0) {
            if (i + 1 == line.length || line[i + 1] == ' ' || line[i + 1] == '\t') {
                val key = line.substring(0, i).trimEnd()
                if (key.isEmpty() || key.contains(" #")) return null
                return key to line.substring(i + 1).trim()
            }
            i = line.indexOf(':', i + 1)
        }
        return null
    }

    private fun closingQuote(
        line: String,
        quote: Char,
    ): Int? {
        var i = 1
        while (i < line.length) {
            when {
                quote == '"' && line[i] == '\\' -> i += 2
                quote == '\'' && line[i] == '\'' && line.getOrNull(i + 1) == '\'' -> i += 2
                line[i] == quote -> return i
                else -> i++
            }
        }
        return null
    }

    private fun isControl(c: Char): Boolean =
        (c < ' ' && c != '\n') ||
            c == '\u007f' ||
            c == '\u0085' ||
            c == LINE_SEPARATOR ||
            c == PARAGRAPH_SEPARATOR ||
            c == BYTE_ORDER_MARK

    private fun isPlain(value: String): Boolean =
        value.isNotEmpty() &&
            value == value.trim() &&
            '\n' !in value &&
            value.none { isControl(it) } &&
            value.first() !in INDICATORS &&
            ": " !in value &&
            " #" !in value &&
            !value.endsWith(':') &&
            value.lowercase() !in RESERVED &&
            // A PIN of 0042 is not the number 42.
            !NUMBER.matches(value)

    /** A single-line scalar: bare when that reads back the same, double-quoted and escaped otherwise. */
    fun scalarText(value: String): String = if (isPlain(value)) value else doubleQuoted(value)

    /** A mapping key, quoted when YAML would not read it back as the same string. */
    fun keyText(key: String): String = scalarText(key)

    fun doubleQuoted(value: String): String =
        buildString {
            append('"')
            value.forEach { c ->
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\t' -> append("\\t")
                    '\r' -> append("\\r")
                    '\u0000' -> append("\\0")
                    '\u0007' -> append("\\a")
                    '\b' -> append("\\b")
                    '\u000b' -> append("\\v")
                    '\u000c' -> append("\\f")
                    '\u001b' -> append("\\e")
                    '\u0085' -> append("\\N")
                    LINE_SEPARATOR -> append("\\L")
                    PARAGRAPH_SEPARATOR -> append("\\P")
                    else ->
                        if (isControl(c)) append("\\u%04x".format(c.code)) else append(c)
                }
            }
            append('"')
        }

    /**
     * The lines of `key: value` for a value that may span lines: a literal
     * block whose chomping indicator (`|-`, `|`, `|+`) carries the value's
     * trailing newlines exactly, or a quoted scalar when a block cannot hold
     * the value (control characters, nothing but newlines).
     */
    fun field(
        key: String,
        value: String,
    ): List<String> {
        val head = keyText(key) + ":"
        val content = value.trimEnd('\n')
        val blockable =
            '\n' in value &&
                content.isNotEmpty() &&
                value.none { isControl(it) && it != '\t' } &&
                // A line of only spaces would read back as an empty one.
                content.split('\n').none { it.isNotEmpty() && it.isBlank() }
        if (!blockable) return listOf("$head ${scalarText(value)}")
        val trailing = value.length - content.length
        val chomp =
            when (trailing) {
                0 -> "-"
                1 -> ""
                else -> "+"
            }
        val lines = content.split('\n') + List(maxOf(0, trailing - 1)) { "" }
        // A first line that starts with a space would be taken for deeper indentation.
        val indicator = if (lines.first { it.isNotEmpty() }.first() == ' ') INDENT.length.toString() else ""
        return listOf("$head |$indicator$chomp") + lines.map { if (it.isEmpty()) "" else INDENT + it }
    }
}
