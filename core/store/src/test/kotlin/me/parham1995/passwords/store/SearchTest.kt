package me.parham1995.passwords.store

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SearchTest {
    private val names = listOf("shopping/greenhouse", "dev/github.com/parham", "bank/mellat", "dev/gitlab.com/parham")

    @Test
    fun `contiguous matches rank first`() {
        assertThat(Search.rank("gith", names) { it }.first()).isEqualTo("dev/github.com/parham")
    }

    @Test
    fun `non-subsequences are dropped`() {
        assertThat(Search.rank("zzz", names) { it }).isEmpty()
    }

    @Test
    fun `an empty query keeps everything in order`() {
        assertThat(Search.rank(" ", names) { it }).isEqualTo(names)
    }

    @Test
    fun `case does not matter`() {
        assertThat(Search.rank("MELLAT", names) { it }).containsExactly("bank/mellat")
    }
}
