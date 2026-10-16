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
    private val canonicalRoot: File by lazy { root.canonicalFile }

    /** One `.gpg` file, named the way `pass` names it: its path without the extension. */
    data class Item(
        val name: String,
        val file: File,
        /**
         * The entry's real name when [name] reaches it through a symlink --
         * a folder alias such as `mail.example.com -> mail`, kept so the same
         * login matches under both domains. Null for the real thing.
         */
        val aliasOf: String? = null,
    ) {
        /** `aut/email/parham` -> `parham`. */
        val leaf: String get() = name.substringAfterLast('/')

        /** `aut/email/parham` -> `aut/email`, empty at the top. */
        val folder: String get() = name.substringBeforeLast('/', "")
    }

    /**
     * Every entry, sorted by name. Hidden directories (`.git`, `.gopass`,
     * `.public-keys`) are skipped.
     *
     * Symlinks are followed the way `pass ls` follows them, so an aliased
     * folder lists its entries under both names -- but only when the link
     * lands inside the store. A link out of it (to the app's own keys, say,
     * in a repository someone else can push to) is ignored, and a link back
     * up to an ancestor is not walked into twice.
     */
    fun items(): List<Item> {
        if (!root.isDirectory) return emptyList()
        val found = mutableListOf<Item>()
        visit(root, "", setOf(canonicalRoot.path), found)
        return found.sortedBy { it.name }
    }

    private fun visit(
        dir: File,
        prefix: String,
        ancestors: Set<String>,
        found: MutableList<Item>,
    ) {
        if (ancestors.size > MAX_DEPTH) return
        dir.listFiles().orEmpty().forEach { child ->
            if (child.name.startsWith('.')) return@forEach
            val real = runCatching { child.canonicalFile }.getOrNull() ?: return@forEach
            if (!isInside(real)) return@forEach
            when {
                child.isDirectory ->
                    if (real.path !in ancestors) visit(child, prefix + child.name + "/", ancestors + real.path, found)
                child.isFile && child.name.endsWith(EXTENSION) -> {
                    val name = prefix + child.name.removeSuffix(EXTENSION)
                    val realName = nameOfReal(real)
                    found += Item(name, child, aliasOf = realName.takeIf { it != name })
                }
            }
        }
    }

    /**
     * The file for [name], refusing any name -- `..`, an absolute path, a
     * symlink -- that would land outside the store.
     */
    fun file(name: String): File {
        val file = File(root, name + EXTENSION)
        require(isInside(file.canonicalFile)) { "$name is outside the store" }
        return file
    }

    /**
     * The name of the file [name] really is, following any symlink on the way.
     * Writes and commits go there: git refuses a path through a symlink, and
     * an edit made through an alias belongs to the entry it aliases.
     */
    fun realName(name: String): String = nameOfReal(file(name).canonicalFile)

    /**
     * The name of [name]'s own file, resolving symlinked folders on the way
     * but not a symlink at the end: deleting a file alias removes the alias,
     * never the entry it points at.
     */
    fun ownName(name: String): String {
        val file = file(name)
        return nameOfReal(File(file.parentFile.canonicalFile, file.name))
    }

    fun isAlias(name: String): Boolean =
        java.nio.file.Files
            .isSymbolicLink(file(name).toPath())

    private fun nameOfReal(real: File): String =
        real.relativeTo(canonicalRoot).invariantSeparatorsPath.removeSuffix(EXTENSION)

    private fun isInside(real: File): Boolean = real.path.startsWith(canonicalRoot.path + File.separator)

    fun nameOf(file: File): String = file.relativeTo(root).invariantSeparatorsPath.removeSuffix(EXTENSION)

    /**
     * The recipients an entry must be encrypted for: the `.gpg-id` nearest to
     * it, walking up to the root. `pass` and gopass both resolve it this way,
     * so a subfolder shared with someone else stays shared after an edit here.
     */
    fun recipientsFor(name: String): List<String> {
        // The .gpg-id that governs the real file, not the alias's folder.
        var dir: File? = File(root, realName(name) + EXTENSION).parentFile
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
        private const val MAX_DEPTH = 32

        /** One id per line; blank lines and `#` comments are ignored. */
        fun parseGpgId(text: String): List<String> =
            text
                .lineSequence()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
                .toList()
    }
}
