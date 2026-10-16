package me.parham1995.passwords.data.git

import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.util.security.SecurityUtils
import java.security.KeyPair

/**
 * Generates the SSH key pair, preferring the strongest algorithm the device can
 * actually produce.
 *
 * Kept free of Android so it can be tested directly, which earned its keep
 * immediately. The first version passed JCA algorithm names -- `EdDSA`, `EC`,
 * `RSA` -- to [KeyUtils.generateKeyPair], which wants the **SSH key type**
 * (`ssh-ed25519`) instead. Every algorithm then failed with "no decoder for key
 * type", and the version before that asked for `EC` while its comment claimed
 * Ed25519 and quietly succeeded with the wrong key. Neither is visible without
 * asserting on the key that comes out.
 */
object SshKeyGenerator {
    /**
     * In preference order. All three are accepted by GitHub, and the name is
     * the SSH key type because that is what sshd's generator takes.
     */
    enum class Algorithm(
        val sshKeyType: String,
        val size: Int,
    ) {
        ED25519(KeyPairProvider.SSH_ED25519, ED25519_SIZE),
        ECDSA(ECDSA_NISTP256, ECDSA_SIZE),
        RSA(KeyPairProvider.SSH_RSA, RSA_SIZE),
    }

    data class Generated(
        val pair: KeyPair,
        val algorithm: Algorithm,
    )

    /**
     * Ed25519 needs sshd's support classes *and* an implementation on the
     * classpath. Without one sshd reports the curve unsupported rather than
     * throwing, so it is checked rather than attempted.
     */
    fun available(): List<Algorithm> =
        Algorithm.entries.filter { it != Algorithm.ED25519 || SecurityUtils.isEDDSACurveSupported() }

    fun generate(): Generated {
        val failures = mutableListOf<String>()
        available().forEach { algorithm ->
            runCatching { KeyUtils.generateKeyPair(algorithm.sshKeyType, algorithm.size) }
                .onSuccess { return Generated(it, algorithm) }
                // Naming each algorithm and its reason: on a device this is the
                // only signal about what the platform actually refused.
                .onFailure { failures += algorithm.name + " -> " + it::class.simpleName + ": " + it.message }
        }
        error("no usable SSH key algorithm - " + failures.joinToString("; "))
    }

    /** The `ssh-...` type string, as it appears in an authorized_keys line. */
    fun keyType(pair: KeyPair): String = KeyUtils.getKeyType(pair)

    private const val ECDSA_NISTP256 = "ecdsa-sha2-nistp256"
    private const val ED25519_SIZE = 256
    private const val ECDSA_SIZE = 256
    private const val RSA_SIZE = 4096
}
