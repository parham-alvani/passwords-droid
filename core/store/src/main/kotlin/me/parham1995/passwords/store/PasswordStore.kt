package me.parham1995.passwords.store

import java.io.File

/**
 * A password store on disk: a directory of `.gpg` files, with `.gpg-id` files
 * saying who each subtree is encrypted for.
 *
 * Read-only knowledge of the layout lives here; decrypting and syncing are
 * other modules' business. Nothing in this class touches Android.
 */
class PasswordStore(
    val root: File,
) {
    /** One `.gpg` file, named the way `pass` names it: its path without the extension. */
    data class Item(
        val name: String,
        val file: File,
    ) {
        /** `aut/email/parham` -> `parham`. */
        val leaf: String get() = name.substringAfterLast('/')

        /** `aut/email/parham` -> `aut/email`, empty at the top. */
        val folder: String get() = name.substringBeforeLast('/', "")
    }

    /** Every entry, sorted by name. Hidden directories (`.git`, `.gopass`, `.public-keys`) are skipped. */
    fun items(): List<Item> {
        if (!root.isDirectory) return emptyList()
        return root
            .walkTopDown()
            .onEnter { it == root || !it.name.startsWith('.') }
            .filter { it.isFile && it.name.endsWith(EXTENSION) }
            .map { Item(nameOf(it), it) }
            .sortedBy { it.name }
            .toList()
    }

    fun file(name: String): File = File(root, name + EXTENSION)

    fun nameOf(file: File): String = file.relativeTo(root).invariantSeparatorsPath.removeSuffix(EXTENSION)

    /**
     * The recipients an entry must be encrypted for: the `.gpg-id` nearest to
     * it, walking up to the root. `pass` and gopass both resolve it this way,
     * so a subfolder shared with someone else stays shared after an edit here.
     */
    fun recipientsFor(name: String): List<String> {
        var dir: File? = file(name).parentFile
        while (dir != null && dir.startsWith(root)) {
            val ids = File(dir, GPG_ID)
            if (ids.isFile) return parseGpgId(ids.readText())
            if (dir == root) break
            dir = dir.parentFile
        }
        return emptyList()
    }

    /**
     * Armored public keys gopass exports next to the store (`core.exportkeys`),
     * one file per recipient id. They let the phone encrypt for every
     * recipient without a keyring of its own.
     */
    fun exportedPublicKeys(): Map<String, File> =
        File(root, PUBLIC_KEYS)
            .listFiles()
            .orEmpty()
            .filter { it.isFile }
            .associateBy { it.name }

    companion object {
        const val EXTENSION = ".gpg"
        const val GPG_ID = ".gpg-id"
        const val PUBLIC_KEYS = ".public-keys"

        /** One id per line; blank lines and `#` comments are ignored. */
        fun parseGpgId(text: String): List<String> =
            text
                .lineSequence()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
                .toList()
    }
}
