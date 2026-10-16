package me.parham1995.passwords.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.parham1995.passwords.crypto.PgpCrypto
import me.parham1995.passwords.data.git.StoreGit
import me.parham1995.passwords.store.PassEntry
import me.parham1995.passwords.store.PasswordStore
import me.parham1995.passwords.store.StoreIcons
import org.bouncycastle.openpgp.api.OpenPGPCertificate
import org.bouncycastle.openpgp.api.OpenPGPKey
import org.eclipse.jgit.lib.PersonIdent

/**
 * The store as the app sees it: entries to list, open, save and delete, and a
 * git repository behind them.
 *
 * Every change is one commit, worded the way `pass` words its own, and is
 * pushed straight away. A push that fails -- no signal, GitHub down -- leaves
 * the commit in place to go with the next sync, so nothing typed is lost.
 */
class PasswordRepository(
    val store: PasswordStore,
    private val keyRing: KeyRing,
    private val crypto: PgpCrypto,
    private val git: suspend () -> StoreGit,
    private val settings: suspend () -> Settings,
) {
    private val lock = Mutex()
    private val _items = MutableStateFlow<List<PasswordStore.Item>>(emptyList())
    val items: StateFlow<List<PasswordStore.Item>> = _items.asStateFlow()

    private val _icons = MutableStateFlow(StoreIcons.EMPTY)

    /** Folder icons from the store's `.icons.json`, which gopass ignores. */
    val icons: StateFlow<StoreIcons> = _icons.asStateFlow()

    private val iconsFile get() = java.io.File(store.root, StoreIcons.FILE)

    suspend fun refresh() =
        withContext(Dispatchers.IO) {
            _items.value = store.items()
            _icons.value = if (iconsFile.isFile) StoreIcons.parse(iconsFile.readText()) else StoreIcons.EMPTY
        }

    /** Sets or, with null, removes a folder's icon, as one commit like any other change. */
    suspend fun setFolderIcon(
        path: String,
        icon: StoreIcons.Icon?,
    ) = lock.withLock {
        withContext(Dispatchers.IO) {
            val current = if (iconsFile.isFile) StoreIcons.parse(iconsFile.readText()) else StoreIcons.EMPTY
            if (current.folder(path) == icon) return@withContext
            val updated = current.withFolder(path, icon)
            iconsFile.writeText(updated.format())
            commitAndPush(
                if (icon ==
                    null
                ) {
                    "Remove icon for $path."
                } else {
                    "Set icon for $path."
                },
                listOf(StoreIcons.FILE),
            )
        }
        refresh()
    }

    /**
     * The secret key that can open [name], so the caller knows whose
     * passphrase to ask for. Null when no imported key fits.
     */
    suspend fun keyFor(name: String): OpenPGPKey? =
        withContext(Dispatchers.IO) {
            val keys = keyRing.secretKeys()
            val ids = store.recipientsFor(name)
            keys.firstOrNull { key -> ids.any { PgpCrypto.matches(key, it) } } ?: keys.firstOrNull()
        }

    fun needsPassphrase(key: OpenPGPKey): Boolean = crypto.needsPassphrase(key)

    fun isPassphraseCorrect(
        key: OpenPGPKey,
        passphrase: CharArray,
    ): Boolean = crypto.isPassphraseCorrect(key, passphrase)

    suspend fun open(
        name: String,
        passphrase: CharArray?,
    ): PassEntry =
        withContext(Dispatchers.Default) {
            val ciphertext = withContext(Dispatchers.IO) { store.file(name).readBytes() }
            val plain = crypto.decrypt(ciphertext, keyRing.secretKeys(), passphrase)
            try {
                PassEntry.parse(String(plain, Charsets.UTF_8))
            } finally {
                plain.fill(0)
            }
        }

    /**
     * Opens [name] and follows gopass references: when its password line is
     * `gopass://other`, the password shown is `other`'s, as `gopass show`
     * does with `core.follow-references`. The entry itself is returned
     * unchanged, so editing it keeps the reference.
     */
    suspend fun openFollowing(
        name: String,
        passphrase: CharArray?,
    ): Opened {
        val entry = open(name, passphrase)
        val chain = mutableListOf(name)
        var target = entry
        while (true) {
            val next = target.reference ?: break
            if (next in chain) throw BrokenReference("reference loop: " + (chain + next).joinToString(" → "))
            if (chain.size > MAX_REFERENCE_HOPS) throw BrokenReference("too many references from $name")
            if (!store.file(next).isFile) throw BrokenReference("$name refers to $next, which does not exist")
            target = open(next, passphrase)
            chain += next
        }
        return Opened(entry, target.password, chain.drop(1))
    }

    /** An entry, and the password it resolves to after following [via]. */
    data class Opened(
        val entry: PassEntry,
        val password: String,
        val via: List<String>,
    )

    /**
     * Encrypts [entry] for everyone in the governing `.gpg-id` and commits it.
     * Renaming is a save under the new name plus a delete of the old one, in
     * the same commit.
     */
    suspend fun save(
        name: String,
        entry: PassEntry,
        previousName: String? = null,
    ) = lock.withLock {
        val recipients = recipientsFor(name)
        val ciphertext = withContext(Dispatchers.Default) { crypto.encrypt(entry.format().toByteArray(), recipients) }
        withContext(Dispatchers.IO) {
            // Through an alias, the write lands on the real file and the
            // commit names it; git will not stage a path through a symlink.
            val real = store.realName(name)
            val previousReal = previousName?.let(store::realName)
            val target = store.file(real)
            target.parentFile?.mkdirs()
            target.writeBytes(ciphertext)
            val renamed = previousReal != null && previousReal != real
            if (renamed) store.file(previousReal).delete()
            val message =
                when {
                    renamed -> "Rename $previousName to $name."
                    previousName == null -> "Add given password for $name to store."
                    else -> "Edit password for $name using passwords-droid."
                }
            val paths = listOfNotNull(relative(real), previousReal?.takeIf { renamed }?.let(::relative))
            commitAndPush(message, paths)
        }
        refresh()
    }

    suspend fun delete(name: String) =
        lock.withLock {
            withContext(Dispatchers.IO) {
                // A file alias goes on its own; the entry it points at stays.
                val own = if (store.isAlias(name)) store.ownName(name) else store.realName(name)
                java.nio.file.Files
                    .delete(java.io.File(store.root, relative(own)).toPath())
                commitAndPush("Remove $name from store.", listOf(relative(own)))
            }
            refresh()
        }

    /** Pull, then send anything waiting. */
    suspend fun sync(): SyncResult =
        lock.withLock {
            val repo = git()
            val result =
                if (!repo.isCloned) {
                    repo.clone()
                    SyncResult(cloned = true, pending = 0)
                } else {
                    repo.pull()
                    runCatching { repo.push() }
                    SyncResult(cloned = false, pending = repo.unpushed())
                }
            refresh()
            result
        }

    private suspend fun commitAndPush(
        message: String,
        paths: List<String>,
    ) {
        val repo = git()
        val config = settings()
        val author =
            PersonIdent(
                config.authorName.ifBlank { "passwords-droid" },
                config.authorEmail.ifBlank { "passwords-droid@localhost" },
            )
        repo.commit(message, author, *paths.toTypedArray())
        // Best effort: the commit is safe locally and goes with the next sync.
        runCatching { repo.push() }
    }

    /**
     * Certificates for every id in the governing `.gpg-id`, from the imported
     * keys first and gopass's exported `.public-keys` second. Refuses rather
     * than silently encrypting for fewer people than the store says.
     */
    private fun recipientsFor(name: String): List<OpenPGPCertificate> {
        val ids = store.recipientsFor(name)
        require(ids.isNotEmpty()) { "no .gpg-id governs $name" }
        val known = keyRing.all() + exportedCertificates()
        val missing = mutableListOf<String>()
        val certs =
            ids.mapNotNull { id ->
                known.firstOrNull { PgpCrypto.matches(it, id) }.also { if (it == null) missing += id }
            }
        if (missing.isNotEmpty()) throw MissingRecipients(missing)
        return certs.distinctBy { it.keyIdentifier }
    }

    private fun exportedCertificates(): List<OpenPGPCertificate> =
        store.exportedPublicKeys().values.flatMap { crypto.readKeys(it.readBytes()) }

    private fun relative(name: String): String = name + PasswordStore.EXTENSION

    private companion object {
        const val MAX_REFERENCE_HOPS = 8
    }

    data class SyncResult(
        val cloned: Boolean,
        val pending: Int,
    )
}

class BrokenReference(
    message: String,
) : Exception(message)

class MissingRecipients(
    val ids: List<String>,
) : Exception("no public key for " + ids.joinToString())
