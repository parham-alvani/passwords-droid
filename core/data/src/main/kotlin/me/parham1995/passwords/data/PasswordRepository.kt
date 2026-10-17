package me.parham1995.passwords.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.parham1995.passwords.crypto.PgpCrypto
import me.parham1995.passwords.data.git.StoreGit
import me.parham1995.passwords.data.git.SyncConflict
import me.parham1995.passwords.store.CreateWizard
import me.parham1995.passwords.store.Documents
import me.parham1995.passwords.store.PassEntry
import me.parham1995.passwords.store.PasswordHealth
import me.parham1995.passwords.store.PasswordStore
import me.parham1995.passwords.store.Pwned
import me.parham1995.passwords.store.StoreIcons
import me.parham1995.passwords.store.Templates
import org.bouncycastle.bcpg.KeyIdentifier
import org.bouncycastle.openpgp.api.OpenPGPCertificate
import org.bouncycastle.openpgp.api.OpenPGPKey
import org.eclipse.jgit.lib.PersonIdent
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.atomic.AtomicInteger

/**
 * The store as the app sees it: entries to list, open, save and delete, and a
 * git repository behind them.
 *
 * Every change is one commit, worded the way gopass words its own (see
 * [CommitMessages]), and is pushed straight away. A push that fails -- no
 * signal, GitHub down, the desktop pushed first -- leaves the commit in place
 * and asks [scheduler] for a background sync, so nothing typed is lost.
 *
 * Every public function is main-safe: file, key and git work runs on the IO
 * or Default dispatcher.
 */
class PasswordRepository(
    val store: PasswordStore,
    private val keyRing: KeyRing,
    private val crypto: PgpCrypto,
    private val git: suspend () -> StoreGit,
    private val settings: suspend () -> Settings,
    private val scheduler: SyncScheduler = SyncScheduler.NONE,
) {
    private val lock = Mutex()
    private val _items = MutableStateFlow<List<PasswordStore.Item>>(emptyList())
    val items: StateFlow<List<PasswordStore.Item>> = _items.asStateFlow()

    private val _icons = MutableStateFlow(StoreIcons.EMPTY)

    /** Folder icons from the store's `.icons.json`, which gopass ignores. */
    val icons: StateFlow<StoreIcons> = _icons.asStateFlow()

    private val _syncStatus = MutableStateFlow(SyncStatus())

    /** Whether changes are waiting to be pushed, and why the last attempt failed. */
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _loginMethods = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * The `login-method` of every passwordless entry (see
     * [PassEntry.isPasswordless]) decrypted or saved since the app started,
     * by name. Lets autofill label a suggestion "me@x.com · Google" and the
     * list show a hint without decrypting anything more. Kept in memory
     * only; nothing read from an entry is written to disk.
     */
    val loginMethods: StateFlow<Map<String, String>> = _loginMethods.asStateFlow()

    private fun learn(
        name: String,
        entry: PassEntry,
    ) {
        val method = entry.loginMethod?.takeIf { entry.isPasswordless }
        if (_loginMethods.value[name] == method) return
        _loginMethods.update { if (method == null) it - name else it + (name to method) }
    }

    private fun forget(name: String) = _loginMethods.update { it - name }

    private val iconsFile get() = File(store.root, StoreIcons.FILE)

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
            commitAndPush(CommitMessages.icon(path, removed = icon == null), listOf(StoreIcons.FILE))
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

    suspend fun needsPassphrase(key: OpenPGPKey): Boolean =
        withContext(Dispatchers.Default) { crypto.needsPassphrase(key) }

    suspend fun isPassphraseCorrect(
        key: OpenPGPKey,
        passphrase: CharArray,
    ): Boolean = withContext(Dispatchers.Default) { crypto.isPassphraseCorrect(key, passphrase) }

    suspend fun open(
        name: String,
        passphrase: CharArray?,
    ): PassEntry = PassEntry.parse(openText(name, passphrase)).also { learn(name, it) }

    /** The entry's decrypted text, exactly as stored. */
    suspend fun openText(
        name: String,
        passphrase: CharArray?,
    ): String =
        withContext(Dispatchers.Default) {
            val ciphertext = withContext(Dispatchers.IO) { store.file(name).readBytes() }
            val plain = crypto.decrypt(ciphertext, keyRing.secretKeys(), passphrase)
            try {
                String(plain, Charsets.UTF_8)
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
        val text = openText(name, passphrase)
        val entry = PassEntry.parse(text)
        learn(name, entry)
        val document = Documents.of(name, text)
        val chain = mutableListOf(name)
        var target = entry
        while (true) {
            val next = target.reference ?: break
            if (next in chain) throw BrokenReference("reference loop: " + (chain + next).joinToString(" → "))
            if (chain.size > MAX_REFERENCE_HOPS) throw BrokenReference("too many references from $name")
            if (!exists(next)) throw BrokenReference("$name refers to $next, which does not exist")
            target = open(next, passphrase)
            chain += next
        }
        return Opened(entry, target.password, chain.drop(1), document, text)
    }

    /** Whether an entry called [name] exists. */
    suspend fun exists(name: String): Boolean = withContext(Dispatchers.IO) { store.file(name).isFile }

    /** An entry, and the password it resolves to after following [via]. */
    data class Opened(
        val entry: PassEntry,
        val password: String,
        val via: List<String>,
        /** Set when the entry is a file (a certificate, a VPN profile) rather than a login. */
        val document: Documents.Document? = null,
        /** The decrypted text exactly as stored, for sharing the whole entry. */
        val text: String = entry.format(),
    )

    /**
     * Encrypts [entry] for everyone in the governing `.gpg-id` and commits it.
     * Renaming is a save under the new name plus a delete of the old one, in
     * the same commit. Renaming an alias moves the alias, never its target.
     *
     * Without a [message], the commit is worded from what happened: a move,
     * an edit, or a new entry -- [generated] says its password was generated
     * rather than typed, as `gopass generate` and `gopass insert` differ.
     */
    suspend fun save(
        name: String,
        entry: PassEntry,
        previousName: String? = null,
        message: String? = null,
        generated: Boolean = false,
    ) = lock.withLock {
        writeText(name, entry.format(), previousName, message, generated)
    }

    /**
     * Stores [text] verbatim as [name]: what [save] does after formatting an
     * entry, and how a file is kept -- as itself when it is text, the `pass
     * insert -m` way, or as a gopass attachment when it is not.
     */
    suspend fun saveText(
        name: String,
        text: String,
        message: String? = null,
    ) = lock.withLock { writeText(name, text, null, message, generated = false) }

    /**
     * The `gopass create` wizard templates: the store's `.yml` files in `.gopass/create`,
     * or gopass's defaults when it has none that read.
     */
    suspend fun createTemplates(): List<CreateWizard.Template> =
        withContext(Dispatchers.IO) { CreateWizard.load(store.root) }

    /** New entries in a folder with a `.pass-template` are shaped by it, as with `gopass generate`. */
    suspend fun templateFor(name: String): String? = withContext(Dispatchers.IO) { Templates.find(store.root, name) }

    private suspend fun writeText(
        name: String,
        text: String,
        previousName: String?,
        message: String?,
        generated: Boolean,
    ) {
        val recipients = recipientsFor(name)
        val ciphertext = withContext(Dispatchers.Default) { crypto.encrypt(text.toByteArray(), recipients) }
        withContext(Dispatchers.IO) {
            val moved = previousName != null && previousName != name
            if (moved && store.isAlias(previousName)) {
                moveAlias(previousName, name, ciphertext, message)
                return@withContext
            }
            // Through an alias, the write lands on the real file and the
            // commit names it; git will not stage a path through a symlink.
            val real = store.realName(name)
            val previousReal = previousName?.let(store::realName)
            val target = store.file(real)
            val existed = target.isFile
            target.parentFile?.mkdirs()
            target.writeBytes(ciphertext)
            val renamed = previousReal != null && previousReal != real
            if (renamed) store.file(previousReal).delete()
            val commitMessage =
                message ?: when {
                    renamed -> CommitMessages.move(previousName, name)
                    previousName != null || existed -> CommitMessages.edit(name)
                    generated -> CommitMessages.generate(name)
                    else -> CommitMessages.insert(name)
                }
            val paths = listOfNotNull(relative(real), previousReal?.takeIf { renamed }?.let(::relative))
            commitAndPush(commitMessage, paths)
        }
        previousName?.takeIf { it != name }?.let(::forget)
        learn(name, PassEntry.parse(text))
        refresh()
    }

    /**
     * Renames a file alias: the symlink moves, pointing at the same entry,
     * and the entry itself gets [ciphertext] (the edit made with the rename).
     * Deleting through the alias's resolved name would remove the entry and
     * leave the alias dangling.
     */
    private suspend fun moveAlias(
        from: String,
        to: String,
        ciphertext: ByteArray,
        message: String?,
    ) {
        val real = store.realName(from)
        val oldOwn = store.ownName(from)
        val oldLink = File(store.root, relative(oldOwn))
        val newOwn = store.ownName(to)
        val newLink = File(store.root, relative(newOwn))
        require(!Files.exists(newLink.toPath(), LinkOption.NOFOLLOW_LINKS)) { "$to already exists" }
        val target = store.file(real).canonicalFile
        newLink.parentFile?.mkdirs()
        val linkDir = newLink.parentFile!!.canonicalFile.toPath()
        Files.createSymbolicLink(newLink.toPath(), linkDir.relativize(target.toPath()))
        Files.delete(oldLink.toPath())
        target.writeBytes(ciphertext)
        commitAndPush(
            message ?: CommitMessages.move(from, to),
            listOf(relative(newOwn), relative(oldOwn), relative(real)),
        )
    }

    /** The ids a folder is encrypted for, and the folder whose `.gpg-id` says so ("" for the root). */
    suspend fun recipients(folder: String): Pair<String, List<String>> =
        withContext(Dispatchers.IO) { governing(folder) }

    private fun governing(folder: String): Pair<String, List<String>> {
        var dir = folder.trim('/')
        while (true) {
            val ids = File(store.root, gpgIdPath(dir))
            if (ids.isFile) return dir to PasswordStore.parseGpgId(ids.readText())
            if (dir.isEmpty()) return "" to emptyList()
            dir = dir.substringBeforeLast('/', "")
        }
    }

    /** Every certificate the app knows: imported keys and gopass's exported `.public-keys`. */
    suspend fun knownCertificates(): List<OpenPGPCertificate> = withContext(Dispatchers.IO) { known() }

    private fun known(): List<OpenPGPCertificate> =
        (keyRing.all() + exportedCertificates()).distinctBy { it.keyIdentifier }

    /**
     * The entries whose recipients [folder]'s `.gpg-id` decides: those under
     * it, but not under a subfolder with a `.gpg-id` of its own. Aliases are
     * left out; they are the same files.
     */
    suspend fun governedEntries(folder: String): List<String> =
        withContext(Dispatchers.IO) {
            val dir = folder.trim('/')
            items.value
                .filter { it.aliasOf == null }
                .map { it.name }
                .filter { name ->
                    (dir.isEmpty() || name.startsWith("$dir/")) &&
                        governing(name.substringBeforeLast('/', "")).first == dir
                }
        }

    /**
     * Changes who [folder] is encrypted for, the way `gopass recipients add`
     * and `pass init -p` do: writes its `.gpg-id`, re-encrypts every entry it
     * governs (not those under a subfolder with its own `.gpg-id`), exports
     * any new recipient's public key to `.public-keys` as gopass does, and
     * commits it all as one change.
     *
     * All or nothing: every entry is decrypted and re-encrypted in memory
     * first, and only then written. If a write or the commit fails, the files
     * already written are put back from git, so the tree is never left half
     * re-encrypted.
     *
     * The `.gpg-id` keeps its comments and the order of the ids that stay;
     * new recipients are added as full fingerprints, as gopass writes them.
     *
     * Refuses to leave out every key this phone can decrypt with -- that would
     * lock the phone out of the folder it is re-encrypting.
     */
    suspend fun setRecipients(
        folder: String,
        ids: List<String>,
        passphrase: CharArray?,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ) = lock.withLock {
        val dir = folder.trim('/')
        require(ids.isNotEmpty()) { "a folder needs at least one recipient" }
        val known = withContext(Dispatchers.IO) { known() }
        val secretKeys = withContext(Dispatchers.IO) { keyRing.secretKeys() }
        val certs = resolveAll(ids, known)
        val wanted = ids.zip(certs).toMap()
        require(secretKeys.any { key -> certs.any { it.keyIdentifier == key.keyIdentifier } }) {
            "none of these recipients is a key this phone holds; it could not read the folder afterwards"
        }
        val governed = governedEntries(dir)
        val distinctCerts = certs.distinctBy { it.keyIdentifier }

        // Everything in memory first; nothing on disk changes until all of it worked.
        val ciphers = LinkedHashMap<String, ByteArray>()
        withContext(Dispatchers.Default) {
            governed.forEachIndexed { index, name ->
                val bytes = withContext(Dispatchers.IO) { store.file(name).readBytes() }
                val plain = crypto.decrypt(bytes, secretKeys, passphrase)
                ciphers[name] = crypto.encrypt(plain, distinctCerts).also { plain.fill(0) }
                onProgress(index + 1, governed.size)
            }
        }

        withContext(Dispatchers.IO) {
            val gpgId = gpgIdPath(dir)
            val gpgIdFile = File(store.root, gpgId)
            val before = governing(dir).second
            val text = updatedGpgId(gpgIdFile.takeIf { it.isFile }?.readText(), wanted, known)
            val exportedDir = File(store.root, PasswordStore.PUBLIC_KEYS)
            val alreadyExported = exportedCertificates().map { it.keyIdentifier }.toSet()
            val toExport =
                if (exportedDir.isDirectory) {
                    distinctCerts.filter {
                        it.keyIdentifier !in alreadyExported
                    }
                } else {
                    emptyList()
                }

            val written = mutableListOf<String>()
            try {
                ciphers.forEach { (name, cipher) ->
                    written += relative(name)
                    store.file(name).writeBytes(cipher)
                }
                written += gpgId
                gpgIdFile.parentFile?.mkdirs()
                gpgIdFile.writeText(text)
                toExport.forEach { cert ->
                    val path = "${PasswordStore.PUBLIC_KEYS}/${PgpCrypto.fingerprint(cert)}"
                    written += path
                    File(
                        store.root,
                        path,
                    ).writeText(((cert as? OpenPGPKey)?.toCertificate() ?: cert).toAsciiArmoredString())
                }
                commit(recipientsMessage(dir, before, distinctCerts, known, toExport.isNotEmpty()), written)
            } catch (e: Exception) {
                runCatching { git().restore(written) }.onFailure { e.addSuppressed(it) }
                throw e
            }
            pushOrSchedule()
        }
        refresh()
    }

    /**
     * The new `.gpg-id`: every comment and blank line of [old] kept, the ids
     * that stay kept as written and in their order, and new recipients
     * appended as full upper-case fingerprints.
     */
    private fun updatedGpgId(
        old: String?,
        wanted: Map<String, OpenPGPCertificate>,
        known: List<OpenPGPCertificate>,
    ): String {
        val wantedKeys = wanted.values.map { it.keyIdentifier }.toSet()
        val covered = mutableSetOf<KeyIdentifier>()
        val kept = mutableListOf<String>()
        old
            ?.lines()
            ?.dropLastWhile { it.isBlank() }
            ?.forEach { line ->
                val id = line.substringBefore('#').trim()
                if (id.isEmpty()) {
                    kept += line
                    return@forEach
                }
                val cert = wanted[id] ?: (PgpCrypto.resolve(known, id) as? PgpCrypto.Recipient.Found)?.certificate
                if (id in wanted || cert != null && cert.keyIdentifier in wantedKeys) {
                    kept += line
                    cert?.let { covered += it.keyIdentifier }
                }
            }
        val added =
            wanted.values
                .distinctBy { it.keyIdentifier }
                .filter { it.keyIdentifier !in covered }
                .map { PgpCrypto.fingerprint(it) }
        return (kept + added).joinToString("\n", postfix = "\n")
    }

    private fun recipientsMessage(
        dir: String,
        before: List<String>,
        after: List<OpenPGPCertificate>,
        known: List<OpenPGPCertificate>,
        exported: Boolean,
    ): String {
        val beforeCerts =
            before.associateWith {
                (
                    PgpCrypto.resolve(
                        known,
                        it,
                    ) as? PgpCrypto.Recipient.Found
                )?.certificate
            }
        val beforeKeys = beforeCerts.values.mapNotNull { it?.keyIdentifier }.toSet()
        val afterKeys = after.map { it.keyIdentifier }.toSet()
        val lines =
            after.filter { it.keyIdentifier !in beforeKeys }.map {
                CommitMessages.addRecipient(
                    PgpCrypto.fingerprint(it),
                )
            } +
                beforeCerts
                    .filter { (_, cert) -> cert == null || cert.keyIdentifier !in afterKeys }
                    .map { (id, cert) -> CommitMessages.removeRecipient(cert?.let(PgpCrypto::fingerprint) ?: id) } +
                listOfNotNull(CommitMessages.EXPORT_KEYS.takeIf { exported })
        return lines.ifEmpty { listOf(CommitMessages.reencrypt(dir)) }.joinToString("\n")
    }

    suspend fun delete(name: String) =
        lock.withLock {
            withContext(Dispatchers.IO) {
                // A file alias goes on its own; the entry it points at stays.
                val own = if (store.isAlias(name)) store.ownName(name) else store.realName(name)
                Files.delete(File(store.root, relative(own)).toPath())
                commitAndPush(CommitMessages.remove(name), listOf(relative(own)))
            }
            forget(name)
            refresh()
        }

    /**
     * Every login's password, for the health check: decrypted a few at a time
     * off the main thread, with documents, gopass references and passwordless
     * (SSO, social login) accounts left out because they are not passwords
     * to score. [onProgress] reports how many
     * of how many are done. The caller must not keep the result.
     */
    suspend fun healthCandidates(
        passphrase: CharArray?,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<PasswordHealth.Candidate> =
        withContext(Dispatchers.Default) {
            val names = items.value.filter { it.aliasOf == null }.map { it.name }
            val changed = runCatching { git().lastChanged() }.getOrDefault(emptyMap())
            val done = AtomicInteger()
            val gate = Semaphore(DECRYPT_PARALLELISM)
            names
                .map { name ->
                    async {
                        gate.withPermit {
                            val result =
                                runCatching {
                                    val bytes = withContext(Dispatchers.IO) { store.file(name).readBytes() }
                                    val plain = crypto.decrypt(bytes, keyRing.secretKeys(), passphrase)
                                    val text = String(plain, Charsets.UTF_8).also { plain.fill(0) }
                                    val entry = PassEntry.parse(text)
                                    learn(name, entry)
                                    if (Documents.of(name, text) != null ||
                                        entry.reference != null ||
                                        entry.isPasswordless
                                    ) {
                                        null
                                    } else {
                                        PasswordHealth.Candidate(name, entry.password, changed[relative(name)])
                                    }
                                }.getOrNull()
                            onProgress(done.incrementAndGet(), names.size)
                            result
                        }
                    }
                }.awaitAll()
                .filterNotNull()
        }

    /**
     * How many breaches each password appears in, by entry name, from Have I
     * Been Pwned's range API: one request per distinct five-character hash
     * prefix, and only the prefix leaves the phone.
     */
    suspend fun pwned(
        candidates: List<PasswordHealth.Candidate>,
        client: PwnedClient = PwnedClient.HIBP,
    ): Map<String, Int> =
        withContext(Dispatchers.IO) {
            // An empty password is no account's password: never ask about it.
            val hashes =
                candidates.filter { it.password.isNotBlank() }.associate { it.name to Pwned.sha1(it.password) }
            val ranges =
                hashes.values
                    .map { it.take(Pwned.PREFIX_LENGTH) }
                    .distinct()
                    .associateWith { prefix -> client.range(prefix) }
            hashes
                .mapValues { (_, hash) -> Pwned.count(hash, ranges.getValue(hash.take(Pwned.PREFIX_LENGTH))) }
                .filterValues { it > 0 }
        }

    /** The commits that changed [name], newest first. */
    suspend fun history(name: String): List<StoreGit.Revision> =
        withContext(Dispatchers.IO) { git().history(relative(store.realName(name))) }

    /** [name] as it was at [commit], decrypted; null when it did not exist there. */
    suspend fun openAt(
        name: String,
        commit: String,
        passphrase: CharArray?,
    ): PassEntry? {
        val bytes =
            withContext(Dispatchers.IO) { git().contentAt(commit, relative(store.realName(name))) } ?: return null
        return withContext(Dispatchers.Default) {
            val plain = crypto.decrypt(bytes, keyRing.secretKeys(), passphrase)
            try {
                PassEntry.parse(String(plain, Charsets.UTF_8))
            } finally {
                plain.fill(0)
            }
        }
    }

    /**
     * Puts [name] back the way it was at [revision], as a new commit on top --
     * history is never rewritten, so the restore itself can be undone.
     * Re-encrypted for today's recipients, not the ones it had then.
     */
    suspend fun restore(
        name: String,
        revision: StoreGit.Revision,
        passphrase: CharArray?,
    ) {
        val entry = openAt(name, revision.commit, passphrase) ?: error("$name did not exist in that version")
        val date =
            java.time.Instant
                .ofEpochMilli(revision.time)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate()
        save(name, entry, previousName = name, message = CommitMessages.restore(name, date.toString()))
    }

    /**
     * Pull, then send anything waiting. A pull that fails throws; a push that
     * fails does not -- the commits stay and a background sync is scheduled.
     */
    suspend fun sync(): SyncResult =
        lock.withLock {
            val result =
                withContext(Dispatchers.IO) {
                    val repo = git()
                    if (!repo.isCloned) {
                        repo.clone()
                        SyncResult(cloned = true, pending = 0)
                    } else {
                        repo.pull()
                        pushOrSchedule()
                        SyncResult(cloned = false, pending = runCatching { repo.unpushed() }.getOrDefault(0))
                    }
                }
            refresh()
            result
        }

    /**
     * What the background sync runs: pull (rebasing on whatever the desktop
     * pushed meanwhile) and push. Never throws, except to cancel; the
     * outcome says whether trying again later could help.
     */
    suspend fun pushPending(): PushOutcome {
        val outcome =
            lock.withLock {
                withContext(Dispatchers.IO) {
                    try {
                        val repo = git()
                        if (!repo.isCloned) return@withContext PushOutcome.DONE
                        repo.pull()
                        repo.push()
                        _syncStatus.value = SyncStatus(pending = repo.unpushed())
                        PushOutcome.DONE
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: SyncConflict) {
                        _syncStatus.value = _syncStatus.value.copy(error = e.message, conflict = true)
                        PushOutcome.CONFLICT
                    } catch (e: Exception) {
                        _syncStatus.value = _syncStatus.value.copy(error = e.message ?: e::class.simpleName)
                        PushOutcome.RETRY
                    }
                }
            }
        runCatching { refresh() }
        return outcome
    }

    private suspend fun commitAndPush(
        message: String,
        paths: List<String>,
    ) {
        commit(message, paths)
        pushOrSchedule()
    }

    private suspend fun commit(
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
    }

    /**
     * Best effort: the commit is safe locally. A failed push -- offline, or
     * refused because the desktop pushed first -- is handed to the
     * background sync rather than reported as a failed save.
     */
    private suspend fun pushOrSchedule() {
        val repo = git()
        try {
            repo.push()
            _syncStatus.value = SyncStatus()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val pending = runCatching { repo.unpushed() }.getOrDefault(0)
            _syncStatus.value =
                SyncStatus(pending = pending, error = e.message ?: e::class.simpleName, conflict = e is SyncConflict)
            runCatching { scheduler.schedule() }
        }
    }

    /**
     * Certificates for every id in the governing `.gpg-id`, from the imported
     * keys first and gopass's exported `.public-keys` second. Refuses rather
     * than silently encrypting for fewer people than the store says.
     */
    private suspend fun recipientsFor(name: String): List<OpenPGPCertificate> =
        withContext(Dispatchers.IO) {
            val ids = store.recipientsFor(name)
            require(ids.isNotEmpty()) { "no .gpg-id governs $name" }
            resolveAll(ids, known()).distinctBy { it.keyIdentifier }
        }

    /** One usable certificate per id, or [MissingRecipients] naming every id that has none. */
    private fun resolveAll(
        ids: List<String>,
        known: List<OpenPGPCertificate>,
    ): List<OpenPGPCertificate> {
        val missing = mutableListOf<String>()
        val reasons = mutableMapOf<String, String>()
        val certs =
            ids.mapNotNull { id ->
                when (val resolved = PgpCrypto.resolve(known, id)) {
                    is PgpCrypto.Recipient.Found -> resolved.certificate
                    is PgpCrypto.Recipient.Unusable -> {
                        missing += id
                        reasons[id] = "${resolved.certificate.label()} is ${resolved.reason}"
                        null
                    }
                    PgpCrypto.Recipient.Missing -> {
                        missing += id
                        null
                    }
                }
            }
        if (missing.isNotEmpty()) throw MissingRecipients(missing, reasons)
        return certs
    }

    private fun exportedCertificates(): List<OpenPGPCertificate> =
        store.exportedPublicKeys().values.flatMap { crypto.readKeys(it.readBytes()) }

    private fun relative(name: String): String = name + PasswordStore.EXTENSION

    private fun gpgIdPath(dir: String): String =
        if (dir.isEmpty()) PasswordStore.GPG_ID else "$dir/${PasswordStore.GPG_ID}"

    private companion object {
        const val MAX_REFERENCE_HOPS = 8
        const val DECRYPT_PARALLELISM = 4
    }

    data class SyncResult(
        val cloned: Boolean,
        val pending: Int,
    )

    /** Where the store stands with its remote. */
    data class SyncStatus(
        /** Commits made here and not pushed yet. */
        val pending: Int = 0,
        /** Why the last push or background sync failed; null when it worked. */
        val error: String? = null,
        /** The failure needs a person: the same entry changed on both sides. */
        val conflict: Boolean = false,
    )

    /** What [pushPending] achieved, for the background worker to act on. */
    enum class PushOutcome {
        DONE,

        /** Offline, or refused again: worth another try later. */
        RETRY,

        /** Trying again will not help until someone resolves it. */
        CONFLICT,
    }
}

/** Asks for a background pull-and-push; the app backs it with WorkManager. */
fun interface SyncScheduler {
    fun schedule()

    companion object {
        val NONE = SyncScheduler { }
    }
}

class BrokenReference(
    message: String,
) : Exception(message)

/**
 * Some ids in a `.gpg-id` have no key this phone can encrypt for: none at
 * all, or one that is expired or revoked, as [reasons] says per id.
 */
class MissingRecipients(
    val ids: List<String>,
    val reasons: Map<String, String> = emptyMap(),
) : Exception(
        ids.joinToString(prefix = "cannot encrypt for ") { id ->
            reasons[id]?.let { "$id ($it)" }
                ?: "$id (no public key)"
        },
    )
