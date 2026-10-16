package me.parham1995.passwords.store

/**
 * Finds the entries that belong to a website or an app, from their names
 * alone -- nothing is decrypted to answer the question.
 *
 * It relies on the convention `pass` users and Password Store share: a folder
 * named after the site, a file named after the account, e.g.
 * `aveehealth/miro.com/parham.gpg`. A web page on `www.miro.com` or
 * `eu.miro.com` matches it; an app matches through its package name read as a
 * reversed domain (`com.github.android` -> `github.com`) or through a folder
 * named like its brand (`org.telegram.messenger` -> `telegram/`).
 *
 * Better matches come first. Nothing is guessed from substrings: `bank.com`
 * must not offer the passwords kept under `mybank.com`.
 */
object CredentialMatcher {
    sealed interface Target {
        /** A page's host, from a browser trusted to report it. */
        data class Web(
            val host: String,
        ) : Target

        /** A native app, by package name. */
        data class App(
            val packageName: String,
        ) : Target
    }

    data class Match(
        val item: PasswordStore.Item,
        /** The account name to show and to fill: the entry's file name. */
        val username: String,
        val score: Int,
    )

    fun find(
        items: List<PasswordStore.Item>,
        target: Target,
        limit: Int = DEFAULT_LIMIT,
    ): List<Match> =
        items
            .mapNotNull { item -> score(item, target)?.let { Match(item, usernameOf(item), it) } }
            // An alias and its target are the same login; show it once.
            .distinctBy { it.item.aliasOf ?: it.item.name }
            .sortedWith(compareByDescending<Match> { it.score }.thenBy { it.item.name })
            .take(limit)

    /** `site/alice.gpg` -> `alice`; `site/alice/password.gpg` (one folder per account) -> `alice`. */
    fun usernameOf(item: PasswordStore.Item): String =
        if (item.leaf.lowercase() in GENERIC_LEAVES &&
            item.folder.isNotEmpty()
        ) {
            item.folder.substringAfterLast('/')
        } else {
            item.leaf
        }

    private fun score(
        item: PasswordStore.Item,
        target: Target,
    ): Int? {
        val folders =
            item.folder
                .split('/')
                .filter { it.isNotEmpty() }
                .map { it.lowercase() }
        if (folders.isEmpty()) return null
        return when (target) {
            is Target.Web -> {
                val host = normalizeHost(target.host) ?: return null
                folders.mapNotNull { domainScore(host, it) }.maxOrNull()
            }
            is Target.App -> {
                val labels =
                    target.packageName
                        .lowercase()
                        .split('.')
                        .filter { it.isNotEmpty() }
                if (labels.size < 2) return null
                val reversed = "${labels[1]}.${labels[0]}"
                folders
                    .mapNotNull { folder ->
                        when {
                            folder == target.packageName.lowercase() -> EXACT
                            domainScore(reversed, folder) != null -> SUBDOMAIN
                            // `telegram/` for org.telegram.messenger: a brand folder.
                            '.' !in folder && folder == labels[1] && labels[1] !in GENERIC_LABELS -> BRAND
                            else -> null
                        }
                    }.maxOrNull()
            }
        }
    }

    /** How well a folder named like a domain covers [host]; null when it does not. */
    private fun domainScore(
        host: String,
        folder: String,
    ): Int? {
        val domain = normalizeHost(folder) ?: return null
        if ('.' !in domain) return null
        return when {
            host == domain -> EXACT
            host.endsWith(".$domain") -> SUBDOMAIN
            // A folder for one subdomain also serves its siblings' sign-in:
            // `accounts.aut.ac.ir` for a page on `portal.aut.ac.ir`.
            registrable(host) == registrable(domain) -> SIBLING
            else -> null
        }
    }

    private fun normalizeHost(value: String): String? =
        value
            .trim()
            .lowercase()
            .substringAfter("://")
            .substringBefore('/')
            .substringBefore(':')
            .removePrefix("www.")
            .takeIf { it.isNotEmpty() }

    /**
     * The last two labels, or three for a two-part public suffix such as
     * `ac.ir` or `co.uk`. Not the full public-suffix list, which is 200KB for a
     * rule this store never needs beyond a handful of country suffixes.
     */
    internal fun registrable(host: String): String {
        val labels = host.split('.')
        if (labels.size <= 2) return host
        val lastTwo = labels.takeLast(2).joinToString(".")
        val take = if (lastTwo in TWO_PART_SUFFIXES || labels[labels.size - 2] in SECOND_LEVEL) 3 else 2
        return labels.takeLast(minOf(take, labels.size)).joinToString(".")
    }

    private const val DEFAULT_LIMIT = 12
    private const val EXACT = 100
    private const val SUBDOMAIN = 80
    private const val SIBLING = 60
    private const val BRAND = 40

    private val GENERIC_LEAVES = setOf("password", "pass", "login")
    private val GENERIC_LABELS = setOf("android", "google", "app", "apps", "mobile", "client")
    private val TWO_PART_SUFFIXES = setOf("co.uk", "org.uk", "com.au", "co.jp", "com.br", "co.ir")
    private val SECOND_LEVEL = setOf("ac", "co", "com", "gov", "org", "net", "edu", "sch")
}
