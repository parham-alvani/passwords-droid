package me.parham1995.passwords.feature.edit

import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.parham1995.passwords.crypto.PgpException
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

        data class Form(
            val name: String = "",
            val password: String = "",
            val username: String = "",
            val url: String = "",
            val otp: String = "",
            val notes: String = "",
            /** Fields other than the ones above, kept as they were. */
            val otherFields: List<PassEntry.Field> = emptyList(),
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

        private val _done = MutableStateFlow<String?>(null)

        /** The saved name, once saved. */
        val done: StateFlow<String?> = _done.asStateFlow()

        private val _deleted = MutableStateFlow(false)
        val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

        val isNew: Boolean get() = original == null

        init {
            if (original == null) {
                _form.update { it.copy(password = PasswordGenerator.generate()) }
            } else {
                unlock(original)
            }
        }

        fun edit(transform: (Form) -> Form) = _form.update { transform(it).copy(error = null) }

        /** The `.pass-template` a new entry with the current name would get, if any. */
        fun template(): String? =
            if (original ==
                null
            ) {
                _form.value.name
                    .trim()
                    .trim('/')
                    .takeIf { it.isNotEmpty() }
                    ?.let { repository.templateFor(it) }
            } else {
                null
            }

        fun generate() = edit { it.copy(password = PasswordGenerator.generate()) }

        fun save() =
            viewModelScope.launch {
                val form = _form.value
                val name = form.name.trim().trim('/')
                if (name.isEmpty() || name.split('/').any { it.isBlank() || it.startsWith('.') }) {
                    _form.update { it.copy(error = "Give it a name like folder/entry") }
                    return@launch
                }
                if (name != original && repository.store.file(name).exists()) {
                    _form.update { it.copy(error = "$name already exists") }
                    return@launch
                }
                _form.update { it.copy(saving = true) }
                val known =
                    buildList {
                        if (form.username.isNotBlank()) add(PassEntry.Field("login", form.username.trim()))
                        if (form.url.isNotBlank()) add(PassEntry.Field("url", form.url.trim()))
                        addAll(form.otherFields)
                    }
                val entry =
                    PassEntry(
                        password = form.password,
                        fields = known,
                        otpUri = form.otp.trim().ifEmpty { null },
                        notes = form.notes.trimEnd(),
                    )
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
                            "Add given password for $name to store.",
                        )
                    } else {
                        repository.save(name, entry, previousName = original)
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
                    val usernameField = entry.fields.firstOrNull { it.value == entry.username }
                    val urlField = entry.fields.firstOrNull { it.value == entry.url && it != usernameField }
                    _form.value =
                        Form(
                            name = name,
                            password = entry.password,
                            username = entry.username.orEmpty(),
                            url = entry.url.orEmpty(),
                            otp = entry.otpUri.orEmpty(),
                            notes = entry.notes,
                            otherFields = entry.fields.filterNot { it == usernameField || it == urlField },
                        )
                } catch (e: PgpException) {
                    onUnlockFailed(e.message ?: "Could not decrypt")
                } finally {
                    passphrase?.fill('\u0000')
                }
            }
        }

        override fun onUnlockFailed(message: String) {
            _form.update { it.copy(loading = false, error = message) }
        }
    }
