package me.parham1995.passwords.store

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * Icons for the store's folders, kept in the store itself as `.icons.json`.
 *
 * gopass has no notion of icons, and none is needed: it reads `*.gpg` and
 * `.gpg-id` and ignores every other file, so a hidden JSON file at the root
 * travels with the repository to every device without gopass, `pass` or
 * `gopass fsck` noticing. It is not encrypted and does not need to be -- folder
 * names are already in plain text in git, and an icon adds nothing to them.
 *
 * ```json
 * {
 *   "folders": {
 *     "aut": { "icon": "graduation-cap", "color": "blue" }
 *   },
 *   "version": 1
 * }
 * ```
 *
 * Icons are Lucide names and colours are Iconic's names, the same vocabulary
 * Daftar reads from an Obsidian vault. Keys this version does not know -- at
 * the top level or inside a folder's entry -- are kept when the file is
 * written back, so a newer app or a hand edit is never silently undone.
 */
class StoreIcons private constructor(
    private val root: JsonObject,
) {
    data class Icon(
        /** A Lucide icon name, e.g. `graduation-cap`. */
        val name: String,
        /** An Iconic colour name (`red`, `blue`, ...) or null for the ordinary tint. */
        val color: String? = null,
    )

    private val folders: JsonObject get() = root[FOLDERS] as? JsonObject ?: JsonObject(emptyMap())

    fun folder(path: String): Icon? {
        val entry = folders[path] as? JsonObject ?: return null
        val name = (entry[ICON] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        return Icon(name, (entry[COLOR] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() })
    }

    val isEmpty: Boolean get() = folders.isEmpty()

    /** A copy with [path]'s icon set, or removed when [icon] is null. */
    fun withFolder(
        path: String,
        icon: Icon?,
    ): StoreIcons {
        val updated = folders.toMutableMap()
        if (icon == null) {
            updated.remove(path)
        } else {
            // Keep whatever else a newer version stored next to the icon.
            val previous = folders[path] as? JsonObject ?: JsonObject(emptyMap())
            updated[path] =
                JsonObject(
                    previous.toMutableMap().apply {
                        this[ICON] = JsonPrimitive(icon.name)
                        if (icon.color == null) remove(COLOR) else this[COLOR] = JsonPrimitive(icon.color)
                    },
                )
        }
        return StoreIcons(
            JsonObject(
                root.toMutableMap().apply {
                    this[FOLDERS] = JsonObject(updated)
                    putIfAbsent(VERSION, JsonPrimitive(CURRENT_VERSION))
                },
            ),
        )
    }

    /** Sorted keys, two-space indent, trailing newline: one change, one line of diff. */
    fun format(): String = pretty.encodeToString(JsonElement.serializer(), sorted(root)) + "\n"

    companion object {
        const val FILE = ".icons.json"
        private const val FOLDERS = "folders"
        private const val ICON = "icon"
        private const val COLOR = "color"
        private const val VERSION = "version"
        private const val CURRENT_VERSION = 1

        /** The colours Iconic offers, which the picker offers too. */
        val COLORS = listOf("red", "orange", "yellow", "green", "cyan", "blue", "purple", "pink", "gray")

        val EMPTY = StoreIcons(JsonObject(emptyMap()))

        private val pretty =
            Json {
                prettyPrint = true
                prettyPrintIndent = "  "
            }

        /** Never throws: a file someone broke by hand means no icons, not no store. */
        fun parse(text: String): StoreIcons =
            runCatching { Json.parseToJsonElement(text) as? JsonObject }
                .getOrNull()
                ?.let { root ->
                    // A version this app does not understand might mean anything; show no icons, keep the file.
                    val version = (root[VERSION] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: CURRENT_VERSION
                    if (version > CURRENT_VERSION) null else StoreIcons(root)
                } ?: EMPTY

        private fun sorted(element: JsonElement): JsonElement =
            when (element) {
                is JsonObject ->
                    buildJsonObject {
                        element.keys.sorted().forEach {
                            put(
                                it,
                                sorted(element.getValue(it)),
                            )
                        }
                    }
                else -> element
            }
    }
}
