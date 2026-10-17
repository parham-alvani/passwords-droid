package me.parham1995.passwords.data.share

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.parham1995.passwords.crypto.AgeShare
import me.parham1995.passwords.data.Settings
import java.io.IOException
import java.net.URLEncoder
import java.util.Base64

/** Why a share could not be made, revoked or tested, worded for the person holding the phone. */
class ShareException(
    message: String,
) : Exception(message)

/**
 * One-off share links, made the way a "share" static page on GitHub Pages
 * expects them: the secret is encrypted with age to a fresh key
 * ([AgeShare]), committed to `s/<id>.age` with the message
 * `share: add <id>`, and the link is `<base>#<id>.<AGE-SECRET-KEY-1…>`.
 * Revoking deletes the file with `share: revoke <id>`; the page's own
 * workflow deletes it after [MAX_AGE_MILLIS] otherwise.
 *
 * Files go up through GitHub's contents API with a fine-grained token, so
 * the page's repository needs no clone on the phone. Everything about where
 * the page lives is a setting; nothing is built in.
 *
 * The phone remembers which shares it made -- id, entry, what was shared,
 * when -- so they can be revoked. It never remembers a link's key: a link
 * is shown once, when it is made, and cannot be shown again.
 */
class ShareRepository(
    private val settings: suspend () -> Settings,
    private val token: ShareTokenStore,
    private val history: DataStore<Preferences>,
    private val transport: GitHubTransport = GitHubTransport.HTTP,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    enum class Kind(
        val label: String,
    ) {
        PASSWORD("password"),
        ENTRY("entry"),
    }

    /** A share made from this phone. The key is not here, on purpose. */
    data class Share(
        val id: String,
        val name: String,
        val kind: Kind,
        val createdMillis: Long,
    )

    /** A share just made: [link] holds the key, so it is shown once and then dropped. */
    class Made(
        val share: Share,
        val link: CharArray,
    )

    /** What `GET /repos/{owner}/{repo}` says about the token; [push] is null when GitHub did not say. */
    data class Access(
        val repository: String,
        val push: Boolean?,
    )

    /** Shares from this phone the server has not yet expired, newest first. */
    val shares: Flow<List<Share>> =
        history.data.map { prefs ->
            decode(prefs[SHARES]).filter { it.isLive() }.sortedByDescending { it.createdMillis }
        }

    /** Whether a token is stored; it is never shown again. */
    val tokenSaved: StateFlow<Boolean> get() = token.saved

    /** Whether the settings and a token are all there, so a Share action makes sense. */
    fun configured(settings: Flow<Settings>): Flow<Boolean> =
        combine(settings, token.saved) { current, saved ->
            saved && current.shareBaseUrl.isNotBlank() && current.shareRepository.isNotBlank()
        }

    /** Seals and stores [secret]; the caller wipes it. */
    suspend fun saveToken(secret: CharArray) = withContext(io) { token.save(secret) }

    suspend fun removeToken() = withContext(io) { token.delete() }

    /**
     * Encrypts [plain], pushes it, records it, and returns the link. The
     * caller wipes [plain]; the sealed identity is wiped here once the link
     * holds it.
     */
    suspend fun share(
        name: String,
        kind: Kind,
        plain: ByteArray,
    ): Made =
        withContext(io) {
            val target = target()
            val id = AgeShare.newId()
            val sealed = AgeShare.seal(plain)
            val link =
                try {
                    val body =
                        buildJsonObject {
                            put("message", "share: add $id")
                            put("content", Base64.getEncoder().encodeToString(sealed.ciphertext))
                            put("branch", target.branch)
                        }
                    call("PUT", target.contents(id), body.toString(), target)
                    "${target.baseUrl}#$id.".toCharArray() + sealed.identity
                } finally {
                    sealed.identity.fill('\u0000')
                }
            val share = Share(id, name, kind, clock())
            history.edit { prefs -> prefs[SHARES] = encode(decode(prefs[SHARES]).filter { it.isLive() } + share) }
            Made(share, link)
        }

    /**
     * Deletes the share's file and forgets it. A file already gone -- the
     * page's workflow expired it -- is forgotten without complaint.
     */
    suspend fun revoke(id: String) =
        withContext(io) {
            require(AgeShare.isId(id)) { "not a share id: $id" }
            val target = target()
            val url = target.contents(id)
            val sha =
                try {
                    json(call("GET", url + "?ref=" + target.branch.urlEncoded(), null, target, missingOk = true))["sha"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?: throw ShareException("GitHub did not say which version of the file to delete.")
                } catch (e: NotFound) {
                    null
                }
            if (sha != null) {
                val body =
                    buildJsonObject {
                        put("message", "share: revoke $id")
                        put("sha", sha)
                        put("branch", target.branch)
                    }
                try {
                    call("DELETE", url, body.toString(), target, missingOk = true)
                } catch (e: NotFound) {
                    // Gone between the two requests; the result is the same.
                }
            }
            forget(id)
        }

    /** Forgets [id] here without touching the server. */
    suspend fun forget(id: String) {
        history.edit { prefs -> prefs[SHARES] = encode(decode(prefs[SHARES]).filter { it.id != id && it.isLive() }) }
    }

    /** Forgets every share; for removing everything from the phone. */
    suspend fun forgetAll() {
        history.edit { it.remove(SHARES) }
    }

    /** Checks the token can see the repository, and whether it may push. */
    suspend fun test(): Access =
        withContext(io) {
            val target = target(requireBaseUrl = false)
            val repo = json(call("GET", "$API/repos/${target.repository}", null, target))
            Access(
                repository = repo["full_name"]?.jsonPrimitive?.contentOrNull ?: target.repository,
                push = (repo["permissions"] as? JsonObject)?.get("push")?.jsonPrimitive?.booleanOrNull,
            )
        }

    private class Target(
        val baseUrl: String,
        val repository: String,
        val branch: String,
        val prefix: String,
    ) {
        fun contents(id: String): String = "$API/repos/$repository/contents/$prefix$id.age"
    }

    private suspend fun target(requireBaseUrl: Boolean = true): Target {
        val current = settings()
        val baseUrl = normalizeBaseUrl(current.shareBaseUrl)
        val repository = current.shareRepository.trim()
        val branch = current.shareBranch.trim().ifEmpty { "main" }
        val prefix = normalizePrefix(current.sharePrefix)
        if (requireBaseUrl && !baseUrl.startsWith("https://")) {
            throw ShareException("Set the share page's address (https://…) in Settings → Sharing.")
        }
        if (!REPOSITORY.matches(repository)) {
            throw ShareException("Set the share page's GitHub repository as owner/name in Settings → Sharing.")
        }
        if (!PREFIX.matches(prefix)) throw ShareException("The share folder may hold only letters, digits and . _ - /")
        if (!token.saved.value) throw ShareException("Save a GitHub token in Settings → Sharing first.")
        return Target(baseUrl, repository, branch, prefix)
    }

    private class NotFound : Exception()

    /**
     * One authenticated request. The token is unsealed for the request and
     * wiped after; the header itself has to be a String, which is as far as
     * [java.net.HttpURLConnection] lets that go.
     */
    private fun call(
        method: String,
        url: String,
        body: String?,
        target: Target,
        missingOk: Boolean = false,
    ): String {
        val secret = token.open() ?: throw ShareException("Save a GitHub token in Settings → Sharing first.")
        val response =
            try {
                transport.send(
                    GitHubTransport.Request(
                        method = method,
                        url = url,
                        headers =
                            mapOf(
                                "Authorization" to "Bearer " + String(secret),
                                "Accept" to "application/vnd.github+json",
                                "X-GitHub-Api-Version" to API_VERSION,
                                "User-Agent" to "passwords-droid",
                            ),
                        body = body,
                    ),
                )
            } catch (e: IOException) {
                throw ShareException("Could not reach GitHub: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                secret.fill('\u0000')
            }
        if (response.code in 200..299) return response.body
        if (response.code == 404 && missingOk) throw NotFound()
        throw ShareException(describe(response, target))
    }

    private fun describe(
        response: GitHubTransport.Response,
        target: Target,
    ): String {
        val said = runCatching { json(response.body)["message"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        val detail = said?.let { " GitHub said: $it" }.orEmpty()
        return when (response.code) {
            401 -> "GitHub refused the token (401): it is wrong, expired or revoked."
            403 ->
                "The token may not write to ${target.repository} (403). " +
                    "Give it Contents: read and write on that repository.$detail"
            404 ->
                "${target.repository} or its branch ${target.branch} was not found (404). " +
                    "A fine-grained token sees only the repositories it was given."
            409, 422 -> "GitHub reported a conflict (${response.code}); try again.$detail"
            else -> "GitHub answered ${response.code}.$detail"
        }
    }

    private fun json(body: String): JsonObject =
        runCatching { Json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw ShareException("GitHub sent an answer this app does not understand.") }

    private fun Share.isLive(): Boolean = clock() - createdMillis < MAX_AGE_MILLIS

    companion object {
        /** The page's daily workflow deletes shares older than this. */
        const val MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000

        private const val API = "https://api.github.com"
        private const val API_VERSION = "2022-11-28"
        private val SHARES = stringPreferencesKey("shares")
        private val REPOSITORY = Regex("[A-Za-z0-9-]+/[A-Za-z0-9._-]+")
        private val PREFIX = Regex("([A-Za-z0-9._-]+/)*")

        /** A page address without a trailing slash would load through a redirect; links get one. */
        fun normalizeBaseUrl(url: String): String {
            val trimmed = url.trim().substringBefore('#')
            return if (trimmed.isEmpty() || trimmed.endsWith("/") || trimmed.endsWith(".html")) trimmed else "$trimmed/"
        }

        fun normalizePrefix(prefix: String): String {
            val trimmed = prefix.trim().trim('/')
            return if (trimmed.isEmpty()) "" else "$trimmed/"
        }

        private fun String.urlEncoded(): String = URLEncoder.encode(this, Charsets.UTF_8)

        /** One share per line: `id created kind name`; the name goes last since it may hold spaces. */
        internal fun encode(shares: List<Share>): String =
            shares.joinToString("\n") { "${it.id} ${it.createdMillis} ${it.kind.name} ${it.name.replace('\n', ' ')}" }

        internal fun decode(text: String?): List<Share> =
            text.orEmpty().lines().mapNotNull { line ->
                val parts = line.split(' ', limit = 4)
                if (parts.size < 4) return@mapNotNull null
                val created = parts[1].toLongOrNull() ?: return@mapNotNull null
                val kind = Kind.entries.firstOrNull { it.name == parts[2] } ?: return@mapNotNull null
                Share(parts[0], parts[3], kind, created).takeIf { AgeShare.isId(it.id) }
            }
    }
}
