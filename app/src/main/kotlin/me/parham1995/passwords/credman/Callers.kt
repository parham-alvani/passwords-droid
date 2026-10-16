package me.parham1995.passwords.credman

import android.content.Context
import androidx.credentials.provider.CallingAppInfo
import me.parham1995.passwords.R
import me.parham1995.passwords.store.CredentialMatcher

/**
 * Who is asking for a password, as something [CredentialMatcher] can match.
 *
 * A browser asks on behalf of a web page, and only a browser whose signing
 * certificate is on a privileged list is believed about which page that is --
 * otherwise any app could claim to be "github.com". The lists are Google's
 * (gstatic.com/gpm-passkeys-privileged-apps) and the Bitwarden community's,
 * the same two Password Store ships. Anything else is matched as itself, by
 * package name.
 */
class Callers(
    context: Context,
) {
    private val allowlists: List<String> by lazy {
        listOf(R.raw.privileged_google, R.raw.privileged_community).map { id ->
            context.resources
                .openRawResource(id)
                .bufferedReader()
                .use { it.readText() }
        }
    }

    fun targetOf(caller: CallingAppInfo): CredentialMatcher.Target {
        val origin =
            allowlists.firstNotNullOfOrNull { list ->
                // Throws when the app is on the list with a different signature: an impostor.
                runCatching { caller.getOrigin(list) }.getOrNull()
            }
        return if (origin !=
            null
        ) {
            CredentialMatcher.Target.Web(origin)
        } else {
            CredentialMatcher.Target.App(caller.packageName)
        }
    }
}
