package me.parham1995.passwords.store

/**
 * Ranks entries against what was typed, the way a fuzzy finder does: every
 * character of the query must appear in order, and matches that start words,
 * run together, or sit in the last path segment rank higher.
 *
 * `gh` finds `dev/github.com/parham`, and puts it above
 * `shopping/greenhouse` because the match is contiguous.
 */
object Search {
    fun <T> rank(
        query: String,
        items: List<T>,
        nameOf: (T) -> String,
    ): List<T> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return items
        return items
            .mapNotNull { item -> score(needle, nameOf(item).lowercase())?.let { item to it } }
            .sortedWith(compareByDescending<Pair<T, Int>> { it.second }.thenBy { nameOf(it.first) })
            .map { it.first }
    }

    /** Null when [needle] is not a subsequence of [haystack]. */
    internal fun score(
        needle: String,
        haystack: String,
    ): Int? {
        val leafStart = haystack.lastIndexOf('/') + 1
        var score = 0
        var from = 0
        var previous = -2
        needle.forEach { char ->
            val at = haystack.indexOf(char, from)
            if (at < 0) return null
            score +=
                when {
                    at == previous + 1 -> CONTIGUOUS
                    at == 0 || haystack[at - 1] in BOUNDARIES -> WORD_START
                    else -> 1
                }
            if (at >= leafStart) score += IN_LEAF
            previous = at
            from = at + 1
        }
        // Prefer the shorter name when everything else is equal.
        return score * LENGTH_WEIGHT - haystack.length
    }

    private const val CONTIGUOUS = 8
    private const val WORD_START = 6
    private const val IN_LEAF = 2
    private const val LENGTH_WEIGHT = 100
    private val BOUNDARIES = setOf('/', '.', '-', '_', ' ', '@')
}
