package me.parham1995.passwords.store

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Android compiles regexes with ICU, which refuses a bare `}` that the JVM
 * accepts as a literal. A JVM test of the regex itself passes either way, so
 * this reads the sources instead: every `Regex("""...""")` literal in the
 * project must escape a closing brace that is not ending a `{n,m}` quantifier.
 */
class RegexPortabilityTest {
    private val root =
        generateSequence(File("").absoluteFile) {
            it.parentFile
        }.first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun `no regex has a bare closing brace`() {
        val offenders =
            listOf("core", "app")
                .map { File(root, it) }
                .flatMap { dir ->
                    dir.walk().filter {
                        it.extension == "kt" &&
                            "/build/" !in it.path &&
                            "/src/main/" in it.path
                    }
                }.flatMap { file ->
                    REGEX_LITERAL.findAll(file.readText()).mapNotNull { match ->
                        val pattern = match.groupValues[1].replace(QUANTIFIER, "").replace("""\}""", "")
                        if ('}' in pattern) "${file.relativeTo(root)}: ${match.groupValues[1]}" else null
                    }
                }
        assertThat(offenders).isEmpty()
    }

    private companion object {
        val REGEX_LITERAL = Regex("Regex\\(\"\"\"(.*?)\"\"\"")
        val QUANTIFIER = Regex("""\{\d+(,\d*)?\}""")
    }
}
