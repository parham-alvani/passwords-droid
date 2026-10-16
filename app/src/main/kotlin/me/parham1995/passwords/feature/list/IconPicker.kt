package me.parham1995.passwords.feature.list

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import me.parham1995.passwords.store.StoreIcons
import me.parham1995.passwords.ui.icon.LocalLucide
import me.parham1995.passwords.ui.icon.LucideIcon
import me.parham1995.passwords.ui.icon.iconColor

/**
 * Picks a Lucide icon and an Iconic colour for a folder. With nothing typed it
 * offers the icons a password store's folders tend to be -- work, school,
 * bank, network -- and searching reaches all two thousand.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconPicker(
    folder: String,
    current: StoreIcons.Icon?,
    onPick: (StoreIcons.Icon?) -> Unit,
    onDismiss: () -> Unit,
) {
    val lucide = LocalLucide.current
    var query by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(current?.name) }
    var color by remember { mutableStateOf(current?.color) }
    val shown =
        remember(query, lucide) {
            val needle = query.trim().lowercase()
            if (needle.isEmpty()) {
                SUGGESTED.filter { it in lucide }
            } else {
                lucide.names
                    .filter { needle in it }
                    .sortedWith(compareBy({ !it.startsWith(needle) }, { it.length }, { it }))
                    .take(MAX_RESULTS)
            }
        }
    val fallback = MaterialTheme.colorScheme.primary

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(
                            48.dp,
                        ).clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) { name?.let { LucideIcon(it, size = 28.dp, tint = iconColor(color, fallback)) } }
                Column(Modifier.padding(start = 12.dp)) {
                    Text(folder, style = MaterialTheme.typography.titleMedium)
                    Text(name ?: "No icon", style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search ${lucide.size} icons") },
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                modifier = Modifier.fillMaxWidth(),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Swatch(null, selected = color == null, fallback) { color = null } }
                items(StoreIcons.COLORS) { option ->
                    Swatch(option, selected = color == option, fallback) {
                        color =
                            option
                    }
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(52.dp),
                modifier = Modifier.fillMaxWidth().height(280.dp),
            ) {
                items(shown, key = { it }) { option ->
                    Box(
                        Modifier
                            .padding(4.dp)
                            .size(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (option ==
                                    name
                                ) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                            ).clickable { name = option },
                        contentAlignment = Alignment.Center,
                    ) {
                        LucideIcon(
                            option,
                            size = 24.dp,
                            tint = iconColor(color, fallback),
                            contentDescription = option,
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.End) {
                if (current != null) TextButton(onClick = { onPick(null) }) { Text("Remove icon") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    onClick = { onPick(name?.let { StoreIcons.Icon(it, color) }) },
                    enabled = name != null,
                ) { Text("Save") }
            }
        }
    }
}

@Composable
private fun Swatch(
    name: String?,
    selected: Boolean,
    fallback: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(iconColor(name, fallback))
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
            .clickable(onClick = onClick),
    )
}

private const val MAX_RESULTS = 120

/** What the folders of a password store tend to be. Names missing from the bundled set are skipped. */
private val SUGGESTED =
    listOf(
        "briefcase",
        "building-2",
        "graduation-cap",
        "school",
        "landmark",
        "wallet",
        "credit-card",
        "piggy-bank",
        "bitcoin",
        "banknote",
        "house",
        "user",
        "users",
        "heart",
        "heart-pulse",
        "stethoscope",
        "car",
        "plane",
        "wifi",
        "router",
        "server",
        "cloud",
        "database",
        "globe",
        "mail",
        "phone",
        "smartphone",
        "message-circle",
        "send",
        "github",
        "gitlab",
        "git-branch",
        "code",
        "terminal",
        "key-round",
        "shield",
        "lock",
        "fingerprint",
        "shopping-cart",
        "store",
        "gamepad-2",
        "music",
        "film",
        "camera",
        "book",
        "newspaper",
        "rocket",
        "star",
    )
