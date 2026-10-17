package me.parham1995.passwords.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Nothing secret is kept here; it is plain DataStore. */
data class Settings(
    val remoteUrl: String = "",
    val branch: String = "main",
    val authorName: String = "",
    val authorEmail: String = "",
    /** gopass's `core.cliptimeout` default. */
    val clipboardSeconds: Int = 45,
    /** How long a typed passphrase is remembered after its last use. */
    val passphraseMinutes: Int = 5,
    val biometricUnlock: Boolean = true,
    /** The share page links point at, e.g. `https://example.com/share/`. Empty: sharing is off. */
    val shareBaseUrl: String = "",
    /** The GitHub repository behind the share page, `owner/name`. */
    val shareRepository: String = "",
    val shareBranch: String = "main",
    /** Where in that repository shares go; the page fetches `s/<id>.age`. */
    val sharePrefix: String = "s/",
) {
    val isConfigured: Boolean get() = remoteUrl.isNotBlank()
}

@Singleton
class SettingsStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        val settings: Flow<Settings> = context.dataStore.data.map { it.toSettings() }

        suspend fun current(): Settings = settings.first()

        suspend fun update(transform: (Settings) -> Settings) {
            context.dataStore.edit { prefs ->
                val next = transform(prefs.toSettings())
                prefs[REMOTE] = next.remoteUrl
                prefs[BRANCH] = next.branch
                prefs[AUTHOR_NAME] = next.authorName
                prefs[AUTHOR_EMAIL] = next.authorEmail
                prefs[CLIPBOARD] = next.clipboardSeconds
                prefs[PASSPHRASE_MINUTES] = next.passphraseMinutes
                prefs[BIOMETRIC] = next.biometricUnlock
                prefs[SHARE_BASE_URL] = next.shareBaseUrl
                prefs[SHARE_REPOSITORY] = next.shareRepository
                prefs[SHARE_BRANCH] = next.shareBranch
                prefs[SHARE_PREFIX] = next.sharePrefix
            }
        }

        private fun Preferences.toSettings(): Settings {
            val defaults = Settings()
            return Settings(
                remoteUrl = this[REMOTE] ?: defaults.remoteUrl,
                branch = this[BRANCH] ?: defaults.branch,
                authorName = this[AUTHOR_NAME] ?: defaults.authorName,
                authorEmail = this[AUTHOR_EMAIL] ?: defaults.authorEmail,
                clipboardSeconds = this[CLIPBOARD] ?: defaults.clipboardSeconds,
                passphraseMinutes = this[PASSPHRASE_MINUTES] ?: defaults.passphraseMinutes,
                biometricUnlock = this[BIOMETRIC] ?: defaults.biometricUnlock,
                shareBaseUrl = this[SHARE_BASE_URL] ?: defaults.shareBaseUrl,
                shareRepository = this[SHARE_REPOSITORY] ?: defaults.shareRepository,
                shareBranch = this[SHARE_BRANCH] ?: defaults.shareBranch,
                sharePrefix = this[SHARE_PREFIX] ?: defaults.sharePrefix,
            )
        }

        private companion object {
            val REMOTE = stringPreferencesKey("remote_url")
            val BRANCH = stringPreferencesKey("branch")
            val AUTHOR_NAME = stringPreferencesKey("author_name")
            val AUTHOR_EMAIL = stringPreferencesKey("author_email")
            val CLIPBOARD = intPreferencesKey("clipboard_seconds")
            val PASSPHRASE_MINUTES = intPreferencesKey("passphrase_minutes")
            val BIOMETRIC = booleanPreferencesKey("biometric_unlock")
            val SHARE_BASE_URL = stringPreferencesKey("share_base_url")
            val SHARE_REPOSITORY = stringPreferencesKey("share_repository")
            val SHARE_BRANCH = stringPreferencesKey("share_branch")
            val SHARE_PREFIX = stringPreferencesKey("share_prefix")
        }
    }
