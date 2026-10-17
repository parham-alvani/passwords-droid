package me.parham1995.passwords.store

import java.io.File

/**
 * `gopass create`: templates (`.yml` or `.yaml` files in the store's
 * `.gopass/create` folder) that ask
 * for a few attributes -- a site, a username, a password -- and turn the
 * answers into a new entry with a name built from them. A port of gopass's
 * internal/create (wizard.go, templates.go, helpers.go); asking the questions
 * is the UI's job, everything after the answers is here.
 */
object CreateWizard {
    /** Where gopass looks for templates, relative to the store root. */
    const val TEMPLATE_DIR = ".gopass/create"

    /** gopass's commit message for a created entry, after `Save secret <name>: `. */
    const val COMMIT_REASON = "Create new entry"

    /** The length gopass offers for a password drawn from an attribute's `charset`. */
    const val CHARSET_LENGTH = 4

    /** The commit message gopass writes when `gopass create` saves [name]. */
    fun commitMessage(name: String): String = "Save secret $name: $COMMIT_REASON"

    data class Attribute(
        val name: String,
        /** One of [STRING], [HOSTNAME], [PASSWORD], [CHOICE], [MULTILINE]; gopass skips anything else. */
        val type: String,
        val prompt: String = "",
        /** For a password: generate from these characters only. */
        val charset: String = "",
        /** Length limits in UTF-8 bytes, as gopass counts them; 0 is no limit. */
        val min: Int = 0,
        val max: Int = 0,
        /** For a password: always ask for one rather than offer to generate it. */
        val alwaysPrompt: Boolean = false,
        /** For a generated password: every class in [charset] must appear. */
        val strict: Boolean = false,
        /** For a choice: what can be chosen. */
        val options: List<String> = emptyList(),
        /** For a password: the account may have none (SSO, social login). */
        val optional: Boolean = false,
    ) {
        /** What to ask: the prompt, or the name in capitals when there is none, as gopass shows it. */
        val label: String get() = prompt.ifEmpty { name.uppercase() }

        companion object {
            const val STRING = "string"
            const val HOSTNAME = "hostname"
            const val PASSWORD = "password"
            const val CHOICE = "choice"
            const val MULTILINE = "multiline"
        }
    }

    data class Template(
        val name: String,
        val priority: Int = 0,
        val prefix: String = "",
        /** The attributes whose answers make up the entry's name, in attribute order. */
        val nameFrom: List<String> = emptyList(),
        val welcome: String = "",
        val attributes: List<Attribute> = emptyList(),
    )

    /** An answer to one attribute. */
    sealed interface Answer {
        /** Typed text, a chosen option, or a hostname as entered. */
        data class Text(
            val value: String,
        ) : Answer

        /** A password made by [generatePassword] or [PasswordGenerator]: not held to min and max, as in gopass. */
        data class Generated(
            val password: String,
        ) : Answer

        /** No password: an optional password attribute that was declined. */
        data object Declined : Answer
    }

    /** An answer gopass would reject, with gopass's message. */
    class InvalidAnswer(
        message: String,
    ) : IllegalArgumentException(message)

    data class Created(
        /** Where the entry goes: `prefix/part/part`. */
        val name: String,
        /** The entry exactly as gopass writes it (key-value). */
        val text: String,
        /** The password, empty for a passwordless account. */
        val password: String,
        val generated: Boolean,
    ) {
        val entry: PassEntry get() = PassEntry.parse(text)

        val commitMessage: String get() = commitMessage(name)
    }

    /**
     * gopass's three default templates: website login, numerical PIN, and SSO /
     * passwordless login. Offered when a store has no templates of its own --
     * unlike gopass, never written into the store.
     */
    val defaults: List<Template> by lazy { DEFAULT_SOURCES.map(::parseTemplate) }

    /**
     * The store's templates, by priority, or [defaults] when it has none that
     * read. A file that does not parse is skipped and reported to [onError],
     * as gopass logs it and carries on.
     */
    fun load(
        root: File,
        onError: (File, Exception) -> Unit = { _, _ -> },
    ): List<Template> {
        val dir = File(root, TEMPLATE_DIR)
        val parsed =
            dir
                .walkTopDown()
                .filter { it.isFile && (it.name.endsWith(".yml") || it.name.endsWith(".yaml")) }
                .sortedBy { it.path }
                .mapNotNull { file ->
                    try {
                        parseTemplate(file.readText())
                    } catch (e: IllegalArgumentException) {
                        onError(file, e)
                        null
                    }
                }.toList()
        return parsed.ifEmpty { defaults }.sortedBy { it.priority }
    }

    /** Reads one template file; throws [IllegalArgumentException] when it is not one. */
    fun parseTemplate(text: String): Template {
        val doc = MiniYaml.parse(text) ?: throw IllegalArgumentException("empty template")
        val map = doc as? Map<*, *> ?: throw IllegalArgumentException("a template is a mapping")
        return Template(
            name = string(map["name"]),
            priority = int(map["priority"]),
            prefix = string(map["prefix"]),
            nameFrom = strings(map["name_from"]),
            welcome = string(map["welcome"]),
            attributes = list(map["attributes"]).map(::attribute),
        )
    }

    private fun attribute(value: Any?): Attribute {
        val map = value as? Map<*, *> ?: throw IllegalArgumentException("an attribute is a mapping")
        return Attribute(
            name = string(map["name"]),
            type = string(map["type"]),
            prompt = string(map["prompt"]),
            charset = string(map["charset"]),
            min = int(map["min"]),
            max = int(map["max"]),
            alwaysPrompt = bool(map["always_prompt"]),
            strict = bool(map["strict"]),
            options = strings(map["options"]),
            optional = bool(map["optional"]),
        )
    }

    private fun string(value: Any?): String =
        when (value) {
            null -> ""
            is String -> value
            else -> throw IllegalArgumentException("expected text, got $value")
        }

    private fun int(value: Any?): Int =
        when (value) {
            null -> 0
            is String -> value.replace("_", "").toIntOrNull() ?: throw IllegalArgumentException("not a number: $value")
            else -> throw IllegalArgumentException("expected a number, got $value")
        }

    private fun bool(value: Any?): Boolean =
        when ((value as? String)?.lowercase() ?: value) {
            null -> false
            "true", "yes", "on" -> true
            "false", "no", "off" -> false
            else -> throw IllegalArgumentException("not a boolean: $value")
        }

    private fun list(value: Any?): List<*> =
        when (value) {
            null -> emptyList<Any>()
            is List<*> -> value
            else -> throw IllegalArgumentException("expected a list, got $value")
        }

    private fun strings(value: Any?): List<String> = list(value).map(::string)

    /**
     * A password for an attribute with a `charset`, as gopass makes one:
     * [length] characters from it, every class it holds present when the
     * attribute is `strict`. Without a charset gopass asks about length,
     * symbols and passphrases; use [PasswordGenerator.generate] for that.
     */
    fun generatePassword(
        attribute: Attribute,
        length: Int = CHARSET_LENGTH,
    ): String {
        require(attribute.charset.isNotEmpty()) { "${attribute.name} has no charset" }
        return PasswordGenerator.fromCharset(length, attribute.charset, attribute.strict)
    }

    /**
     * Builds the entry gopass would save for [template] given [answers], keyed
     * by attribute name. Attributes are written as `key: value` lines in order,
     * a password goes on the first line (left empty when declined), multiline
     * text is appended as it is. The name is the prefix and the [Template.nameFrom]
     * answers, each made filename-safe; for a hostname that is the bare host.
     *
     * gopass also adds a `password-change-url` from its built-in password
     * rules for known sites; this port has no such table and does not.
     */
    fun create(
        template: Template,
        answers: Map<String, Answer>,
    ): Created {
        val secret = KeyValueWriter()
        val forName = template.nameFrom.toSet()
        val nameParts = mutableListOf<String>()
        var password = ""
        var generated = false
        for (attribute in template.attributes) {
            val key = attribute.name
            val answer = answers[key]
            when (attribute.type) {
                Attribute.STRING -> {
                    val value = text(answer)
                    checkLength(attribute, value)
                    if (key in forName) nameParts += value
                    secret.set(key, value)
                }
                Attribute.CHOICE -> {
                    if (attribute.options.isEmpty()) throw InvalidAnswer("choice attribute $key has no options")
                    val value = text(answer)
                    if (value !in attribute.options) {
                        throw InvalidAnswer("$key must be one of ${attribute.options.joinToString()}")
                    }
                    if (key in forName) nameParts += value
                    secret.set(key, value)
                }
                Attribute.MULTILINE -> secret.write(text(answer))
                Attribute.HOSTNAME -> {
                    val value = text(answer)
                    val hostname = extractHostname(value)
                    if (hostname.isEmpty()) throw InvalidAnswer("cannot parse URL $value")
                    if (key in forName) nameParts += hostname
                    secret.set(key, value)
                }
                Attribute.PASSWORD ->
                    when (answer) {
                        is Answer.Generated -> {
                            password = answer.password
                            generated = true
                            secret.setPassword(password)
                        }
                        is Answer.Text -> {
                            checkLength(attribute, answer.value)
                            password = answer.value
                            generated = false
                            secret.setPassword(password)
                        }
                        Answer.Declined, null ->
                            if (!attribute.optional) throw InvalidAnswer("$key is required")
                    }
            }
        }
        return Created(name(template.prefix, nameParts), secret.text(), password, generated)
    }

    private fun text(answer: Answer?): String =
        when (answer) {
            is Answer.Text -> answer.value
            is Answer.Generated -> answer.password
            Answer.Declined, null -> ""
        }

    private fun checkLength(
        attribute: Attribute,
        value: String,
    ) {
        // Go's len(): bytes, not characters.
        val length = value.toByteArray(Charsets.UTF_8).size
        if (attribute.min > 0 && length < attribute.min) {
            throw InvalidAnswer("${attribute.name} is too short (needs ${attribute.min})")
        }
        if (attribute.max > 0 && length > attribute.max) {
            throw InvalidAnswer("${attribute.name} is too long (at most ${attribute.max})")
        }
    }

    /**
     * `prefix/part/part`, each part through [cleanFilename], joined the way
     * Go's filepath.Join does (empty parts dropped). `.` and `..` are dropped
     * too, so an answer cannot climb out of the store.
     */
    private fun name(
        prefix: String,
        parts: List<String>,
    ): String {
        val cleaned = parts.map(::cleanFilename).filter { it.isNotEmpty() && it != "." && it != ".." }
        if (cleaned.isEmpty()) throw InvalidAnswer("nothing to name the entry after")
        return (prefix.split('/') + cleaned)
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/")
    }

    private val UNSAFE = Regex("""[^A-Za-z0-9_@.-]""")

    /** gopass's `fsutil.CleanFilename`: anything but word characters, `@`, `.` and `-` becomes `_`, then `_` and spaces are trimmed. */
    fun cleanFilename(input: String): String = UNSAFE.replace(input, "_").trim('_', ' ')

    /**
     * gopass's `extractHostname`: the host of [input] read as a URL (`http://`
     * assumed when there is no scheme), made filename-safe; failing that, the
     * whole input made filename-safe.
     */
    fun extractHostname(input: String): String {
        if (input.isEmpty()) return ""
        val url = if ("://" in input) input else "http://$input"
        return hostOf(url)?.let(::cleanFilename)?.takeIf { it.isNotEmpty() } ?: cleanFilename(input)
    }

    /** The host of [url] without port or brackets, or null where Go's url.Parse would fail. */
    private fun hostOf(url: String): String? {
        val afterScheme = url.substringAfter("://")
        val scheme = url.substringBefore("://")
        if (scheme.isEmpty() || !scheme.first().isLetter() || scheme.any { !it.isLetterOrDigit() && it !in "+-." }) {
            return null
        }
        val authority = afterScheme.takeWhile { it !in "/?#" }.substringAfterLast('@')
        if (authority.any { it.code < 0x80 && !it.isLetterOrDigit() && it !in HOST_CHARS }) return null
        if (authority.startsWith('[')) return authority.substringBefore(']').removePrefix("[")
        val colon = authority.lastIndexOf(':')
        val port = if (colon >= 0) authority.substring(colon + 1) else null
        return if (port != null && port.all { it.isDigit() }) authority.substring(0, colon) else authority
    }

    /** What Go's url.Parse accepts in a host besides letters and digits. */
    private const val HOST_CHARS = "-._~!$&'()*+,;=:[]<>\"%"

    /**
     * gopass's AKV secret as `gopass create` fills it: the first line is the
     * password, `set` replaces a key's first line or appends one, `write`
     * appends text to the body.
     *
     * One deliberate difference: gopass takes the first line of text written
     * into a still-empty secret as the password, so a template that asks for
     * multiline text first loses that line to the password later. Here the
     * first line is always the password's.
     */
    private class KeyValueWriter {
        private val lines = mutableListOf("")

        fun set(
            key: String,
            value: String,
        ) {
            val at =
                lines.indices.drop(1).firstOrNull { i ->
                    ": " in lines[i] && lines[i].trim().substringBefore(':').trim() == key
                }
            if (at != null) lines[at] = "$key: $value" else lines += "$key: $value"
        }

        fun setPassword(password: String) {
            lines[0] = password
        }

        fun write(text: String) {
            if (text.isEmpty()) return
            lines += text.removeSuffix("\n").split('\n')
        }

        fun text(): String = lines.joinToString("\n") + "\n"
    }

    // Copied from gopass's internal/create/templates.go.
    private val DEFAULT_SOURCES =
        listOf(
            """
            ---
            priority: 0
            name: "Website login"
            prefix: "websites"
            name_from:
              - "url"
              - "username"
            welcome: "🧪 Creating Website login"
            attributes:
              - name: "url"
                type: "hostname"
                prompt: "Website URL"
                min: 1
                max: 255
              - name: "username"
                type: "string"
                prompt: "Username"
                min: 1
              - name: "password"
                type: "password"
                prompt: "Password for the Website"
            """.trimIndent(),
            """
            ---
            priority: 1
            name: "PIN Code (numerical)"
            prefix: "pin"
            name_from:
              - "authority"
              - "application"
            welcome: "🧪 Creating numerical PIN"
            attributes:
              - name: "authority"
                type: "string"
                prompt: "Authority"
                min: 1
              - name: "application"
                type: "string"
                prompt: "Entity"
                min: 1
              - name: "password"
                type: "password"
                prompt: "Pin"
                charset: "0123456789"
                min: 1
                max: 64
              - name: "comment"
                type: "string"
            """.trimIndent(),
            """
            ---
            priority: 2
            name: "SSO / passwordless login (e.g. Google)"
            prefix: "websites"
            name_from:
              - "url"
              - "username"
            welcome: "🧪 Creating SSO / passwordless login"
            attributes:
              - name: "url"
                type: "hostname"
                prompt: "Website URL"
                min: 1
                max: 255
              - name: "username"
                type: "string"
                prompt: "Account / email"
                min: 1
              - name: "login-method"
                type: "choice"
                prompt: "Login via"
                options:
                  - "google"
                  - "apple"
                  - "github"
                  - "microsoft"
                  - "sso"
                  - "magic-link"
              - name: "comment"
                type: "string"
            """.trimIndent(),
        )
}
