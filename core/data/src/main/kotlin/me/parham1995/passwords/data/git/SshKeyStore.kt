package me.parham1995.passwords.data.git

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The SSH key the store is synced with.
 *
 * Generated **on the device**; the private half never leaves it. The public
 * line is registered on GitHub as a deploy key on the store's repository --
 * with write access, because unlike a notes reader this app commits.
 *
 * One store, one key. A deploy key works on exactly one repository, so a
 * second store would need a second key, and that is a problem for the day
 * there is a second store.
 */
@Singleton
class SshKeyStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        init {
            // sshd's static initialisers throw on Android unless this has run,
            // and a poisoned class stays poisoned for the life of the process.
            AndroidGitEnvironment.install(File(context.filesDir, "git"))
        }

        private val sshDir = File(context.filesDir, "ssh").apply { mkdirs() }
        private val _revision = MutableStateFlow(0)

        /** Bumped when the key is created or removed; files have nothing to subscribe to. */
        val revision: StateFlow<Int> = _revision.asStateFlow()

        val directory: File get() = sshDir
        val identity: File get() = File(sshDir, NAME)
        private val publicFile: File get() = File(sshDir, NAME + PUBLIC_SUFFIX)

        fun exists(): Boolean = identity.isFile && publicFile.isFile

        /** The line to paste into GitHub's deploy-key box. */
        fun publicKeyLine(): String? = publicFile.takeIf { it.isFile }?.readText()?.trim()

        suspend fun generate(comment: String = "passwords-droid"): String =
            withContext(Dispatchers.IO) {
                val pair = SshKeyGenerator.generate().pair
                identity.outputStream().use { out ->
                    OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(pair, comment, null, out)
                }
                // ssh refuses a world-readable identity file.
                identity.setReadable(false, false)
                identity.setReadable(true, true)
                identity.setWritable(false, false)
                identity.setWritable(true, true)

                val line = PublicKeyEntry.toString(pair.public) + " " + comment
                publicFile.writeText(line + "\n")
                _revision.value++
                line
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

        fun delete() {
            identity.delete()
            publicFile.delete()
            _revision.value++
        }

        private companion object {
            const val NAME = "id_store"
            const val PUBLIC_SUFFIX = ".pub"
        }
    }
