package me.parham1995.passwords.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.parham1995.passwords.data.Settings

/**
 * Who the phone's commits are by. Read on every commit, so a change applies to
 * the next one; left empty, commits are by `passwords-droid`.
 */
@Composable
internal fun GitAuthor(
    settings: Settings,
    onSave: (name: String, email: String) -> Unit,
) {
    var name by rememberSaveable(settings.authorName) { mutableStateOf(settings.authorName) }
    var email by rememberSaveable(settings.authorEmail) { mutableStateOf(settings.authorEmail) }
    val emailValid = email.isBlank() || EMAIL.matches(email.trim())
    val changed = name.trim() != settings.authorName || email.trim() != settings.authorEmail
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Commit author", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            placeholder = { Text("passwords-droid") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            placeholder = { Text("passwords-droid@localhost") },
            singleLine = true,
            isError = !emailValid,
            supportingText = {
                Text(if (emailValid) "GitHub links a commit to an account by this address" else "Not an email address")
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = { onSave(name.trim(), email.trim()) }, enabled = changed && emailValid) {
            Text("Save author")
        }
    }
}

private val EMAIL = Regex("""^[^@\s]+@[^@\s]+$""")
