package me.parham1995.passwords.autofill

import com.github.androidpasswordstore.autofillparser.FormOrigin
import me.parham1995.passwords.store.CredentialMatcher

/**
 * The parser's idea of who is asking, as the matcher's. It has already
 * decided whether a browser is trusted to name the page; an untrusted one
 * arrives as an app, by package.
 */
fun FormOrigin.toTarget(): CredentialMatcher.Target =
    when (this) {
        is FormOrigin.Web -> CredentialMatcher.Target.Web(identifier)
        is FormOrigin.App -> CredentialMatcher.Target.App(identifier)
    }
