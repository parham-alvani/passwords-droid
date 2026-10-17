package me.parham1995.passwords.feature.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.parham1995.passwords.data.CommitMessages
import me.parham1995.passwords.data.MissingRecipients
import me.parham1995.passwords.data.PasswordRepository
import me.parham1995.passwords.store.CreateWizard
import javax.inject.Inject

/**
 * `gopass create` on the phone: pick a template -- the store's own, or
 * gopass's defaults when it has none -- answer its questions, and save the
 * entry under the name built from the answers, committed as gopass commits it.
 */
@HiltViewModel
class CreateViewModel
    @Inject
    constructor(
        private val repository: PasswordRepository,
    ) : ViewModel() {
        data class State(
            /** The templates to choose from; null while they are read. */
            val templates: List<CreateWizard.Template>? = null,
            /** The template being filled in; null while choosing. */
            val form: WizardForm? = null,
            /** Per-field length problems are shown once a save was tried. */
            val showErrors: Boolean = false,
            val saving: Boolean = false,
            val error: String? = null,
            /** A name that is already taken, waiting for "overwrite" or another name. */
            val exists: String? = null,
        )

        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state.asStateFlow()

        private val _done = MutableStateFlow<String?>(null)

        /** The saved name, once saved. */
        val done: StateFlow<String?> = _done.asStateFlow()

        init {
            viewModelScope.launch {
                val templates =
                    try {
                        repository.createTemplates()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        CreateWizard.defaults
                    }
                _state.update { it.copy(templates = templates) }
            }
        }

        fun pick(template: CreateWizard.Template) =
            _state.update {
                State(templates = it.templates, form = WizardForm.start(template))
            }

        /** Back to the template list, forgetting what was typed. */
        fun back() = _state.update { State(templates = it.templates) }

        fun edit(transform: (WizardForm) -> WizardForm) =
            _state.update { state -> state.form?.let { state.copy(form = transform(it), error = null) } ?: state }

        fun dismissExists() = _state.update { it.copy(exists = null) }

        /** Saves the entry; with [overwrite], over an existing one of the same name, as gopass asks to. */
        fun save(overwrite: Boolean = false) =
            viewModelScope.launch {
                val state = _state.value
                val form = state.form ?: return@launch
                if (state.saving) return@launch
                val created =
                    try {
                        CreateWizard.create(form.template, form.answers())
                    } catch (e: CreateWizard.InvalidAnswer) {
                        _state.update { it.copy(error = e.message, showErrors = true, exists = null) }
                        return@launch
                    }
                val name = (if (form.nameEdited) form.name else created.name).trim().trim('/')
                if (name.isEmpty() || name.split('/').any { it.isBlank() || it.startsWith('.') }) {
                    _state.update { it.copy(error = "Give it a name like folder/entry", exists = null) }
                    return@launch
                }
                if (!overwrite && repository.exists(name)) {
                    _state.update { it.copy(exists = name) }
                    return@launch
                }
                _state.update { it.copy(saving = true, exists = null) }
                runCatching { repository.saveText(name, created.text, CommitMessages.create(name)) }
                    .onSuccess { _done.value = name }
                    .onFailure { e ->
                        val message =
                            when (e) {
                                is MissingRecipients ->
                                    "No public key for ${e.ids.joinToString()}. Import it in Settings."
                                else -> e.message ?: "Could not save"
                            }
                        _state.update { it.copy(saving = false, error = message) }
                    }
            }
    }
