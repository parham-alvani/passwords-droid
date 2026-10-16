package me.parham1995.passwords.store

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StoreIconsTest {
    @Test
    fun `sets, reads and removes a folder icon`() {
        val icons = StoreIcons.EMPTY.withFolder("aut", StoreIcons.Icon("graduation-cap", "blue"))
        assertThat(icons.folder("aut")).isEqualTo(StoreIcons.Icon("graduation-cap", "blue"))
        assertThat(icons.folder("snapp")).isNull()
        assertThat(icons.withFolder("aut", null).folder("aut")).isNull()
    }

    @Test
    fun `writes sorted, indented, one line per change`() {
        val text =
            StoreIcons.EMPTY
                .withFolder("snapp", StoreIcons.Icon("car"))
                .withFolder("aut", StoreIcons.Icon("graduation-cap", "blue"))
                .format()
        assertThat(text).isEqualTo(
            """
            {
              "folders": {
                "aut": {
                  "color": "blue",
                  "icon": "graduation-cap"
                },
                "snapp": {
                  "icon": "car"
                }
              },
              "version": 1
            }

            """.trimIndent(),
        )
        assertThat(StoreIcons.parse(text).folder("aut")?.name).isEqualTo("graduation-cap")
    }

    @Test
    fun `keeps what it does not understand`() {
        val text = """{"version":1,"folders":{"aut":{"icon":"school","badge":"x"}},"entries":{"a/b":{"icon":"key"}}}"""
        val written = StoreIcons.parse(text).withFolder("aut", StoreIcons.Icon("graduation-cap")).format()
        assertThat(written).contains("\"badge\": \"x\"")
        assertThat(written).contains("\"entries\"")
        assertThat(written).contains("\"graduation-cap\"")
    }

    @Test
    fun `a broken or future file means no icons rather than a crash`() {
        assertThat(StoreIcons.parse("{not json").isEmpty).isTrue()
        assertThat(StoreIcons.parse("""{"version":99,"folders":{"aut":{"icon":"x"}}}""").folder("aut")).isNull()
    }
}
