package me.parham1995.passwords.feature.list

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
import me.parham1995.passwords.data.PasswordRepository
import me.parham1995.passwords.store.PasswordStore
import me.parham1995.passwords.store.Search
import javax.inject.Inject

@HiltViewModel
class ListViewModel
    @Inject
    constructor(
        private val repository: PasswordRepository,
    ) : ViewModel() {
        private val _query = MutableStateFlow("")
        val query: StateFlow<String> = _query.asStateFlow()

        private val _syncing = MutableStateFlow(false)
        val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

        private val _message = MutableStateFlow<String?>(null)
        val message: StateFlow<String?> = _message.asStateFlow()

        val items: StateFlow<List<PasswordStore.Item>> =
            combine(repository.items, _query) { items, query -> Search.rank(query, items) { it.name } }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MILLIS), emptyList())

        init {
            viewModelScope.launch { repository.refresh() }
        }

        fun search(text: String) {
            _query.value = text
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

        fun messageShown() {
            _message.value = null
        }

        private companion object {
            const val STOP_MILLIS = 5_000L
        }
    }
