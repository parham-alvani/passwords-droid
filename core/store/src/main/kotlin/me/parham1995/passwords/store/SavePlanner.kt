package me.parham1995.passwords.store

/**
 * Decides what a "save this password?" offer from autofill or Credential
 * Manager does to the store, from names alone -- nothing is decrypted.
 *
 * - A login the store already has (same site, same account) is an
 *   [Plan.update]: only its password line changes, so a `totp:` line,
 *   recovery codes and notes kept with it survive a password change.
 * - A new web login is made by the store's own `gopass create` website
 *   template when it has one that fits ([websiteTemplate]), so the phone names
 *   and shapes it as `gopass create` would: `websites/<host>/<user>` with
 *   `url:` and `username:` lines.
 * - Anything else -- an app, or a store with no fitting template -- goes next
 *   to the site's existing accounts, or in a folder named after it.
 */
object SavePlanner {
    data class Plan(
        /** The existing entry for this login, whose password the offer would replace; null for a new login. */
        val update: String?,
        /** Where a new entry would go, and what it would hold. */
        val create: Create,
    )

    data class Create(
        val name: String,
        val text: String,
    )

    fun plan(
        items: List<PasswordStore.Item>,
        target: CredentialMatcher.Target?,
        id: String,
        password: String,
        templates: List<CreateWizard.Template>,
    ): Plan {
        val matches = target?.let { CredentialMatcher.find(items, it) }.orEmpty()
        val update =
            id
                .takeIf { it.isNotBlank() }
                ?.let { account -> matches.firstOrNull { it.username.equals(account, ignoreCase = true) } }
                ?.item
                ?.let { it.aliasOf ?: it.name }
        val url = (target as? CredentialMatcher.Target.Web)?.host?.let { if ("://" in it) it else "https://$it" }
        val fromTemplate =
            url?.let { websiteTemplate(templates) }?.let { template ->
                runCatching { create(template, url, id, password) }.getOrNull()
            }
        return Plan(update, fromTemplate ?: plain(matches, target, id, password, url))
    }

    /**
     * The template a saved web login is made with: one that asks for a
     * hostname, a password and at most one required piece of text (the
     * account) -- gopass's "Website login" and its look-alikes. One with a
     * choice to make (the SSO template) or more to fill in does not fit.
     */
    fun websiteTemplate(templates: List<CreateWizard.Template>): CreateWizard.Template? =
        templates.firstOrNull { template ->
            val types = template.attributes.groupBy { it.type }
            types.keys.all { it in FITTING_TYPES } &&
                types[CreateWizard.Attribute.HOSTNAME]?.size == 1 &&
                types[CreateWizard.Attribute.PASSWORD]?.size == 1 &&
                types[CreateWizard.Attribute.STRING].orEmpty().count { it.min > 0 } <= 1
        }

    private fun create(
        template: CreateWizard.Template,
        url: String,
        id: String,
        password: String,
    ): Create {
        val strings = template.attributes.filter { it.type == CreateWizard.Attribute.STRING }
        val account =
            strings.firstOrNull { it.min > 0 }
                ?: strings.firstOrNull { it.name.lowercase() in ACCOUNT_KEYS }
                ?: strings.firstOrNull()
        val answers =
            buildMap<String, CreateWizard.Answer> {
                template.attributes.forEach { attribute ->
                    when {
                        attribute.type == CreateWizard.Attribute.HOSTNAME ->
                            put(
                                attribute.name,
                                CreateWizard.Answer.Text(url),
                            )
                        attribute.type == CreateWizard.Attribute.PASSWORD ->
                            put(
                                attribute.name,
                                CreateWizard.Answer.Text(password),
                            )
                        attribute == account -> put(attribute.name, CreateWizard.Answer.Text(id))
                    }
                }
            }
        val created = CreateWizard.create(template, answers)
        return Create(created.name, created.text)
    }

    private fun plain(
        matches: List<CredentialMatcher.Match>,
        target: CredentialMatcher.Target?,
        id: String,
        password: String,
        url: String?,
    ): Create {
        val account = id.ifBlank { "login" }.replace('/', '_')
        val site =
            matches.firstOrNull()?.item?.folder ?: when (target) {
                is CredentialMatcher.Target.Web ->
                    target.host
                        .substringAfter("://")
                        .substringBefore('/')
                        .removePrefix("www.")
                is CredentialMatcher.Target.App -> target.packageName
                null -> "unsorted"
            }
        val fields =
            buildList {
                if (id.isNotBlank()) add(PassEntry.Field("username", id))
                url?.let { add(PassEntry.Field("url", it)) }
            }
        return Create("$site/$account", PassEntry(password, fields, null, "").format())
    }

    private val FITTING_TYPES =
        setOf(
            CreateWizard.Attribute.HOSTNAME,
            CreateWizard.Attribute.PASSWORD,
            CreateWizard.Attribute.STRING,
            CreateWizard.Attribute.MULTILINE,
        )

    private val ACCOUNT_KEYS = setOf("username", "user", "login", "email")
}
