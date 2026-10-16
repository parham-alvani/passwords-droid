package me.parham1995.passwords.store

import java.io.File
import java.security.MessageDigest

/**
 * gopass templates: a plain, unencrypted `.pass-template` file in a folder
 * that shapes every secret `gopass generate` creates in that folder or below
 * it -- the nearest one wins. The app applies them to new entries the same way.
 *
 * Only what gopass's own documentation shows is rendered: `.Content` (the
 * password), `.Name` (the entry's last segment), `.Path` (its full name) and
 * the `md5sum`/`sha1sum`/`sha256sum`/`sha512sum` functions of `.Content`. Any
 * other action is left as written, so nothing is silently mistranslated.
 */
object Templates {
    const val FILE = ".pass-template"

    /** The template governing a new entry at [name], or null when there is none. */
    fun find(
        root: File,
        name: String,
    ): String? {
        var dir: File? = File(root, name).parentFile
        val top = root.canonicalFile
        while (dir != null && dir.canonicalFile.path.startsWith(top.path)) {
            val file = File(dir, FILE)
            if (file.isFile) return file.readText()
            if (dir.canonicalFile == top) break
            dir = dir.parentFile
        }
        return null
    }

    fun render(
        template: String,
        name: String,
        password: String,
    ): String =
        ACTION.replace(template) { match ->
            when (val expression = match.groupValues[1].trim().replace(Regex("\\s+"), " ")) {
                ".Content" -> password
                ".Name" -> name.substringAfterLast('/')
                ".Path" -> name
                "md5sum .Content" -> hash("MD5", password)
                "sha1sum .Content" -> hash("SHA-1", password)
                "sha256sum .Content" -> hash("SHA-256", password)
                "sha512sum .Content" -> hash("SHA-512", password)
                else -> match.value.also { if (expression.isEmpty()) return@replace it }
            }
        }

    private fun hash(
        algorithm: String,
        value: String,
    ): String = MessageDigest.getInstance(algorithm).digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private val ACTION = Regex("""\{\{-?\s*(.*?)\s*-?}}""")
}
