package me.parham1995.passwords.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PassphraseCacheTest {
    private var now = 0L
    private val cache =
        PassphraseCache(
            ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("t", Context.MODE_PRIVATE),
            clock = { now },
        )

    @Test
    fun `remembers until idle for longer than the ttl`() {
        cache.remember("FP", "secret".toCharArray())
        now = 4_000
        assertThat(cache.recall("FP", ttlMillis = 5_000)?.concatToString()).isEqualTo("secret")
        // Use restarted the clock, so 8s after remembering is still only 4s idle.
        now = 8_000
        assertThat(cache.recall("FP", ttlMillis = 5_000)).isNotNull()
        now = 14_000
        assertThat(cache.recall("FP", ttlMillis = 5_000)).isNull()
    }

    @Test
    fun `forgetAll empties memory but keeps sealed blobs`() {
        cache.remember("FP", "secret".toCharArray())
        cache.storeSealed("FP", byteArrayOf(1, 2, 3))
        cache.forgetAll()
        assertThat(cache.recall("FP", Long.MAX_VALUE)).isNull()
        assertThat(cache.sealed("FP")).isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test
    fun `passphrases survive the byte round trip, including non-ascii`() {
        val original = "رمز-عبور-🔑".toCharArray()
        assertThat(PassphraseCache.decode(PassphraseCache.encode(original))).isEqualTo(original)
    }
}
