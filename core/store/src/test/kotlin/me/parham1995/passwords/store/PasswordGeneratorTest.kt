package me.parham1995.passwords.store

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PasswordGeneratorTest {
    @Test
    fun `every class appears, every time`() {
        repeat(500) {
            val pw = PasswordGenerator.generate(PasswordGenerator.Options(length = 4))
            assertThat(pw).hasLength(4)
            assertThat(pw.any { it.isLowerCase() }).isTrue()
            assertThat(pw.any { it.isUpperCase() }).isTrue()
            assertThat(pw.any { it.isDigit() }).isTrue()
            assertThat(pw.any { !it.isLetterOrDigit() }).isTrue()
        }
    }

    @Test
    fun `ambiguous characters can be left out`() {
        repeat(200) {
            val pw = PasswordGenerator.generate(PasswordGenerator.Options(length = 64, avoidAmbiguous = true))
            assertThat(pw.none { it in "l1IO0" }).isTrue()
        }
    }
}
