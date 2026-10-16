package me.parham1995.passwords

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import me.parham1995.passwords.data.SettingsStore
import me.parham1995.passwords.feature.edit.EditScreen
import me.parham1995.passwords.feature.entry.EntryScreen
import me.parham1995.passwords.feature.list.ListScreen
import me.parham1995.passwords.feature.settings.SettingsScreen
import me.parham1995.passwords.feature.setup.SetupScreen
import javax.inject.Inject

@Serializable
object Setup

@Serializable
object Entries

@Serializable
data class Entry(
    val name: String,
)

/** [name] null creates a new entry; [folder] pre-fills where it goes. */
@Serializable
data class Edit(
    val name: String? = null,
    val folder: String = "",
)

@Serializable
object Preferences

@HiltViewModel
class StartViewModel
    @Inject
    constructor(
        settings: SettingsStore,
    ) : ViewModel() {
        val configured =
            settings.settings
                .map { it.isConfigured }
                .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    }

@Composable
fun PasswordsNavHost() {
    val start: StartViewModel = hiltViewModel()
    val configured by start.configured.collectAsState()
    // Wait for settings, so a configured app never flashes the setup screen.
    val ready = configured ?: return
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = if (ready) Entries else Setup) {
        composable<Setup> {
            SetupScreen(onDone = { nav.navigate(Entries) { popUpTo(Setup) { inclusive = true } } })
        }
        composable<Entries> {
            ListScreen(
                onOpen = { nav.navigate(Entry(it)) },
                onAdd = { folder -> nav.navigate(Edit(folder = folder)) },
                onSettings = { nav.navigate(Preferences) },
            )
        }
        composable<Entry> { back ->
            val route = back.toRoute<Entry>()
            EntryScreen(
                name = route.name,
                onBack = { nav.popBackStack() },
                onEdit = { nav.navigate(Edit(name = route.name)) },
            )
        }
        composable<Edit> { back ->
            val route = back.toRoute<Edit>()
            EditScreen(
                name = route.name,
                folder = route.folder,
                onDone = { saved ->
                    nav.popBackStack()
                    if (route.name == null && saved != null) nav.navigate(Entry(saved))
                },
                onDeleted = { nav.popBackStack(Entries, inclusive = false) },
            )
        }
        composable<Preferences> {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onReset = { nav.navigate(Setup) { popUpTo(0) } },
            )
        }
    }
}
