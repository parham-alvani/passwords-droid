package me.parham1995.passwords.store

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TemplatesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `renders what gopass renders`() {
        val out =
            Templates.render(
                "{{ .Content }}\nlogin: {{ .Name }}\nurl: https://{{ .Path }}\nsha: {{ sha256sum .Content }}\n",
                "web/example.org/dave",
                "pw",
            )
        assertThat(out).isEqualTo(
            "pw\nlogin: dave\nurl: https://web/example.org/dave\n" +
                "sha: 30c952fab122c3f9759f02a6d95c3758b246b4fee239957b2d4fee46e26170c4\n",
        )
    }

    @Test
    fun `actions it does not know are left as written`() {
        assertThat(Templates.render("{{ getpw \"x\" }} {{.Content}}", "a/b", "pw")).isEqualTo("{{ getpw \"x\" }} pw")
    }

    @Test
    fun `the nearest template governs, up to the root`() {
        File(tmp.root, "web").mkdirs()
        File(tmp.root, "web/.pass-template").writeText("web")
        File(tmp.root, "web/deep/.pass-template").apply { parentFile.mkdirs() }.writeText("deep")
        assertThat(Templates.find(tmp.root, "web/a/b/entry")).isEqualTo("web")
        assertThat(Templates.find(tmp.root, "web/deep/x/entry")).isEqualTo("deep")
        assertThat(Templates.find(tmp.root, "other/entry")).isNull()
    }
}
