package me.parham1995.passwords.data.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.api.RebaseResult
import org.eclipse.jgit.api.TransportConfigCallback
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.SshSessionFactory
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.TreeFilter
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
                // The private key is sealed on disk; sshd gets it from memory
                // and never looks for an identity file.
                .setDefaultIdentities { emptyList() }
                .setDefaultKeysProvider { listOfNotNull(store.keyPair()) }
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
                    val rebase = result.rebaseResult
                    // Only a rebase that stopped part way has anything to
                    // abort; asking JGit to abort when none is in progress
                    // throws and hides the real reason.
                    if (git.repository.repositoryState.isRebasing) {
                        git.rebase().setOperation(RebaseCommand.Operation.ABORT).call()
                    }
                    when (rebase?.status) {
                        RebaseResult.Status.CONFLICTS, RebaseResult.Status.STOPPED ->
                            throw SyncConflict(
                                "the same entry changed here and elsewhere; " +
                                    (rebase.conflicts?.joinToString() ?: rebase.status.toString()),
                            )
                        RebaseResult.Status.UNCOMMITTED_CHANGES ->
                            throw SyncConflict(
                                "the store has uncommitted changes: " +
                                    rebase.uncommittedChanges.orEmpty().joinToString(),
                            )
                        else -> throw SyncConflict("pull failed: ${rebase?.status ?: result}")
                    }
                }
                git.repository.resolve(Constants.HEAD).name
            }
        }

    /**
     * Puts [paths] back the way HEAD has them, in the index and on disk, and
     * removes those HEAD does not have -- undoing a change that was written
     * but could not be committed as a whole.
     */
    suspend fun restore(paths: Collection<String>) =
        withContext(Dispatchers.IO) {
            if (paths.isEmpty()) return@withContext
            open().use { git ->
                val repo = git.repository
                val head = repo.resolve(Constants.HEAD)
                val tree = head?.let { id -> RevWalk(repo).use { it.parseCommit(id).tree } }
                val (tracked, untracked) =
                    paths.partition { path ->
                        tree != null &&
                            TreeWalk.forPath(repo, path, tree) != null
                    }
                if (tracked.isNotEmpty()) {
                    git.reset().apply { tracked.forEach { addPath(it) } }.call()
                    git
                        .checkout()
                        .setStartPoint(Constants.HEAD)
                        .apply { tracked.forEach { addPath(it) } }
                        .call()
                }
                untracked.forEach { path ->
                    if (head != null) git.reset().addPath(path).call()
                    File(workTree, path).delete()
                }
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
                // A symlink is staged as itself, even when it dangles.
                if (java.nio.file.Files.exists(
                        File(workTree, path).toPath(),
                        java.nio.file.LinkOption.NOFOLLOW_LINKS,
                    )
                ) {
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

    /**
     * Pushes [branch]; on a rejection, pulls (rebasing) and tries again. The
     * desktop's gopass pushes after every change, so a rejected push is the
     * normal case, not an error.
     */
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
                    throw PushRejected("push refused: ${rejection.status} ${rejection.message.orEmpty()}".trim())
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

    /** One commit that changed an entry. */
    data class Revision(
        val commit: String,
        val time: Long,
        val author: String,
        val message: String,
    )

    /**
     * When each `.gpg` file last changed, by path, from one walk of history
     * newest first: a path's first appearance is its latest change. The walk
     * stops once every current file has been seen, so a store with years of
     * history and few stale entries is cheap.
     */
    suspend fun lastChanged(): Map<String, Long> =
        withContext(Dispatchers.IO) {
            open().use { git ->
                val repo = git.repository
                val head = repo.resolve(Constants.HEAD) ?: return@withContext emptyMap()
                val wanted = mutableSetOf<String>()
                TreeWalk(repo).use { walk ->
                    walk.addTree(RevWalk(repo).use { it.parseCommit(head).tree })
                    walk.isRecursive = true
                    while (walk.next()) if (walk.pathString.endsWith(GPG)) wanted += walk.pathString
                }
                val found = HashMap<String, Long>(wanted.size)
                RevWalk(repo).use { revWalk ->
                    revWalk.markStart(revWalk.parseCommit(head))
                    for (commit in revWalk) {
                        val time = commit.commitTime * MILLIS
                        TreeWalk(repo).use { walk ->
                            walk.isRecursive = true
                            walk.filter = TreeFilter.ANY_DIFF
                            if (commit.parentCount >
                                0
                            ) {
                                walk.addTree(revWalk.parseCommit(commit.getParent(0)).tree)
                            } else {
                                walk.addTree(EmptyTreeIterator())
                            }
                            walk.addTree(commit.tree)
                            while (walk.next()) {
                                val path = walk.pathString
                                if (path in wanted && path !in found) found[path] = time
                            }
                        }
                        if (found.size == wanted.size) break
                    }
                }
                found
            }
        }

    /** The commits that changed [path], newest first. */
    suspend fun history(
        path: String,
        limit: Int = HISTORY_LIMIT,
    ): List<Revision> =
        withContext(Dispatchers.IO) {
            open().use { git ->
                git
                    .log()
                    .addPath(path)
                    .setMaxCount(limit)
                    .call()
                    .map { Revision(it.name, it.commitTime * MILLIS, it.authorIdent.name, it.shortMessage) }
            }
        }

    /** The bytes [path] had at [commit], or null when it did not exist there. */
    suspend fun contentAt(
        commit: String,
        path: String,
    ): ByteArray? =
        withContext(Dispatchers.IO) {
            open().use { git ->
                val repo = git.repository
                val tree = RevWalk(repo).use { it.parseCommit(ObjectId.fromString(commit)).tree }
                TreeWalk.forPath(repo, path, tree)?.use { walk -> repo.open(walk.getObjectId(0)).bytes }
            }
        }

    private fun open(): Git = Git.open(workTree)

    private companion object {
        const val TIMEOUT_SECONDS = 120
        const val PUSH_ATTEMPTS = 3
        const val HISTORY_LIMIT = 100
        const val MILLIS = 1000L
        const val GPG = ".gpg"
    }
}

/** Both sides changed the same entry, or the tree is in no state to pull: a person has to look. */
class SyncConflict(
    message: String,
) : Exception(message)

/** The remote kept refusing the push even after pulling; worth trying again later. */
class PushRejected(
    message: String,
) : Exception(message)
