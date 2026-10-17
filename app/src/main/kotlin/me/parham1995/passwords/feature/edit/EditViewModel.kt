package me.parham1995.passwords.feature.edit

import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.parham1995.passwords.crypto.PgpException
import me.parham1995.passwords.data.CommitMessages
import me.parham1995.passwords.data.MissingRecipients
import me.parham1995.passwords.data.PasswordRepository
import me.parham1995.passwords.store.PassEntry
import me.parham1995.passwords.store.PasswordGenerator
import me.parham1995.passwords.store.Templates
import me.parham1995.passwords.unlock.Unlocker
import me.parham1995.passwords.unlock.UnlockingViewModel

@HiltViewModel(assistedFactory = EditViewModel.Factory::class)
class EditViewModel
    @AssistedInject
    constructor(
        @Assisted("name") private val original: String?,
        @Assisted("folder") folder: String,
        repository: PasswordRepository,
        unlocker: Unlocker,
    ) : UnlockingViewModel(repository, unlocker) {
        @AssistedFactory
        interface Factory {
            fun create(
                @Assisted("name") name: String?,
                @Assisted("folder") folder: String,
            ): EditViewModel
        }

        /** A field on the form, with an id that outlives edits so Compose keeps focus on the right row. */
        data class Row(
            val id: Long,
            val field: PassEntry.Field,
        )

        data class Form(
            val name: String = "",
            val password: String = "",
            val otp: String = "",
            val notes: String = "",
            /** The entry's fields, in file order, with their keys as written. */
            val fields: List<Row> = emptyList(),
            /** Written back as a gopass YAML body, because it was read as one. */
            val yaml: Boolean = false,
            val loading: Boolean = false,
            val saving: Boolean = false,
            val error: String? = null,
        )

        private val _form =
            MutableStateFlow(
                Form(
                    name = if (folder.isEmpty()) "" else "$folder/",
                    loading =
                        original != null,
                ),
            )
        val form: StateFlow<Form> = _form.asStateFlow()

        /**
         * The `.pass-template` a new entry with the current name would get, if
         * any. Looked up off the main thread once typing pauses, not per keystroke.
         */
        @OptIn(FlowPreview::class)
        val template: StateFlow<String?> =
            _form
                .map { it.name.trim().trim('/') }
                .distinctUntilChanged()
                .debounce(TEMPLATE_DEBOUNCE_MILLIS)
                .map { name ->
                    if (original != null ||
                        name.isEmpty()
                    ) {
                        null
                    } else {
                        withContext(Dispatchers.IO) { repository.templateFor(name) }
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

        private val _done = MutableStateFlow<String?>(null)

        /** The saved name, once saved. */
        val done: StateFlow<String?> = _done.asStateFlow()

        private val _deleted = MutableStateFlow(false)
        val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

        val isNew: Boolean get() = original == null

        private var nextRow = 0L

        /** The entry as read, so a save rewrites only what changed. */
        private var loaded: PassEntry? = null

        /** The last password the generator made: a new entry saved with it says "Generate Password". */
        private var generatedPassword: String? = null

        init {
            if (original == null) {
                _form.update {
                    it.copy(
                        password = PasswordGenerator.generate().also { generatedPassword = it },
                        fields = rows(listOf(PassEntry.Field("login", ""), PassEntry.Field("url", ""))),
                    )
                }
            } else {
                unlock(original)
            }
        }

        fun edit(transform: (Form) -> Form) = _form.update { transform(it).copy(error = null) }

        fun generate() = edit { it.copy(password = PasswordGenerator.generate().also { generatedPassword = it }) }

        fun editField(
            id: Long,
            transform: (PassEntry.Field) -> PassEntry.Field,
        ) = edit { form ->
            form.copy(fields = form.fields.map { if (it.id == id) it.copy(field = transform(it.field)) else it })
        }

        fun addField() = edit { it.copy(fields = it.fields + rows(listOf(PassEntry.Field("", "")))) }

        fun removeField(id: Long) = edit { form -> form.copy(fields = form.fields.filterNot { it.id == id }) }

        private fun rows(fields: List<PassEntry.Field>): List<Row> = fields.map { Row(nextRow++, it) }

        /** The fields as they will be written, or why they cannot be. */
        private fun cleanFields(form: Form): Result<List<PassEntry.Field>> {
            val fields =
                form.fields.map { it.field }.mapNotNull { field ->
                    // A field nobody touched goes back byte for byte, trailing newline and all.
                    if (field.nested || loaded?.fields?.contains(field) == true) return@mapNotNull field
                    val key = field.key.trim()
                    // A YAML block keeps its inner lines; anything else is one trimmed line.
                    val value = field.value.trimEnd().let { if (form.yaml && '\n' in it) it else it.trim() }
                    // An empty row is dropped; an existing field with an empty value is kept.
                    if (value.isEmpty() && (key.isEmpty() || isNew)) null else PassEntry.Field(key, value)
                }
            fields.firstOrNull { !it.nested && !PassEntry.isValidKey(it.key) }?.let {
                return Result.failure(
                    IllegalArgumentException(
                        "\"${it.key}\" is not a field name: it needs text, no colon, and no space at either end",
                    ),
                )
            }
            if (!form.yaml) {
                fields.firstOrNull { '\n' in it.value }?.let {
                    return Result.failure(IllegalArgumentException("${it.key} must be one line in a key: value entry"))
                }
            } else {
                fields.groupBy { it.key }.entries.firstOrNull { it.value.size > 1 }?.let {
                    return Result.failure(
                        IllegalArgumentException("${it.key} is there twice; YAML keys must be unique"),
                    )
                }
            }
            return Result.success(fields)
        }

        fun save() =
            viewModelScope.launch {
                val form = _form.value
                val name = form.name.trim().trim('/')
                if (name.isEmpty() || name.split('/').any { it.isBlank() || it.startsWith('.') }) {
                    _form.update { it.copy(error = "Give it a name like folder/entry") }
                    return@launch
                }
                if (name != original && repository.exists(name)) {
                    _form.update { it.copy(error = "$name already exists") }
                    return@launch
                }
                val known =
                    cleanFields(form).getOrElse { e ->
                        _form.update { it.copy(error = e.message) }
                        return@launch
                    }
                _form.update { it.copy(saving = true) }
                // Copying the entry as read keeps the lines nobody touched as they were.
                val entry =
                    (loaded ?: PassEntry(password = "", fields = emptyList(), otpUri = null, notes = "")).copy(
                        password = form.password,
                        fields = known,
                        otpUri = form.otp.trim().ifEmpty { null },
                        notes = form.notes.trimEnd(),
                        yaml = form.yaml,
                    )
                val generated = original == null && form.password == generatedPassword
                // A new entry in a folder with a .pass-template is shaped by it, as
                // `gopass generate` does -- unless something was typed beyond
                // the password, which then wins.
                val template =
                    if (original == null &&
                        known.isEmpty() &&
                        entry.otpUri == null &&
                        entry.notes.isEmpty()
                    ) {
                        repository.templateFor(name)
                    } else {
                        null
                    }
                runCatching {
                    if (template != null) {
                        repository.saveText(
                            name,
                            Templates.render(template, name, form.password),
                            if (generated) CommitMessages.generate(name) else CommitMessages.insert(name),
                        )
                    } else {
                        repository.save(name, entry, previousName = original, generated = generated)
                    }
                }.onSuccess { _done.value = name }
                    .onFailure { e ->
                        val message =
                            when (e) {
                                is MissingRecipients ->
                                    "No public key for ${e.ids.joinToString()}. Import it in Settings."
                                else -> e.message ?: "Could not save"
                            }
                        _form.update { it.copy(saving = false, error = message) }
                    }
            }

        fun delete() =
            viewModelScope.launch {
                val name = original ?: return@launch
                runCatching { repository.delete(name) }
                    .onSuccess { _deleted.value = true }
                    .onFailure { e -> _form.update { it.copy(error = e.message) } }
            }

        override fun onUnlocked(passphrase: CharArray?) {
            val name = original ?: return
            viewModelScope.launch {
                try {
                    val entry = repository.open(name, passphrase)
                    loaded = entry
                    _form.value =
                        Form(
                            name = name,
                            password = entry.password,
                            otp = entry.otpUri.orEmpty(),
                            notes = entry.notes,
                            fields = rows(entry.fields),
                            yaml = entry.yaml,
                        )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: PgpException) {
                    onUnlockFailed(e.message ?: "Could not decrypt")
                } catch (e: Exception) {
                    onUnlockFailed(e.message ?: "Could not open $name")
                } finally {
                    passphrase?.fill('\u0000')
                }
            }
        }

        override fun onUnlockFailed(message: String) {
            _form.update { it.copy(loading = false, error = message) }
        }

        private companion object {
            const val TEMPLATE_DEBOUNCE_MILLIS = 300L
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
