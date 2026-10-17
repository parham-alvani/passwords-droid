package me.parham1995.passwords.feature.create

import me.parham1995.passwords.store.CreateWizard
import me.parham1995.passwords.store.CreateWizard.Answer
import me.parham1995.passwords.store.CreateWizard.Attribute
import me.parham1995.passwords.store.PasswordGenerator

/**
 * What has been typed into one `gopass create` template, and how it turns
 * into the answers [CreateWizard.create] takes. Plain data, no Android, so
 * the rules are tested on the JVM.
 */
data class WizardForm(
    val template: CreateWizard.Template,
    /** Text, hostname, multiline and choice answers, by attribute name. */
    val texts: Map<String, String> = emptyMap(),
    /** Password answers, by attribute name. */
    val passwords: Map<String, PasswordAnswer> = emptyMap(),
    /** The entry's name; follows the answers until [nameEdited]. */
    val name: String = "",
    val nameEdited: Boolean = false,
) {
    data class PasswordAnswer(
        val value: String = "",
        /** What the generator last made: saved unchanged, it counts as generated, as in gopass. */
        val generated: String? = null,
        /** Off for an `optional` password the account does not have (SSO, social login). */
        val present: Boolean = true,
        /** The length to generate, as typed; empty is [defaultLength]. */
        val length: String = "",
    )

    fun text(attribute: Attribute): String = texts[attribute.name].orEmpty()

    fun password(attribute: Attribute): PasswordAnswer = passwords[attribute.name] ?: PasswordAnswer()

    fun withText(
        attribute: Attribute,
        value: String,
    ): WizardForm = copy(texts = texts + (attribute.name to value)).followName()

    fun withPassword(
        attribute: Attribute,
        transform: (PasswordAnswer) -> PasswordAnswer,
    ): WizardForm = copy(passwords = passwords + (attribute.name to transform(password(attribute))))

    /** A typed name stops following the answers; an empty one follows them again. */
    fun withName(value: String): WizardForm =
        if (value.isBlank()) copy(nameEdited = false).followName() else copy(name = value, nameEdited = true)

    /** Generates a password for [attribute] at the length asked for. */
    fun generate(attribute: Attribute): WizardForm =
        withPassword(attribute) { answer ->
            val made = generatePassword(attribute, lengthOf(attribute, answer))
            answer.copy(value = made, generated = made)
        }

    /** The answers as [CreateWizard.create] takes them; a required password left empty is no answer. */
    fun answers(): Map<String, Answer> =
        template.attributes.mapNotNull { attribute -> answer(attribute)?.let { attribute.name to it } }.toMap()

    private fun answer(attribute: Attribute): Answer? {
        if (attribute.type != Attribute.PASSWORD) return Answer.Text(text(attribute))
        val answer = password(attribute)
        return when {
            attribute.optional && !answer.present -> Answer.Declined
            answer.value.isEmpty() -> null
            answer.value == answer.generated -> Answer.Generated(answer.value)
            else -> Answer.Text(answer.value)
        }
    }

    private fun followName(): WizardForm =
        if (nameEdited) this else copy(name = CreateWizard.nameOf(template, answers()).orEmpty())

    companion object {
        /** A fresh form: the first option of each choice chosen, passwords generated unless the template says to ask. */
        fun start(template: CreateWizard.Template): WizardForm {
            var form = WizardForm(template)
            template.attributes.forEach { attribute ->
                when (attribute.type) {
                    Attribute.CHOICE ->
                        attribute.options.firstOrNull()?.let {
                            form =
                                form.copy(texts = form.texts + (attribute.name to it))
                        }
                    Attribute.PASSWORD -> if (!attribute.alwaysPrompt) form = form.generate(attribute)
                }
            }
            return form.followName()
        }

        /** gopass offers 4 characters for a charset, and this app's usual 24 otherwise. */
        fun defaultLength(attribute: Attribute): Int =
            if (attribute.charset.isNotEmpty()) CreateWizard.CHARSET_LENGTH else PasswordGenerator.Options().length

        /** The length to generate: what was typed when it is usable, else [defaultLength]. */
        fun lengthOf(
            attribute: Attribute,
            answer: PasswordAnswer,
        ): Int =
            answer.length
                .toIntOrNull()
                ?.takeIf { it in minLength(attribute)..MAX_LENGTH }
                ?: defaultLength(attribute)

        /** Short enough is no good: the generator puts one of each class it uses in every password. */
        private fun minLength(attribute: Attribute): Int = if (attribute.charset.isNotEmpty()) 1 else MIN_GENERATED

        private fun generatePassword(
            attribute: Attribute,
            length: Int,
        ): String =
            if (attribute.charset.isNotEmpty()) {
                CreateWizard.generatePassword(attribute, length)
            } else {
                PasswordGenerator.generate(PasswordGenerator.Options(length = length))
            }

        /**
         * gopass's min and max for typed text, in UTF-8 bytes; null when it fits.
         * Empty text is left to the save, which names what is missing.
         */
        fun lengthError(
            attribute: Attribute,
            value: String,
        ): String? {
            if (value.isEmpty()) return null
            val length = value.toByteArray(Charsets.UTF_8).size
            return when {
                attribute.min > 0 && length < attribute.min -> "Too short (needs ${attribute.min})"
                attribute.max > 0 && length > attribute.max -> "Too long (at most ${attribute.max})"
                else -> null
            }
        }

        private const val MIN_GENERATED = 4
        private const val MAX_LENGTH = 128
    }
}
