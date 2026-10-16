package me.parham1995.passwords.feature.keys

import me.parham1995.passwords.crypto.PgpCrypto
import me.parham1995.passwords.crypto.PgpException
import me.parham1995.passwords.data.KeyRing
import me.parham1995.passwords.data.label
import javax.inject.Inject

/**
 * Imports a key file in any of the shapes people have one in: an armored or
 * binary export, or a backup locked with a code (OpenKeychain's "Backup key",
 * `gpg --symmetric`), which needs [backupCode].
 */
class KeyImport
    @Inject
    constructor(
        private val keyRing: KeyRing,
        private val crypto: PgpCrypto,
    ) {
        sealed interface Result {
            data class Imported(
                val labels: List<String>,
                val secret: Boolean,
            ) : Result

            /** The file is a locked backup; ask for its code and call again. */
            data object NeedsBackupCode : Result

            data class Failed(
                val message: String,
            ) : Result
        }

        fun import(
            bytes: ByteArray,
            backupCode: CharArray? = null,
        ): Result {
            val keys =
                if (backupCode != null) {
                    try {
                        crypto.decryptSymmetric(bytes, backupCode)
                    } catch (_: PgpException) {
                        return Result.Failed("That backup code does not open this file")
                    } finally {
                        backupCode.fill('\u0000')
                    }
                } else {
                    bytes
                }
            if (crypto.readKeys(keys).isEmpty()) {
                return if (backupCode == null) Result.NeedsBackupCode else Result.Failed("No OpenPGP key inside")
            }
            val added = keyRing.import(keys)
            return Result.Imported(added.map { it.label() }, secret = added.any { it.isSecretKey })
        }
    }
