package me.parham1995.passwords.store

/**
 * The store as folders, the way `gopass ls` draws it: what one folder holds
 * directly, with its subfolders first.
 *
 * Derived from the flat list of entries rather than read from disk, so a
 * folder exists exactly when something is in it -- an empty directory left
 * behind by a deletion, or one holding only a `.gpg-id`, is not shown.
 */
object StoreTree {
    data class Folder(
        val name: String,
        /** From the store's root, e.g. `aut/email`. */
        val path: String,
        /** Entries anywhere below it, so a deep folder is not mistaken for an empty one. */
        val count: Int,
    )

    data class Listing(
        val path: String,
        val folders: List<Folder>,
        val entries: List<PasswordStore.Item>,
    ) {
        val isEmpty: Boolean get() = folders.isEmpty() && entries.isEmpty()
    }

    fun list(
        items: List<PasswordStore.Item>,
        path: String,
    ): Listing {
        val prefix = if (path.isEmpty()) "" else "$path/"
        val counts = sortedMapOf<String, Int>(String.CASE_INSENSITIVE_ORDER)
        val entries = mutableListOf<PasswordStore.Item>()
        items.forEach { item ->
            if (!item.name.startsWith(prefix)) return@forEach
            val rest = item.name.removePrefix(prefix)
            val slash = rest.indexOf('/')
            if (slash < 0) {
                entries += item
            } else {
                val child = rest.substring(0, slash)
                counts[child] = (counts[child] ?: 0) + 1
            }
        }
        return Listing(
            path = path,
            folders = counts.map { (name, count) -> Folder(name, prefix + name, count) },
            entries = entries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.leaf }),
        )
    }

    /** `aut/email/work` -> `aut/email`; the root's parent is null. */
    fun parent(path: String): String? = if (path.isEmpty()) null else path.substringBeforeLast('/', "")

    /** Each step from the root down to [path], for a breadcrumb: `aut`, `aut/email`, ... */
    fun crumbs(path: String): List<Pair<String, String>> {
        if (path.isEmpty()) return emptyList()
        val parts = path.split('/')
        return parts.indices.map { i -> parts[i] to parts.subList(0, i + 1).joinToString("/") }
    }
}
