package me.parham1995.passwords.data.git

import com.google.common.truth.Truth.assertThat
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The SSH transport talks to GitHub and to nothing that merely claims to be it.
 *
 * Robolectric for the same reason as every other sshd test here: its EdDSA
 * registry is static, and the first classloader to initialise it wins.
 */
@RunWith(RobolectricTestRunner::class)
class PinnedHostKeysTest {
    private val database = PinnedHostKeys()

    private fun key(line: String) = PublicKeyEntry.parsePublicKeyEntry(line).resolvePublicKey(null, null, null)

    @Test
    fun `the pinned keys are the ones GitHub publishes`() {
        // The fingerprints from GitHub's documentation page, which is a second
        // source for the key text copied from api.github.com/meta. A single
        // wrong character in a pinned key makes every sync refuse GitHub.
        val fingerprints = PinnedHostKeys.GITHUB_KEYS.map { KeyUtils.getFingerPrint(key(it)) }

        assertThat(fingerprints).containsExactly(
            "SHA256:+DiY3wvvV6TuJJhbpZisF/zLDA0zPMSvHdkr4UvCOqU",
            "SHA256:p2QAMXNIC1TJYWeIOttrVc98/R1BUFWu3/LiyKgUfQM",
            "SHA256:uNiVztksCsDhcc0u9e8BujQXVUpKZIDTMczCvj3tD2s",
        )
    }

    @Test
    fun `GitHub's own key is accepted on both of its ports`() {
        PinnedHostKeys.GITHUB_KEYS.forEach { line ->
            assertThat(database.accept("github.com", null, key(line), null, null)).isTrue()
            assertThat(database.accept("[ssh.github.com]:443", null, key(line), null, null)).isTrue()
        }
    }

    @Test
    fun `a key GitHub never published is refused`() {
        // What anything between the phone and GitHub would present.
        val impostor = SshKeyGenerator.generate().pair.public

        assertThat(database.accept("github.com", null, impostor, null, null)).isFalse()
        assertThat(database.accept("[ssh.github.com]:443", null, impostor, null, null)).isFalse()
        assertThat(database.lastRefused).contains(KeyUtils.getFingerPrint(impostor))
    }

    @Test
    fun `GitHub's key on some other host is refused`() {
        val github = key(PinnedHostKeys.GITHUB_KEYS.first())

        assertThat(database.accept("git.example.com", null, github, null, null)).isFalse()
        assertThat(database.lookup("git.example.com", null, null)).isEmpty()
    }
}
