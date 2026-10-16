package me.parham1995.passwords.store

import com.nulabinc.zxcvbn.Zxcvbn
import java.security.MessageDigest

/**
 * Which passwords in the store deserve attention: weak, reused, or not
 * changed in years. Breaches are a separate, opt-in check (see [Pwned]).
 *
 * The report names entries and never holds a password: reuse is found by
 * comparing hashes, and the caller wipes what it decrypted.
 */
object PasswordHealth {
    data class Candidate(
        val name: String,
        val password: String,
        /** Epoch millis of the last commit that changed the entry, if known. */
        val lastChanged: Long?,
    )

    data class Weak(
        val name: String,
        /** zxcvbn's 0..4. */
        val score: Int,
        val hint: String?,
    )

    data class Report(
        val checked: Int,
        val weak: List<Weak>,
        /** Each group is the entries sharing one password. */
        val reused: List<List<String>>,
        /** Entries unchanged for longer than the threshold, oldest first, with their last change. */
        val old: List<Pair<String, Long>>,
    ) {
        val issues: Int get() = weak.size + reused.sumOf { it.size } + old.size
    }

    private val zxcvbn by lazy { Zxcvbn() }

    fun analyze(
        candidates: List<Candidate>,
        now: Long,
        maxAgeMillis: Long = TWO_YEARS,
        weakBelow: Int = 3,
    ): Report {
        val usable = candidates.filter { it.password.isNotBlank() }
        val weak =
            usable
                .mapNotNull { c ->
                    // zxcvbn's own guidance: the first 100 characters decide the score.
                    val strength = zxcvbn.measure(c.password.take(MAX_MEASURED))
                    if (strength.score < weakBelow) {
                        Weak(c.name, strength.score, strength.feedback.warning?.takeIf { it.isNotBlank() })
                    } else {
                        null
                    }
                }.sortedWith(compareBy({ it.score }, { it.name }))
        val reused =
            usable
                .groupBy { digest(it.password) }
                .values
                .filter { it.size > 1 }
                .map { group -> group.map { it.name }.sorted() }
                .sortedByDescending { it.size }
        val old =
            usable
                .mapNotNull { c -> c.lastChanged?.takeIf { now - it > maxAgeMillis }?.let { c.name to it } }
                .sortedBy { it.second }
        return Report(usable.size, weak, reused, old)
    }

    private fun digest(password: String): String =
        MessageDigest.getInstance("SHA-256").digest(password.toByteArray()).joinToString("") { "%02x".format(it) }

    private const val MAX_MEASURED = 100
    const val TWO_YEARS = 2L * 365 * 24 * 60 * 60 * 1000
}

/**
 * Have I Been Pwned's k-anonymity check. Only the first five hex characters
 * of the password's SHA-1 leave the phone; the answer is every known suffix
 * under that prefix (padded with decoys), matched here.
 */
object Pwned {
    fun sha1(password: String): String =
        MessageDigest.getInstance("SHA-1").digest(password.toByteArray()).joinToString("") { "%02X".format(it) }

    /** How often the password with [hash] appears in [rangeResponse]; 0 when it does not. */
    fun count(
        hash: String,
        rangeResponse: String,
    ): Int {
        val suffix = hash.substring(PREFIX_LENGTH)
        return rangeResponse
            .lineSequence()
            .map { it.trim().split(':') }
            .firstOrNull { it.size == 2 && it[0].equals(suffix, ignoreCase = true) }
            ?.get(1)
            ?.toIntOrNull() ?: 0
    }

    const val PREFIX_LENGTH = 5
}
