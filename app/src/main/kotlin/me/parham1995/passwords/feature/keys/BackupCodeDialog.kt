package me.parham1995.passwords.feature.keys

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import me.parham1995.passwords.ui.toCharArray

@Composable
fun BackupCodeDialog(
    onSubmit: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    val code = rememberTextFieldState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Backup code") },
        text = {
            Column {
                Text("This key file is locked. Enter the code it was backed up with.")
                OutlinedSecureTextField(state = code)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(code.toCharArray()).also { code.clearText() } }, enabled = code.text.isNotEmpty()) {
                Text("Open")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
