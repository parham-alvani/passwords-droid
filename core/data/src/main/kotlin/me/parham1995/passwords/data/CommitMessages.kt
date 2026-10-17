package me.parham1995.passwords.data

/**
 * Commit messages, worded the way gopass words its own -- the store's history
 * is mostly gopass's, so `gopass history` and `git log` read the same
 * whichever device made a change. Every write is a gopass "Set", which
 * commits as `Save secret <name>: <reason>`.
 */
object CommitMessages {
    /** `gopass insert`: a password the user typed. */
    fun insert(name: String): String = save(name, "Insert user supplied password")

    /** `gopass generate`. */
    fun generate(name: String): String = save(name, "Generate Password")

    /** `gopass edit`, which says `Edited with <editor>`. */
    fun edit(name: String): String = save(name, "Edit with passwords-droid")

    /** `gopass create`, the wizard. */
    fun create(name: String): String = save(name, "Create new entry")

    /** `gopass move`: the commit names the new place. */
    fun move(
        from: String,
        to: String,
    ): String = save(to, "Move from $from to $to")

    /** `gopass rm`. */
    fun remove(name: String): String = "Remove $name from store."

    /** A file stored as an entry, as `gopass fscopy` stores one. */
    fun file(
        name: String,
        fileName: String,
    ): String = save(name, "Copy file $fileName")

    /** An old version put back on top of history. */
    fun restore(
        name: String,
        date: String,
    ): String = save(name, "Restore version of $date")

    /** The folder icons file, which gopass ignores but stores like any other. */
    fun icon(
        folder: String,
        removed: Boolean,
    ): String = save(folder, if (removed) "Remove folder icon" else "Set folder icon")

    /** `gopass recipients add`. */
    fun addRecipient(fingerprint: String): String = "Add Recipient $fingerprint"

    /** `gopass recipients remove`. */
    fun removeRecipient(fingerprint: String): String = "Remove Recipient $fingerprint"

    /** Re-encrypting a folder when its recipients only moved around. */
    fun reencrypt(folder: String): String = "Re-encrypt ${folder.ifEmpty { "store" }} for its recipients"

    /** gopass's commit for `.public-keys` when `core.exportkeys` is on. */
    const val EXPORT_KEYS: String = "Update exported Public Keys"

    private fun save(
        name: String,
        reason: String,
    ): String = "Save secret $name: $reason"
}
