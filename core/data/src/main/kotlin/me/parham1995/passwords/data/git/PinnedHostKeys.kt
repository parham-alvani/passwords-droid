package me.parham1995.passwords.data.git

import android.annotation.SuppressLint
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase
import java.net.InetSocketAddress
import java.security.PublicKey

/**
 * The only SSH host keys the app will talk to: GitHub's, as GitHub publishes
 * them.
 *
 * There is no prompt on a phone and no known_hosts to seed, and this used to
 * be answered by accepting whatever key the other end presented. The deploy
 * key still authenticates *us*, but nothing authenticated *them*: anything
 * between the phone and GitHub -- a hotel captive portal, a hostile Wi-Fi --
 * could answer as github.com, take the fetch and serve a vault of its own, or
 * take a push and keep it. Every repository this app reads is on GitHub, so
 * the right set of keys is known in advance and small enough to write down.
 *
 * Taken from https://api.github.com/meta (`ssh_keys`) and checked against the
 * fingerprints on GitHub's documentation page:
 *
 * - Ed25519 `SHA256:+DiY3wvvV6TuJJhbpZisF/zLDA0zPMSvHdkr4UvCOqU`
 * - ECDSA `SHA256:p2QAMXNIC1TJYWeIOttrVc98/R1BUFWu3/LiyKgUfQM`
 * - RSA `SHA256:uNiVztksCsDhcc0u9e8BujQXVUpKZIDTMczCvj3tD2s`
 *
 * GitHub serves the same keys on `ssh.github.com:443`, the route round a
 * network that blocks port 22. If GitHub ever rotates them, this list is what
 * has to change, and a sync fails with a message naming the key it was shown
 * rather than trusting it.
 */
class PinnedHostKeys(
    private val hosts: Set<String> = GITHUB_HOSTS,
    keyLines: List<String> = GITHUB_KEYS,
) : ServerKeyDatabase {
    private val pinned: List<PublicKey> by lazy {
        keyLines.map { PublicKeyEntry.parsePublicKeyEntry(it).resolvePublicKey(null, null, null) }
    }

    /**
     * The key most recently refused, and the host that offered it, so the
     * failure can say so. sshd reports a refused host key in its own words,
     * which read like any other broken connection.
     */
    @Volatile
    var lastRefused: String? = null
        private set

    override fun lookup(
        connectAddress: String?,
        remoteAddress: InetSocketAddress?,
        config: ServerKeyDatabase.Configuration?,
    ): List<PublicKey> = if (hostOf(connectAddress) in hosts) pinned else emptyList()

    override fun accept(
        connectAddress: String?,
        remoteAddress: InetSocketAddress?,
        serverKey: PublicKey?,
        config: ServerKeyDatabase.Configuration?,
        provider: CredentialsProvider?,
    ): Boolean {
        val host = hostOf(connectAddress)
        val known = serverKey != null && host in hosts && pinned.any { KeyUtils.compareKeys(it, serverKey) }
        if (!known) {
            val fingerprint = serverKey?.let { KeyUtils.getFingerPrint(it) } ?: "no key"
            lastRefused = "${connectAddress ?: "an unnamed host"} presented $fingerprint"
        }
        return known
    }

    companion object {
        val GITHUB_HOSTS = setOf("github.com", "ssh.github.com")

        /**
         * Split across lines for length, and joined with no space on purpose:
         * base64 has none. `PinnedHostKeysTest` checks each joined key's
         * fingerprint against the published one, so a split in the wrong place
         * fails the build.
         */
        @SuppressLint("TextConcatSpace")
        val GITHUB_KEYS =
            listOf(
                "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl",
                "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEmKSENjQEezOmxkZMy7opKgwF" +
                    "B9nkt5YRrYMjNuG5N87uRgg6CLrbo5wAdT/y6v0mKV0U2w0WZ2YB/++Tpockg=",
                "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQCj7ndNxQowgcQnjshcLrqPEiiphnt+VTTvDP6mHBL9j1aNUkY4Ue1gvwnGLV" +
                    "lOhGeYrnZaMgRK6+PKCUXaDbC7qtbW8gIkhL7aGCsOr/C56SJMy/BCZfxd1nWzAOxSDPgVsmerOBYfNqltV9/hWCqBywINIR" +
                    "+5dIg6JTJ72pcEpEjcYgXkE2YEFXV1JHnsKgbLWNlhScqb2UmyRkQyytRLtL+38TGxkxCflmO+5Z8CSSNY7GidjMIZ7Q4zMj" +
                    "A2n1nGrlTDkzwDCsw+wqFPGQA179cnfGWOWRVruj16z6XyvxvjJwbz0wQZ75XK5tKSb7FNyeIEs4TT4jk+S4dhPeAUC5y+bD" +
                    "YirYgM4GC7uEnztnZyaVWQ7B381AK4Qdrwt51ZqExKbQpTUNn+EjqoTwvqNj4kqx5QUCI0ThS/YkOxJCXmPUWZbhjpCg56i+" +
                    "2aB6CmK2JGhn57K5mj0MNdBXA4/WnwH6XoPWJzK5Nyu2zB3nAZp+S5hpQs+p1vN1/wsjk=",
            )

        /**
         * `github.com` or `[ssh.github.com]:443` -- the known_hosts form sshd
         * hands over -- down to the bare host name.
         */
        internal fun hostOf(connectAddress: String?): String {
            val address = connectAddress.orEmpty().trim()
            return if (address.startsWith("[")) {
                address.substringAfter('[').substringBefore(']')
            } else {
                address.substringBefore(':')
            }.lowercase()
        }
    }
}
