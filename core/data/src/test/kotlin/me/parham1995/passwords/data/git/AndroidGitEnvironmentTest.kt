package me.parham1995.passwords.data.git

import com.google.common.truth.Truth.assertThat
import org.apache.sshd.common.util.io.PathUtils
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * sshd resolves a user home folder during static initialisation and throws on
 * Android, where there is none. The throw happens inside an initialiser, so the
 * first attempt fails with ExceptionInInitializerError and every later one with
 * NoClassDefFoundError -- and JGit reports the lot as "remote hung up
 * unexpectedly", which describes none of it.
 *
 * A JVM always has a home folder, so this cannot reproduce the failure. What it
 * can do is assert the resolver is actually installed, which is the part that
 * was missing.
 */
class AndroidGitEnvironmentTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `install points sshd at a writable home folder`() {
        val dir = temp.newFolder("git")

        AndroidGitEnvironment.install(dir)

        assertThat(PathUtils.getUserHomeFolder()).isEqualTo(dir.toPath())
    }
}
