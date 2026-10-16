package me.parham1995.passwords.feature.entry

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import me.parham1995.passwords.store.Documents

/**
 * A file kept in the store -- a certificate, a VPN profile -- shown as the
 * file it is: its name, its text when it has some, and a way out of the app.
 * Saving goes through the system file picker, so the plaintext copy lands
 * only where it was asked to.
 */
@Composable
fun DocumentCard(
    document: Documents.Document,
    onCopy: (String) -> Unit,
) {
    val context = LocalContext.current
    var status by remember { mutableStateOf<String?>(null) }
    val save =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream"),
        ) { uri: Uri? ->
            if (uri != null) {
                status =
                    runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(document.bytes) } }
                        .fold({ "Saved ${document.fileName}" }, { "Could not save: ${it.message}" })
            }
        }
    val text = if (document.isText) document.bytes.toString(Charsets.UTF_8) else null
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(document.fileName, style = MaterialTheme.typography.titleMedium)
            Text(
                "${document.bytes.size} bytes" +
                    if (document.attachment) " · gopass attachment" else " · stored as text",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (text != null) {
                SelectionContainer(
                    Modifier
                        .padding(top = 8.dp)
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState()),
                ) { Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            }
            Row {
                if (text != null) TextButton(onClick = { onCopy(text) }) { Text("Copy") }
                TextButton(onClick = { save.launch(document.fileName) }) { Text("Save as file") }
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
