package me.parham1995.passwords.store

/**
 * A reader for block-style YAML documents of mappings, sequences and scalars
 * -- enough for `gopass create` templates, which are small and hand-written.
 * Maps come back as [LinkedHashMap]s, sequences as lists, scalars as strings
 * and empty values as null. Anchors, tags and flow mappings are not
 * supported; what it cannot read it rejects with [IllegalArgumentException],
 * so a broken file is skipped rather than half-read.
 */
internal object MiniYaml {
    fun parse(text: String): Any? = Parser(text.replace("\r\n", "\n").split('\n')).document()

    private class Parser(
        source: List<String>,
    ) {
        private val lines = source.toMutableList()
        private var i = 0

        private fun indentOf(line: String) = line.length - line.trimStart(' ').length

        private fun isMarker(line: String) =
            line.trimEnd() == "---" || line.startsWith("--- ") || line.trimEnd() == "..."

        private fun isItem(content: String) = content == "-" || content.startsWith("- ")

        /** Moves past blank lines and comments. */
        private fun skip() {
            while (i < lines.size && (lines[i].isBlank() || lines[i].trimStart().startsWith('#'))) i++
        }

        fun document(): Any? {
            skip()
            if (i < lines.size && isMarker(lines[i])) {
                val rest = lines[i].removePrefix("---").trim()
                if (rest.isNotEmpty() && !rest.startsWith('#')) throw IllegalArgumentException("content after ---")
                i++
                skip()
            }
            if (i >= lines.size) return null
            if (lines[i].startsWith('\t')) throw IllegalArgumentException("tabs are not indentation")
            val value = node()
            skip()
            // Only the first document counts, as with gopass's yaml.Unmarshal.
            if (i < lines.size && !isMarker(lines[i])) throw IllegalArgumentException("unexpected: ${lines[i].trim()}")
            return value
        }

        private fun node(): Any? {
            skip()
            val line = lines[i]
            val indent = indentOf(line)
            val content = line.substring(indent)
            return when {
                isItem(content) -> sequence(indent)
                YamlText.keyOf(content) != null -> mapping(indent)
                else -> {
                    i++
                    inlineValue(indent - 1, content)
                }
            }
        }

        private fun sequence(indent: Int): List<Any?> {
            val out = mutableListOf<Any?>()
            while (true) {
                skip()
                if (i >= lines.size) break
                val line = lines[i]
                val content = line.substring(minOf(indentOf(line), line.length))
                if (indentOf(line) != indent || !isItem(content)) {
                    if (indentOf(line) > indent) throw IllegalArgumentException("bad indentation: ${line.trim()}")
                    break
                }
                val rest = content.removePrefix("-").trimStart(' ')
                if (rest.isEmpty() || rest.startsWith('#')) {
                    i++
                    skip()
                    out += if (i < lines.size && indentOf(lines[i]) > indent) node() else null
                } else {
                    // "- key: value" opens a mapping at the column of "key".
                    val column = indent + content.length - rest.length
                    lines[i] = " ".repeat(column) + rest
                    out += node()
                }
            }
            return out
        }

        private fun mapping(indent: Int): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            while (true) {
                skip()
                if (i >= lines.size) break
                val line = lines[i]
                val lineIndent = indentOf(line)
                if (lineIndent < indent || isMarker(line)) break
                if (lineIndent > indent) throw IllegalArgumentException("bad indentation: ${line.trim()}")
                val content = line.substring(indent)
                if (isItem(content)) break
                val (key, rest) = YamlText.keyOf(content) ?: throw IllegalArgumentException("not a key: ${line.trim()}")
                if (key in out) throw IllegalArgumentException("mapping key \"$key\" already defined")
                i++
                out[key] = value(indent, rest)
            }
            return out
        }

        private fun value(
            indent: Int,
            rest: String,
        ): Any? {
            if (rest.isEmpty() || rest.startsWith('#')) {
                skip()
                if (i >= lines.size) return null
                val next = lines[i]
                val nextIndent = indentOf(next)
                val nested = nextIndent > indent || (nextIndent == indent && isItem(next.substring(nextIndent)))
                return if (nested && !isMarker(next)) node() else null
            }
            return inlineValue(indent, rest)
        }

        /** A value that starts on the current line: a block scalar, a flow list or a flow scalar. */
        private fun inlineValue(
            indent: Int,
            rest: String,
        ): Any {
            YamlText.blockHeader(rest)?.let { header ->
                var end = i
                while (end < lines.size && (lines[end].isBlank() || indentOf(lines[end]) > indent)) end++
                if (header.chomp != '+') while (end > i && lines[end - 1].isBlank()) end--
                val block = lines.subList(i, end).toList()
                i = end
                return YamlText.blockScalar(block, header, maxOf(indent, 0))
            }
            if (rest.startsWith('{')) throw IllegalArgumentException("flow mappings are not supported")
            if (rest.startsWith('[')) return flowList(rest)
            val parts = mutableListOf(rest)
            while (i < lines.size && (lines[i].isBlank() || indentOf(lines[i]) > indent) && !isMarker(lines[i])) {
                parts += lines[i]
                i++
            }
            while (parts.size > 1 && parts.last().isBlank()) parts.removeAt(parts.lastIndex)
            return YamlText.scalar(parts)
        }

        private fun flowList(text: String): List<String> {
            val items = mutableListOf<String>()
            val current = StringBuilder()
            var quote: Char? = null
            var closed = false
            var at = 1
            while (at < text.length && !closed) {
                val c = text[at]
                when {
                    quote != null -> {
                        current.append(c)
                        if (c == quote) quote = null
                    }
                    c == '"' || c == '\'' -> {
                        quote = c
                        current.append(c)
                    }
                    c == ',' || c == ']' -> {
                        if (current.isNotBlank()) items += YamlText.scalar(listOf(current.toString()))
                        current.clear()
                        closed = c == ']'
                    }
                    else -> current.append(c)
                }
                at++
            }
            val after = text.substring(at).trim()
            if (!closed ||
                (after.isNotEmpty() && !after.startsWith('#'))
            ) {
                throw IllegalArgumentException("bad list: $text")
            }
            return items
        }
    }
}
