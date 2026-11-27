package me.parham1995.passwords.ui

import androidx.compose.foundation.text.input.TextFieldState

/**
 * Copies what a secure text field holds into a new [CharArray], straight from
 * its [CharSequence], so the secret never becomes a [String] the caller cannot
 * wipe. Clear the field after this, and wipe the array after use.
 */
fun TextFieldState.toCharArray(): CharArray {
    val chars = text
    return CharArray(chars.length) { chars[it] }
}
