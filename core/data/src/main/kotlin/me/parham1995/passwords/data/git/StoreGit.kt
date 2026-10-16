package me.parham1995.passwords.data.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.TransportConfigCallback
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.SshSessionFactory
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder
import java.io.File

/**
 * The store's git repository: clone it, keep it current, and send back what
 * changed on the phone.
 *
 * A full clone, not a shallow one. GitHub refuses a push from a shallow
 * clone ("shallow update not allowed"), and a password store is small --
 * a thousand entries is a few megabytes of history.
 *
 * Pulls rebase. Two devices editing different entries is the normal case, and
 * rebasing keeps the history the straight line `pass git log` shows. Two
 * devices editing the *same* entry cannot be merged at all -- the files are
 * ciphertext -- so that is reported as a conflict rather than attempted.
 */
class StoreGit(
    private val workTree: File,
    private val remoteUrl: String,
    private val branch: String,
    private val keys: SshKeyStore?,
    configDir: File,
    private val hostKeys: ServerKeyDatabase = PinnedHostKeys(),
) {
    init {
        AndroidGitEnvironment.install(configDir)
    }

    val isCloned: Boolean get() = File(workTree, Constants.DOT_GIT).isDirectory

    private val sessions: SshSessionFactory? by lazy {
        keys?.let { store ->
            SshdSessionFactoryBuilder()
                .setHomeDirectory(store.directory.parentFile)
                .setSshDirectory(store.directory)
                .setPreferredAuthentications("publickey")
                .setDefaultIdentities { listOf(store.identity.toPath()) }
                .setConfigFile { store.configFile() }
                // GitHub's published host keys and nothing else: there is no
                // one on a phone to ask whether a new key is fine.
                .setServerKeyDatabase { _, _ -> hostKeys }
                .build(null)
        }
    }

    private val transport =
        TransportConfigCallback { transport ->
            val factory = sessions
            if (transport is SshTransport && factory != null) transport.sshSessionFactory = factory
        }

    suspend fun clone() =
        withContext(Dispatchers.IO) {
            if (workTree.exists()) workTree.deleteRecursively()
            workTree.parentFile?.mkdirs()
            Git
                .cloneRepository()
                .setURI(remoteUrl)
                .setDirectory(workTree)
                .setBranch(branch)
                .setTransportConfigCallback(transport)
                .setTimeout(TIMEOUT_SECONDS)
                .call()
                .close()
        }

    /** Fetches and rebases local commits on top. Returns the new HEAD. */
    suspend fun pull(): String =
        withContext(Dispatchers.IO) {
            open().use { git ->
                val result =
                    git
                        .pull()
                        .setRemoteBranchName(branch)
                        .setRebase(true)
                        .setTransportConfigCallback(transport)
                        .setTimeout(TIMEOUT_SECONDS)
                        .call()
                if (!result.isSuccessful) {
                    git.rebase().setOperation(org.eclipse.jgit.api.RebaseCommand.Operation.ABORT).call()
                    throw SyncConflict(
                        "the same entry changed here and elsewhere; " +
                            (result.rebaseResult?.conflicts?.joinToString() ?: result.toString()),
                    )
                }
                git.repository.resolve(Constants.HEAD).name
            }
        }

    /** Stages everything under [paths] (relative, added or deleted) and commits it. */
    suspend fun commit(
        message: String,
        author: PersonIdent,
        vararg paths: String,
    ) = withContext(Dispatchers.IO) {
        open().use { git ->
            paths.forEach { path ->
                if (File(workTree, path).exists()) {
                    git.add().addFilepattern(path).call()
                } else {
                    git.rm().addFilepattern(path).call()
                }
            }
            git
                .commit()
                .setMessage(message)
                .setAuthor(author)
                .setCommitter(author)
                .setSign(false)
                .call()
        }
    }

    /** Pushes [branch]; on a rejection, pulls once and tries again. */
    suspend fun push() =
        withContext(Dispatchers.IO) {
            repeat(PUSH_ATTEMPTS) { attempt ->
                val rejection =
                    open().use { git ->
                        git
                            .push()
                            .setRemote(Constants.DEFAULT_REMOTE_NAME)
                            .add(branch)
                            .setTransportConfigCallback(transport)
                            .setTimeout(TIMEOUT_SECONDS)
                            .call()
                            .flatMap { it.remoteUpdates }
                            .firstOrNull {
                                it.status != RemoteRefUpdate.Status.OK && it.status != RemoteRefUpdate.Status.UP_TO_DATE
                            }
                    }
                if (rejection == null) return@withContext
                if (attempt == PUSH_ATTEMPTS - 1) {
                    throw SyncConflict("push refused: ${rejection.status} ${rejection.message.orEmpty()}".trim())
                }
                pull()
            }
        }

    /** Commits waiting to be pushed, so the UI can say "2 changes not sent yet". */
    suspend fun unpushed(): Int =
        withContext(Dispatchers.IO) {
            open().use { git ->
                val local = git.repository.resolve(Constants.HEAD) ?: return@withContext 0
                val remote = git.repository.resolve("refs/remotes/origin/$branch") ?: return@withContext 0
                git
                    .log()
                    .addRange(remote, local)
                    .call()
                    .count()
            }
        }

    private fun open(): Git = Git.open(workTree)

    private companion object {
        const val TIMEOUT_SECONDS = 120
        const val PUSH_ATTEMPTS = 2
    }
}

class SyncConflict(
    message: String,
) : Exception(message)
