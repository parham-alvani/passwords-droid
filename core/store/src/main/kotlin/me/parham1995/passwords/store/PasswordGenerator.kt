package me.parham1995.passwords.store

import java.security.SecureRandom

/**
 * Random passwords. Every requested class is guaranteed to appear at least
 * once, because sites that demand "one digit" reject an unlucky draw.
 */
object PasswordGenerator {
    data class Options(
        val length: Int = 24,
        val lowercase: Boolean = true,
        val uppercase: Boolean = true,
        val digits: Boolean = true,
        val symbols: Boolean = true,
        /** Drops look-alikes (`l`, `1`, `I`, `O`, `0`) for passwords typed by hand. */
        val avoidAmbiguous: Boolean = false,
    )

    private const val LOWER = "abcdefghijklmnopqrstuvwxyz"
    private const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val DIGITS = "0123456789"
    private const val SYMBOLS = "!#\$%&*+-.:;=?@^_~"
    private const val AMBIGUOUS = "l1IO0"

    fun generate(
        options: Options = Options(),
        random: SecureRandom = SecureRandom(),
    ): String {
        val classes =
            listOfNotNull(
                LOWER.takeIf { options.lowercase },
                UPPER.takeIf { options.uppercase },
                DIGITS.takeIf { options.digits },
                SYMBOLS.takeIf { options.symbols },
            ).map { set -> if (options.avoidAmbiguous) set.filterNot { it in AMBIGUOUS } else set }
        require(classes.isNotEmpty()) { "choose at least one character class" }
        require(options.length >= classes.size) { "too short to include every class" }

        val all = classes.joinToString("")
        val chars = MutableList(options.length) { all[random.nextInt(all.length)] }
        // One guaranteed character per class, at distinct random positions.
        val positions = (0 until options.length).shuffled(random).take(classes.size)
        classes.zip(positions).forEach { (set, at) -> chars[at] = set[random.nextInt(set.length)] }
        return chars.joinToString("")
    }

    /** gopass's symbol class (`pwgen.Syms`), for telling which classes a charset holds. */
    private const val GOPASS_SYMBOLS = "!\"#\$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

    /**
     * [length] characters drawn from [charset], as gopass's
     * `GeneratePasswordCharset` does for a `gopass create` attribute with a
     * `charset`. With [strict] (`GeneratePasswordCharsetStrict`) every class --
     * digits, upper case, lower case, symbols -- that [charset] contains at all
     * appears at least once.
     */
    fun fromCharset(
        length: Int,
        charset: String,
        strict: Boolean = false,
        random: SecureRandom = SecureRandom(),
    ): String {
        require(charset.isNotEmpty()) { "empty character set" }
        require(length > 0) { "length must be positive" }
        val chars = MutableList(length) { charset[random.nextInt(charset.length)] }
        if (strict) {
            val classes =
                listOf(DIGITS, UPPER, LOWER, GOPASS_SYMBOLS)
                    .map { set -> charset.filter { it in set } }
                    .filter { it.isNotEmpty() }
            require(length >= classes.size) { "too short to include every class" }
            val positions = (0 until length).shuffled(random).take(classes.size)
            classes.zip(positions).forEach { (set, at) -> chars[at] = set[random.nextInt(set.length)] }
        }
        return chars.joinToString("")
    }

    private fun <T> Iterable<T>.shuffled(random: SecureRandom): List<T> = toMutableList().apply { shuffle(random) }
}
