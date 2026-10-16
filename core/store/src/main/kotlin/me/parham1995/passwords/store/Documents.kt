package me.parham1995.passwords.store

import java.util.Base64

/**
 * Entries that are files rather than logins: a certificate, a VPN profile, a
 * key. A store holds them two ways:
 *
 * - **as text**, the `pass insert -m` way: the whole file is the entry, so its
 *   first line (`-----BEGIN CERTIFICATE-----`, `client`) is not a password;
 * - **as gopass attachments** (`gopass fscopy`): an empty first line,
 *   `Content-Disposition` and `Content-Transfer-Encoding: Base64` headers, and
 *   the file base64-encoded after them.
 */
object Documents {
    data class Document(
        /** What to call the file when saving or sharing it. */
        val fileName: String,
        val bytes: ByteArray,
        /** True when it came from a gopass attachment rather than plain text. */
        val attachment: Boolean,
    ) {
        val isText: Boolean get() =
            !attachment ||
                runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)) }.isSuccess

        override fun equals(other: Any?): Boolean =
            other is Document &&
                fileName == other.fileName &&
                attachment == other.attachment &&
                bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = 31 * fileName.hashCode() + bytes.contentHashCode()
    }

    private val EXTENSIONS =
        setOf(
            "pem",
            "crt",
            "cer",
            "key",
            "pub",
            "p12",
            "pfx",
            "ovpn",
            "conf",
            "cfg",
            "json",
            "yaml",
            "yml",
            "txt",
            "env",
            "ini",
            "toml",
            "xml",
            "asc",
            "kdbx",
            "zip",
            "pdf",
            "png",
            "jpg",
            "jpeg",
            "kubeconfig",
        )
    private val FILENAME = Regex("""filename\*?="?([^";]+)"?""")

    /** The file this entry is, or null for an ordinary login. */
    fun of(
        name: String,
        text: String,
    ): Document? {
        val entry = PassEntry.parse(text)
        val encoding = entry.fields.firstOrNull { it.key.equals("Content-Transfer-Encoding", ignoreCase = true) }?.value
        if (encoding.equals("base64", ignoreCase = true)) {
            val disposition =
                entry.fields
                    .firstOrNull {
                        it.key.equals(
                            "Content-Disposition",
                            ignoreCase = true,
                        )
                    }?.value
                    .orEmpty()
            val fileName = FILENAME.find(disposition)?.groupValues?.get(1) ?: name.substringAfterLast('/')
            val bytes = runCatching { Base64.getMimeDecoder().decode(entry.notes.trim()) }.getOrNull() ?: return null
            return Document(fileName, bytes, attachment = true)
        }
        val leaf = name.substringAfterLast('/')
        val looksLikeFile = leaf.substringAfterLast('.', "").lowercase() in EXTENSIONS && '.' in leaf
        val pem = entry.password.trimStart().startsWith("-----BEGIN ")
        if (!looksLikeFile && !pem) return null
        val fileName = if (looksLikeFile) leaf else "$leaf.pem"
        return Document(fileName, text.toByteArray(Charsets.UTF_8), attachment = false)
    }

    /** A file as a new gopass attachment, readable by `gopass fscopy` and `gopass cat`. */
    fun attachment(
        fileName: String,
        bytes: ByteArray,
    ): String =
        buildString {
            append('\n')
            append("Content-Disposition: attachment; filename=\"").append(fileName.replace("\"", "")).append("\"\n")
            append("Content-Transfer-Encoding: Base64\n")
            append(Base64.getEncoder().encodeToString(bytes)).append('\n')
        }
}
