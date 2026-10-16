package me.parham1995.passwords.data.git

import com.google.common.truth.Truth.assertThat
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import org.apache.sshd.common.util.security.SecurityUtils
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

/**
 * Runs under Robolectric, like everything else here that touches sshd.
 *
 * Not because it needs Android -- it does not -- but because sshd resolves its
 * EdDSA implementation through a static registry, and the first classloader to
 * initialise that registry wins for the whole JVM. With one test in
 * Robolectric's sandbox and another on the application classloader, the same
 * class arrives under two loaders and the cast between them fails.
 */
@RunWith(RobolectricTestRunner::class)
class SshKeyGeneratorTest {
    @Test
    fun `eddsa is actually available, not merely referenced`() {
        // sshd ships EdDSA support classes but no implementation. Without one
        // on the classpath this returns false and Ed25519 silently degrades to
        // another algorithm -- which is precisely what happened before.
        assertThat(SecurityUtils.isEDDSACurveSupported()).isTrue()
        assertThat(SshKeyGenerator.available()).contains(SshKeyGenerator.Algorithm.ED25519)
    }

    @Test
    fun `the generated key really is ed25519`() {
        val generated = SshKeyGenerator.generate()

        assertThat(generated.algorithm).isEqualTo(SshKeyGenerator.Algorithm.ED25519)
        // Asserting on the key type rather than on what we asked for: the bug
        // this replaces was a request for one algorithm that quietly produced
        // another.
        assertThat(SshKeyGenerator.keyType(generated.pair)).isEqualTo("ssh-ed25519")
    }

    @Test
    fun `the public line is a valid authorized_keys entry`() {
        val generated = SshKeyGenerator.generate()

        val line = PublicKeyEntry.toString(generated.pair.public)

        assertThat(line).startsWith("ssh-ed25519 ")
        // Round-trip it: a line GitHub would reject is worse than no key.
        val parsed = PublicKeyEntry.parsePublicKeyEntry(line).resolvePublicKey(null, null, null)
        assertThat(parsed).isEqualTo(generated.pair.public)
    }

    @Test
    fun `the private key is written in openssh format`() {
        val generated = SshKeyGenerator.generate()

        val out = ByteArrayOutputStream()
        OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(generated.pair, "test", null, out)
        val pem = out.toString(Charsets.UTF_8)

        // ssh will not load an identity file it cannot recognise.
        assertThat(pem).startsWith("-----BEGIN OPENSSH PRIVATE KEY-----")
        assertThat(pem).contains("-----END OPENSSH PRIVATE KEY-----")
    }

    @Test
    fun `two generations do not produce the same key`() {
        val first = SshKeyGenerator.generate()
        val second = SshKeyGenerator.generate()

        assertThat(first.pair.public).isNotEqualTo(second.pair.public)
    }
}
