package me.parham1995.passwords.store

/**
 * How a passwordless account signs in, as people read it. The values are the
 * options of gopass's "SSO / passwordless login" template (`login-method`);
 * anything else a person typed is shown as it is.
 */
object LoginMethods {
    private val LABELS =
        mapOf(
            "google" to "Google",
            "apple" to "Apple",
            "github" to "GitHub",
            "microsoft" to "Microsoft",
            "sso" to "SSO",
            "magic-link" to "Magic link",
        )

    /** `google` → `Google`, `magic-link` → `Magic link`; an unknown method stays as written. */
    fun label(method: String): String = LABELS[method.trim().lowercase()] ?: method.trim()

    /** "Sign in with Google", for an entry that has no password of its own. */
    fun signIn(method: String): String = "Sign in with " + label(method)

    /** An autofill suggestion's title: `me@x.com · Google`, or just the provider without a username. */
    fun suggestion(
        username: String,
        method: String,
    ): String = listOf(username, label(method)).filter { it.isNotBlank() }.joinToString(" · ")
}
