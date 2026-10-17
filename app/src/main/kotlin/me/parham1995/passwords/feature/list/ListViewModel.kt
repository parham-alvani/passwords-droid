package me.parham1995.passwords.feature.list

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.parham1995.passwords.data.CommitMessages
import me.parham1995.passwords.data.MissingRecipients
import me.parham1995.passwords.data.PasswordRepository
import me.parham1995.passwords.store.Documents
import me.parham1995.passwords.store.PasswordStore
import me.parham1995.passwords.store.Search
import me.parham1995.passwords.store.StoreIcons
import me.parham1995.passwords.store.StoreTree
import javax.inject.Inject

@HiltViewModel
class ListViewModel
    @Inject
    constructor(
        private val repository: PasswordRepository,
        private val saved: SavedStateHandle,
    ) : ViewModel() {
        /** What the screen shows: one folder while browsing, the whole store while searching. */
        sealed interface Content {
            data class Browsing(
                val listing: StoreTree.Listing,
            ) : Content

            data class Searching(
                val results: List<PasswordStore.Item>,
            ) : Content
        }

        private val _query = MutableStateFlow("")
        val query: StateFlow<String> = _query.asStateFlow()

        /** Kept across process death and rotation, so coming back lands in the same folder. */
        val folder: StateFlow<String> = saved.getStateFlow(FOLDER, "")

        private val _syncing = MutableStateFlow(false)
        val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

        private val _message = MutableStateFlow<String?>(null)
        val message: StateFlow<String?> = _message.asStateFlow()

        val icons: StateFlow<StoreIcons> = repository.icons

        fun setIcon(
            folder: String,
            icon: StoreIcons.Icon?,
        ) = viewModelScope.launch {
            runCatching { repository.setFolderIcon(folder, icon) }
                .onFailure { _message.value = "Could not save the icon: ${it.message}" }
        }

        val total: StateFlow<Int> =
            combine(repository.items, _query) { items, _ -> items.size }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MILLIS), 0)

        val content: StateFlow<Content> =
            combine(repository.items, _query, folder) { items, query, folder ->
                if (query.isBlank()) {
                    Content.Browsing(StoreTree.list(items, folder))
                } else {
                    Content.Searching(Search.rank(query, items) { it.name })
                }
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_MILLIS),
                Content.Browsing(StoreTree.list(emptyList(), "")),
            )

        init {
            viewModelScope.launch { repository.refresh() }
        }

        fun search(text: String) {
            _query.value = text
        }

        fun open(folder: String) {
            saved[FOLDER] = folder
        }

        /** Up one level. False at the root, so Back can leave the screen instead. */
        fun up(): Boolean {
            val parent = StoreTree.parent(folder.value) ?: return false
            saved[FOLDER] = parent
            return true
        }

        fun sync() =
            viewModelScope.launch {
                _syncing.value = true
                _message.value =
                    runCatching { repository.sync() }.fold(
                        onSuccess = { if (it.pending > 0) "${it.pending} change(s) not sent yet" else null },
                        onFailure = { "Sync failed: ${it.message}" },
                    )
                _syncing.value = false
            }

        /**
         * Stores a picked file at [name]: as itself when it is UTF-8 text --
         * how the store already keeps certificates and VPN profiles -- and as
         * a gopass attachment otherwise.
         */
        fun addFile(
            name: String,
            fileName: String,
            bytes: ByteArray,
        ) = viewModelScope.launch {
            val text =
                runCatching {
                    Charsets.UTF_8
                        .newDecoder()
                        .decode(java.nio.ByteBuffer.wrap(bytes))
                        .toString()
                }.getOrNull()
                    ?.takeIf { '\u0000' !in it }
            val content = text ?: Documents.attachment(fileName, bytes)
            val saved =
                runCatching {
                    repository.saveText(
                        name.trim('/'),
                        content,
                        CommitMessages.file(name.trim('/'), fileName),
                    )
                }
            _message.value =
                saved.exceptionOrNull()?.let { e ->
                    when (e) {
                        is MissingRecipients -> "No public key for ${e.ids.joinToString()}"
                        else -> "Could not add: ${e.message}"
                    }
                } ?: "Added $name"
        }

        fun messageShown() {
            _message.value = null
        }

        private companion object {
            const val STOP_MILLIS = 5_000L
            const val FOLDER = "folder"
        }
    }
