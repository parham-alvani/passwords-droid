package me.parham1995.passwords.data.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import me.parham1995.passwords.data.security.SecretBox
import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import org.apache.sshd.common.util.security.SecurityUtils
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPair

/**
 * The SSH key the store is synced with.
 *
 * Generated **on the device**; the private half never leaves it. The public
 * line is registered on GitHub as a deploy key on the store's repository --
 * with write access, because unlike a notes reader this app commits.
 *
 * The private key is kept sealed by [box] (the at-rest Keystore key), so a
 * copy of the app's files does not carry a key that can push to the store.
 * sshd is handed the key pair from memory; nothing unsealed touches the disk.
 * A plaintext key left by an older version is sealed, and the plaintext
 * deleted, the first time it is read.
 *
 * One store, one key. A deploy key works on exactly one repository, so a
 * second store would need a second key, and that is a problem for the day
 * there is a second store.
 */
class SshKeyStore(
    private val sshDir: File,
    private val box: SecretBox,
    configDir: File,
) {
    init {
        // sshd's static initialisers throw on Android unless this has run,
        // and a poisoned class stays poisoned for the life of the process.
        AndroidGitEnvironment.install(configDir)
        sshDir.mkdirs()
    }

    private val _revision = MutableStateFlow(0)

    /** Bumped when the key is created or removed; files have nothing to subscribe to. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    val directory: File get() = sshDir
    private val sealedFile: File get() = File(sshDir, NAME + SEALED_SUFFIX)
    private val legacyPlaintext: File get() = File(sshDir, NAME)
    private val publicFile: File get() = File(sshDir, NAME + PUBLIC_SUFFIX)

    /**
     * Kept once unsealed: the at-rest key needs an unlocked phone, and a
     * background sync may run while it is locked.
     */
    @Volatile
    private var cached: KeyPair? = null

    fun exists(): Boolean = (sealedFile.isFile || legacyPlaintext.isFile) && publicFile.isFile

    /** The line to paste into GitHub's deploy-key box. */
    fun publicKeyLine(): String? = publicFile.takeIf { it.isFile }?.readText()?.trim()

    suspend fun generate(comment: String = "passwords-droid"): String =
        withContext(Dispatchers.IO) {
            val pair = SshKeyGenerator.generate().pair
            val pem =
                ByteArrayOutputStream().use { out ->
                    OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(pair, comment, null, out)
                    out.toByteArray()
                }
            try {
                writeSealed(pem)
            } finally {
                pem.fill(0)
            }
            legacyPlaintext.delete()
            cached = pair
            val line = PublicKeyEntry.toString(pair.public) + " " + comment
            publicFile.writeText(line + "\n")
            _revision.value++
            line
        }

    /**
     * The key pair, unsealed, for sshd; null when there is none. Seals a
     * plaintext key from an older version on the way.
     */
    @Synchronized
    fun keyPair(): KeyPair? {
        cached?.let { return it }
        val pem =
            when {
                sealedFile.isFile -> box.open(sealedFile.readBytes())
                legacyPlaintext.isFile -> legacyPlaintext.readBytes().also { migrate(it) }
                else -> return null
            }
        return try {
            SecurityUtils
                .loadKeyPairIdentities(null, NamedResource.ofName(NAME), pem.inputStream(), null)
                ?.firstOrNull()
                .also { cached = it }
        } finally {
            pem.fill(0)
        }
    }

    private fun migrate(plaintext: ByteArray) {
        writeSealed(plaintext)
        // Overwrite before deleting, so the bytes do not linger in the file's old blocks.
        legacyPlaintext.writeBytes(ByteArray(plaintext.size))
        legacyPlaintext.delete()
    }

    private fun writeSealed(pem: ByteArray) {
        val tmp = File(sshDir, NAME + SEALED_SUFFIX + ".tmp")
        tmp.writeBytes(box.seal(pem))
        check(tmp.renameTo(sealedFile)) { "could not store the SSH key" }
    }

    /** The SHA-256 fingerprint, in the form GitHub shows next to a deploy key. */
    fun fingerprint(): String? =
        publicKeyLine()?.let { line ->
            runCatching {
                KeyUtils.getFingerPrint(PublicKeyEntry.parsePublicKeyEntry(line).resolvePublicKey(null, null, null))
            }.getOrDefault("unreadable")
        }

    /**
     * The ssh config sessions run with. Keepalives because a mobile link
     * goes quiet while the server packs objects, and an idle connection is
     * dropped part way through.
     */
    fun configFile(): File {
        val desired =
            buildString {
                appendLine("Host *")
                appendLine("    ServerAliveInterval 20")
                appendLine("    ServerAliveCountMax 12")
                appendLine("    TCPKeepAlive yes")
                appendLine("    StrictHostKeyChecking yes")
            }
        val file = File(sshDir, "config")
        if (!file.isFile || file.readText() != desired) file.writeText(desired)
        return file
    }

    @Synchronized
    fun delete() {
        sealedFile.delete()
        legacyPlaintext.delete()
        publicFile.delete()
        cached = null
        _revision.value++
    }

    private companion object {
        const val NAME = "id_store"
        const val SEALED_SUFFIX = ".sealed"
        const val PUBLIC_SUFFIX = ".pub"
    }
}
