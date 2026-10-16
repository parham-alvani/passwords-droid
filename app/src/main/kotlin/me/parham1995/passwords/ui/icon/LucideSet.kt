package me.parham1995.passwords.ui.icon

import android.content.Context
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.PathParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The Lucide icon set, as one asset.
 *
 * The same asset Daftar ships, built by the same `tools/lucide.py`. Lucide
 * publishes 2,112 separate SVG documents; any of them can be picked for a
 * folder, and the choice arrives from git on other devices, so the whole set is
 * flattened into one file of path strings: 330KB of assets, 85KB in the APK,
 * one parse at startup.
 *
 * Path data is kept as text and turned into a [Path] the first time an icon is
 * actually drawn, because a store shows a few dozen distinct icons and parsing
 * all two thousand up front would be work for nothing.
 */
class LucideSet private constructor(
    private val sources: Map<String, Source>,
) {
    internal class Source(
        val stroke: String,
        /** Sub-paths that are filled as well as stroked. Eleven icons have them. */
        val fill: String?,
    )

    /** An icon's geometry, in Lucide's 24x24 coordinate space. */
    class Glyph(
        val stroke: Path?,
        val fill: Path?,
    )

    private val parsed = HashMap<String, Glyph?>()

    /** Raw path data, for checks that cannot build an Android [Path]. */
    internal fun sourceOf(name: String): Pair<String, String?>? = sources[name]?.let { it.stroke to it.fill }

    /** Every icon name, for the picker. */
    val names: Set<String> get() = sources.keys

    val size: Int get() = sources.size

    operator fun contains(name: String): Boolean = name in sources

    /** The glyph named [name], or null when the set does not have it. */
    @Synchronized
    fun glyph(name: String): Glyph? =
        parsed.getOrPut(name) {
            val source = sources[name] ?: return@getOrPut null
            Glyph(
                stroke = source.stroke.takeIf { it.isNotEmpty() }?.let(::toPath),
                fill = source.fill?.let(::toPath),
            )
        }

    private fun toPath(data: String): Path = PathParser().parsePathString(data).toPath()

    companion object {
        val EMPTY = LucideSet(emptyMap())

        private const val ASSET = "lucide.json"
        private val json = Json { ignoreUnknownKeys = true }

        suspend fun load(context: Context): LucideSet =
            withContext(Dispatchers.IO) {
                runCatching {
                    parse(context.assets.open(ASSET).use { it.readBytes().decodeToString() })
                }.getOrDefault(EMPTY)
            }

        internal fun parse(text: String): LucideSet {
            val root = json.parseToJsonElement(text) as? JsonObject ?: return EMPTY
            val icons = root["icons"] as? JsonObject ?: return EMPTY
            val out = HashMap<String, Source>(icons.size)
            for ((name, value) in icons) {
                when (value) {
                    // The common case: one concatenated stroke path.
                    is JsonPrimitive -> value.contentOrNull?.let { out[name] = Source(it, null) }
                    is JsonArray ->
                        out[name] =
                            Source(
                                stroke = (value.getOrNull(0) as? JsonPrimitive)?.contentOrNull.orEmpty(),
                                fill = (value.getOrNull(1) as? JsonPrimitive)?.contentOrNull,
                            )
                    else -> Unit
                }
            }
            return LucideSet(out)
        }
    }
}
