package me.parham1995.passwords.data.git

import org.apache.sshd.common.util.io.PathUtils
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import java.io.File
import java.util.TimeZone

/**
 * Makes JGit *and* Apache sshd usable on Android.
 *
 * Both assume a desktop, and each needs telling separately.
 *
 * JGit goes looking for `${'$'}HOME/.gitconfig` and `/etc/gitconfig` on its very
 * first call; Android sets no usable `HOME` and the paths it guesses are
 * unwritable, so that call throws before any repository work begins. All three
 * config files are pointed at the app's own storage instead.
 *
 * sshd has its own, unrelated requirement, and missing it is what made every
 * SSH sync fail. `PathUtils.getUserHomeFolder()` throws outright on Android --
 * the library's own message tells you to call
 * [PathUtils.setUserHomeFolderResolver] -- and that throw happens inside a
 * static initialiser. The first attempt fails with `ExceptionInInitializerError`
 * and every attempt afterwards with `NoClassDefFoundError`, because a class
 * whose initialiser threw once stays poisoned for the life of the process. What
 * reaches JGit is "remote hung up unexpectedly", which describes none of that.
 *
 * [install] has to run before **any** JGit or sshd class is touched, which is
 * why it is idempotent and called from the transport's constructor rather than
 * left to a caller to remember.
 */
object AndroidGitEnvironment {
    @Volatile
    private var installed = false

    @Synchronized
    fun install(configDir: File) {
        if (installed) return
        configDir.mkdirs()

        // sshd turns on its BouncyCastle registrar whenever BouncyCastle is on
        // the classpath, and here it is -- PGPainless needs it. On Android the
        // name "BC" resolves to the platform's cut-down copy, which has no
        // named EC curves, so ECCurves' static initialiser dies with "No EC
        // params for nistp384" and takes KeyUtils -- every key algorithm --
        // with it. Only EdDSA and the platform JCE are wanted; PGPainless uses
        // its own BouncyCastle instance and is unaffected. Read from system
        // properties, so it has to be set before the first sshd class loads.
        System.setProperty("org.apache.sshd.registerBouncyCastle", "false")
        System.setProperty(
            "org.apache.sshd.security.registrars",
            "org.apache.sshd.common.util.security.eddsa.EdDSASecurityProviderRegistrar," +
                "org.apache.sshd.common.util.security.SunJCESecurityProviderRegistrar",
        )

        // Must come before anything loads an sshd class, including indirectly.
        PathUtils.setUserHomeFolderResolver { configDir.toPath() }

        SystemReader.setInstance(AndroidSystemReader(configDir))
        installed = true
    }

    private class AndroidSystemReader(
        private val configDir: File,
    ) : SystemReader() {
        override fun getHostname(): String = "android"

        // Deliberately blind to the process environment: JGit would otherwise
        // read HOME, XDG_CONFIG_HOME and GIT_* from whatever the OS happens to
        // set, none of which point anywhere writable here.
        override fun getenv(variable: String?): String? =
            when (variable) {
                "HOME" -> configDir.absolutePath
                else -> null
            }

        override fun getProperty(key: String?): String =
            when (key) {
                "user.home", "user.dir" -> configDir.absolutePath
                else -> key?.let { System.getProperty(it) }.orEmpty()
            }

        override fun openUserConfig(
            parent: Config?,
            fs: FS?,
        ): FileBasedConfig = FileBasedConfig(parent, File(configDir, "user.config"), fs)

        override fun openSystemConfig(
            parent: Config?,
            fs: FS?,
        ): FileBasedConfig = FileBasedConfig(parent, File(configDir, "system.config"), fs)

        override fun openJGitConfig(
            parent: Config?,
            fs: FS?,
        ): FileBasedConfig = FileBasedConfig(parent, File(configDir, "jgit.config"), fs)

        // Deprecated upstream in favour of the java.time clock, but still
        // abstract, so it has to be implemented.
        @Deprecated("JGit deprecated this in favour of getClock()")
        override fun getCurrentTime(): Long = System.currentTimeMillis()

        @Deprecated("JGit deprecated this in favour of getTimeZoneAt()")
        override fun getTimezone(whenMillis: Long): Int =
            TimeZone.getDefault().getOffset(whenMillis) / MILLIS_PER_MINUTE

        private companion object {
            const val MILLIS_PER_MINUTE = 60_000
        }
    }
}
