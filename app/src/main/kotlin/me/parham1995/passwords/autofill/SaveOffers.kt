package me.parham1995.passwords.autofill

import android.os.SystemClock
import java.util.UUID

/**
 * What a form offered to save, held in memory between the save request and
 * the dialog that encrypts it. Only a random token goes into the (mutable)
 * PendingIntent; the values never leave the process, are handed out once, and
 * are wiped when taken or when they go stale.
 */
internal object SaveOffers {
    class Offer(
        val username: CharArray,
        val password: CharArray,
        val at: Long = SystemClock.elapsedRealtime(),
    ) {
        fun wipe() {
            username.fill('\u0000')
            password.fill('\u0000')
        }
    }

    private val offers = mutableMapOf<String, Offer>()

    /** Keeps copies of [username] and [password]; the caller wipes its own. */
    @Synchronized
    fun put(
        username: CharArray,
        password: CharArray,
    ): String {
        val now = SystemClock.elapsedRealtime()
        offers.values.removeAll { offer -> (now - offer.at > MAX_AGE_MILLIS).also { if (it) offer.wipe() } }
        val token = UUID.randomUUID().toString()
        offers[token] = Offer(username.copyOf(), password.copyOf())
        return token
    }

    /** The offer under [token], removed; the caller wipes it. Null if taken, stale, or the process restarted. */
    @Synchronized
    fun take(token: String): Offer? = offers.remove(token)

    /** A save prompt the user has not answered in this long is not coming back. */
    private const val MAX_AGE_MILLIS = 10 * 60 * 1000L
}
