package me.parham1995.passwords.unlock

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation

@Composable
fun PassphraseDialog(
    label: String,
    wrong: Boolean,
    offerBiometric: Boolean,
    onSubmit: (passphrase: CharArray, useBiometric: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var useBiometric by remember { mutableStateOf(offerBiometric) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val submit = {
        onSubmit(text.toCharArray(), useBiometric)
        text = ""
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Passphrase") },
        text = {
            Column {
                Text(label)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    isError = wrong,
                    supportingText = { if (wrong) Text("Wrong passphrase") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                            autoCorrectEnabled = false,
                        ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.focusRequester(focus),
                )
                if (offerBiometric) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = useBiometric, onCheckedChange = { useBiometric = it })
                        Text("Use fingerprint next time")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = submit, enabled = text.isNotEmpty()) { Text("Unlock") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
