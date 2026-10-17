package me.parham1995.passwords.feature.create

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.parham1995.passwords.store.CreateWizard
import me.parham1995.passwords.store.CreateWizard.Attribute
import me.parham1995.passwords.store.LoginMethods

/**
 * The new-entry flow: choose a `gopass create` template or the plain form,
 * then answer the template's questions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateScreen(
    onBack: () -> Unit,
    onBlank: () -> Unit,
    onDone: (String) -> Unit,
    viewModel: CreateViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val done by viewModel.done.collectAsStateWithLifecycle()
    LaunchedEffect(done) { done?.let(onDone) }
    val form = state.form
    BackHandler(enabled = form != null) { viewModel.back() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(form?.template?.name ?: "New entry") },
                navigationIcon = {
                    IconButton(onClick = { if (form != null) viewModel.back() else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (form != null) {
                        IconButton(onClick = { viewModel.save() }, enabled = !state.saving) {
                            Icon(Icons.Default.Check, "Save")
                        }
                    }
                },
            )
        },
    ) { padding ->
        val modifier = Modifier.padding(padding).fillMaxSize()
        if (form == null) {
            TemplateList(state.templates, onPick = viewModel::pick, onBlank = onBlank, modifier = modifier)
        } else {
            Wizard(form, state, viewModel, modifier)
        }
    }

    state.exists?.let { name ->
        AlertDialog(
            onDismissRequest = viewModel::dismissExists,
            title = { Text("$name already exists") },
            text = {
                Text(
                    "Choose another name, or overwrite the entry that is there. Git history keeps the old one.",
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.save(overwrite = true) }) { Text("Overwrite") } },
            dismissButton = { TextButton(onClick = viewModel::dismissExists) { Text("Choose another name") } },
        )
    }
}

@Composable
private fun TemplateList(
    templates: List<CreateWizard.Template>?,
    onPick: (CreateWizard.Template) -> Unit,
    onBlank: () -> Unit,
    modifier: Modifier,
) {
    LazyColumn(modifier) {
        item(key = "blank") {
            ListItem(
                headlineContent = { Text("Blank entry") },
                supportingContent = { Text("A password and any fields you add") },
                leadingContent = { Icon(Icons.Default.Create, null) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                modifier = Modifier.clickable(onClick = onBlank),
            )
        }
        item(key = "divider") { HorizontalDivider(Modifier.padding(horizontal = 16.dp)) }
        item(key = "header") {
            Text(
                "gopass create templates",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        if (templates == null) {
            item(key = "loading") { CircularProgressIndicator(Modifier.padding(32.dp)) }
        } else {
            itemsIndexed(templates, key = { index, template -> "t:$index:${template.name}" }) { _, template ->
                ListItem(
                    headlineContent = { Text(template.name.ifEmpty { "Unnamed template" }) },
                    supportingContent = {
                        val where = template.prefix.trim('/').ifEmpty { "the store" }
                        Text("Saved under $where", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                    modifier = Modifier.clickable { onPick(template) },
                )
            }
        }
    }
}

@Composable
private fun Wizard(
    form: WizardForm,
    state: CreateViewModel.State,
    viewModel: CreateViewModel,
    modifier: Modifier,
) {
    Column(
        modifier
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (form.template.welcome.isNotBlank()) {
            Text(form.template.welcome.trim(), style = MaterialTheme.typography.titleMedium)
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        form.template.attributes.forEachIndexed { index, attribute ->
            key(index, attribute.name) {
                AttributeField(form, attribute, state.showErrors, viewModel)
            }
        }
        OutlinedTextField(
            value = form.name,
            onValueChange = { v -> viewModel.edit { it.withName(v) } },
            label = { Text("Name") },
            supportingText = {
                Text(if (form.nameEdited) "Typed by you; clear it to follow the answers" else "Built from the answers")
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.saving) CircularProgressIndicator()
    }
}

@Composable
private fun AttributeField(
    form: WizardForm,
    attribute: Attribute,
    showErrors: Boolean,
    viewModel: CreateViewModel,
) {
    when (attribute.type) {
        Attribute.STRING, Attribute.HOSTNAME -> {
            val value = form.text(attribute)
            val error = WizardForm.lengthError(attribute, value) ?: missing(attribute, value, showErrors)
            OutlinedTextField(
                value = value,
                onValueChange = { v -> viewModel.edit { it.withText(attribute, v) } },
                label = { Text(attribute.label) },
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType =
                            if (attribute.type ==
                                Attribute.HOSTNAME
                            ) {
                                KeyboardType.Uri
                            } else {
                                KeyboardType.Text
                            },
                        autoCorrectEnabled = false,
                    ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Attribute.MULTILINE ->
            OutlinedTextField(
                value = form.text(attribute),
                onValueChange = { v -> viewModel.edit { it.withText(attribute, v) } },
                label = { Text(attribute.label) },
                minLines = 3,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
        Attribute.CHOICE -> ChoiceField(form, attribute, viewModel)
        Attribute.PASSWORD -> PasswordField(form, attribute, showErrors, viewModel)
        // gopass skips attribute types it does not know; so does the phone.
        else -> Unit
    }
}

private fun missing(
    attribute: Attribute,
    value: String,
    showErrors: Boolean,
): String? = if (showErrors && value.isEmpty() && attribute.min > 0) "Required" else null

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceField(
    form: WizardForm,
    attribute: Attribute,
    viewModel: CreateViewModel,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = form.text(attribute)
    // gopass's SSO template asks for a login method: show the provider, store the option.
    val label: (String) -> String =
        if (attribute.name == LOGIN_METHOD) LoginMethods::label else { option -> option }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = if (selected.isEmpty()) "" else label(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(attribute.label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            modifier =
                Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            attribute.options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    onClick = {
                        viewModel.edit { it.withText(attribute, option) }
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

@Composable
private fun PasswordField(
    form: WizardForm,
    attribute: Attribute,
    showErrors: Boolean,
    viewModel: CreateViewModel,
) {
    val answer = form.password(attribute)
    if (attribute.optional) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "This account has a password (off for SSO / social login)",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = answer.present,
                onCheckedChange = { on -> viewModel.edit { it.withPassword(attribute) { a -> a.copy(present = on) } } },
                modifier = Modifier.semantics { contentDescription = "This account has a password" },
            )
        }
        if (!answer.present) return
    }
    var revealed by remember { mutableStateOf(false) }
    // Generated passwords are not held to min and max, as in gopass.
    val error =
        if (answer.value == answer.generated) {
            null
        } else {
            WizardForm.lengthError(attribute, answer.value)
                ?: if (showErrors && answer.value.isEmpty()) "Required" else null
        }
    OutlinedTextField(
        value = answer.value,
        onValueChange = { v -> viewModel.edit { it.withPassword(attribute) { a -> a.copy(value = v) } } },
        label = { Text(attribute.label) },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = true,
        textStyle = TextStyle(fontFamily = FontFamily.Monospace),
        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { TextButton(onClick = { revealed = !revealed }) { Text(if (revealed) "Hide" else "Show") } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    if (!attribute.alwaysPrompt) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = answer.length,
                onValueChange = { v ->
                    viewModel.edit {
                        it.withPassword(
                            attribute,
                        ) { a -> a.copy(length = v.filter(Char::isDigit).take(3)) }
                    }
                },
                label = { Text("Length") },
                placeholder = { Text(WizardForm.defaultLength(attribute).toString()) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(120.dp),
            )
            TextButton(onClick = { viewModel.edit { it.generate(attribute) } }) {
                Icon(Icons.Default.Refresh, null)
                Text("Generate")
            }
            if (attribute.charset.isNotEmpty()) {
                Text(
                    "From ${attribute.charset.length} characters" + if (attribute.strict) ", every kind" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val LOGIN_METHOD = "login-method"
