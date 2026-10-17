package me.parham1995.passwords.data

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import me.parham1995.passwords.crypto.PgpCrypto
import me.parham1995.passwords.data.git.SshKeyStore
import me.parham1995.passwords.data.git.StoreGit
import me.parham1995.passwords.data.security.KeystoreBox
import me.parham1995.passwords.data.sync.SyncWorker
import me.parham1995.passwords.store.PasswordStore
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun crypto(): PgpCrypto = PgpCrypto()

    @Provides
    @Singleton
    fun keyRing(
        @ApplicationContext context: Context,
        crypto: PgpCrypto,
    ): KeyRing = KeyRing(File(context.filesDir, "keys/keyring.sealed"), KeystoreBox.atRest(), crypto)

    @Provides
    @Singleton
    fun biometricBox(): KeystoreBox = KeystoreBox.biometric()

    @Provides
    @Singleton
    fun passphraseCache(
        @ApplicationContext context: Context,
    ): PassphraseCache = PassphraseCache(context.getSharedPreferences("sealed-passphrases", Context.MODE_PRIVATE))

    /** The deploy key, sealed by the same at-rest key as the OpenPGP keys. */
    @Provides
    @Singleton
    fun sshKeys(
        @ApplicationContext context: Context,
    ): SshKeyStore = SshKeyStore(File(context.filesDir, "ssh"), KeystoreBox.atRest(), File(context.filesDir, "git"))

    @Provides
    @Singleton
    fun repository(
        @ApplicationContext context: Context,
        keyRing: KeyRing,
        crypto: PgpCrypto,
        keys: SshKeyStore,
        settings: SettingsStore,
    ): PasswordRepository {
        val root = File(context.filesDir, "store")
        val configDir = File(context.filesDir, "git")
        return PasswordRepository(
            store = PasswordStore(root),
            keyRing = keyRing,
            crypto = crypto,
            git = {
                val current = settings.current()
                StoreGit(root, current.remoteUrl, current.branch, keys, configDir)
            },
            settings = settings::current,
            scheduler = SyncWorker.scheduler(context),
        )
    }
}
