package me.parham1995.passwords.data.share

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import kage.Age
import kage.crypto.x25519.X25519Identity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.parham1995.passwords.data.Settings
import me.parham1995.passwords.data.security.SecretBox
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64

class ShareRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var now = 1_000_000L
    private val requests = mutableListOf<GitHubTransport.Request>()
    private val responses = ArrayDeque<GitHubTransport.Response>()
    private var unreachable = false

    private val transport =
        GitHubTransport { request ->
            requests += request
            if (unreachable) throw IOException("no route to host")
            responses.removeFirst()
        }

    private val clearBox =
        object : SecretBox {
            override fun seal(plaintext: ByteArray) = plaintext.copyOf()

            override fun open(sealed: ByteArray) = sealed.copyOf()
        }

    private val settings =
        Settings(
            shareBaseUrl = "https://example.com/share",
            shareRepository = "owner/share",
            shareBranch = "pages",
            sharePrefix = "s",
        )
    private val token by lazy { ShareTokenStore(tmp.root.resolve("share/token.sealed"), clearBox) }
    private val repository by lazy {
        ShareRepository(
            settings = { settings },
            token = token,
            history = PreferenceDataStoreFactory.create(scope = scope) { tmp.root.resolve("shares.preferences_pb") },
            transport = transport,
            clock = { now },
            io = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() = scope.cancel()

    private fun ok(body: String = "{}") = GitHubTransport.Response(200, body)

    private fun json(body: String?) = Json.parseToJsonElement(body!!).jsonObject

    @Test
    fun `share pushes the age file the page expects and returns the link`() =
        runBlocking {
            token.save("github_pat_TEST".toCharArray())
            responses += GitHubTransport.Response(201, "{}")

            val made = repository.share("web/example.com", ShareRepository.Kind.PASSWORD, "hunter2".toByteArray())

            val request = requests.single()
            val id = made.share.id
            assertThat(request.method).isEqualTo("PUT")
            assertThat(request.url).isEqualTo("https://api.github.com/repos/owner/share/contents/s/$id.age")
            assertThat(request.headers).containsEntry("Authorization", "Bearer github_pat_TEST")
            assertThat(request.headers).containsEntry("Accept", "application/vnd.github+json")
            assertThat(request.headers).containsEntry("X-GitHub-Api-Version", "2022-11-28")
            val body = json(request.body)
            assertThat(body["message"]!!.jsonPrimitive.content).isEqualTo("share: add $id")
            assertThat(body["branch"]!!.jsonPrimitive.content).isEqualTo("pages")

            val link = String(made.link)
            assertThat(link).matches("https://example\\.com/share/#$id\\.AGE-SECRET-KEY-1[0-9A-Z]{58}")
            val ciphertext = Base64.getDecoder().decode(body["content"]!!.jsonPrimitive.content)
            val out = ByteArrayOutputStream()
            Age.decryptStream(listOf(X25519Identity.decode(link.substringAfter("$id."))), ciphertext.inputStream(), out)
            assertThat(out.toString()).isEqualTo("hunter2")

            assertThat(repository.shares.first().single())
                .isEqualTo(ShareRepository.Share(id, "web/example.com", ShareRepository.Kind.PASSWORD, now))
        }

    @Test
    fun `the list never holds a key`() =
        runBlocking {
            token.save("t".toCharArray())
            responses += ok()
            val made = repository.share("a", ShareRepository.Kind.ENTRY, "x".toByteArray())
            val stored = ShareRepository.encode(repository.shares.first())
            assertThat(stored).doesNotContain("AGE-SECRET-KEY")
            assertThat(stored).doesNotContain(String(made.link).substringAfter("#").substringAfter("."))
        }

    @Test
    fun `revoke looks up the sha and deletes the file`() =
        runBlocking {
            token.save("t".toCharArray())
            responses += ok()
            val id = repository.share("a", ShareRepository.Kind.ENTRY, "x".toByteArray()).share.id
            requests.clear()
            responses += ok("""{"sha":"abc123","name":"$id.age"}""")
            responses += ok()

            repository.revoke(id)

            val (get, delete) = requests
            assertThat(get.method).isEqualTo("GET")
            assertThat(get.url).isEqualTo("https://api.github.com/repos/owner/share/contents/s/$id.age?ref=pages")
            assertThat(delete.method).isEqualTo("DELETE")
            assertThat(delete.url).isEqualTo("https://api.github.com/repos/owner/share/contents/s/$id.age")
            val body = json(delete.body)
            assertThat(body["message"]!!.jsonPrimitive.content).isEqualTo("share: revoke $id")
            assertThat(body["sha"]!!.jsonPrimitive.content).isEqualTo("abc123")
            assertThat(body["branch"]!!.jsonPrimitive.content).isEqualTo("pages")
            assertThat(repository.shares.first()).isEmpty()
        }

    @Test
    fun `revoking a share the server already expired just forgets it`() =
        runBlocking {
            token.save("t".toCharArray())
            responses += ok()
            val id = repository.share("a", ShareRepository.Kind.ENTRY, "x".toByteArray()).share.id
            responses += GitHubTransport.Response(404, """{"message":"Not Found"}""")

            repository.revoke(id)

            assertThat(requests.last().method).isEqualTo("GET")
            assertThat(repository.shares.first()).isEmpty()
        }

    @Test
    fun `shares older than seven days drop off the list`() =
        runBlocking {
            token.save("t".toCharArray())
            responses += ok()
            repository.share("a", ShareRepository.Kind.ENTRY, "x".toByteArray())
            now += ShareRepository.MAX_AGE_MILLIS - 1
            assertThat(repository.shares.first()).hasSize(1)
            now += 1
            assertThat(repository.shares.first()).isEmpty()
        }

    @Test
    fun `errors are worded for a person`() =
        runBlocking {
            token.save("t".toCharArray())
            val cases =
                mapOf(
                    401 to "refused the token",
                    403 to "Contents: read and write",
                    404 to "owner/share or its branch pages was not found",
                    409 to "conflict",
                    422 to "conflict",
                    500 to "GitHub answered 500",
                )
            cases.forEach { (code, wording) ->
                responses += GitHubTransport.Response(code, """{"message":"nope"}""")
                val error =
                    assertThrows(ShareException::class.java) {
                        runBlocking { repository.share("a", ShareRepository.Kind.ENTRY, "x".toByteArray()) }
                    }
                assertThat(error.message).contains(wording)
            }
            assertThat(repository.shares.first()).isEmpty()
        }

    @Test
    fun `a network failure is reported, and nothing is recorded`() =
        runBlocking {
            token.save("t".toCharArray())
            unreachable = true
            val error =
                assertThrows(ShareException::class.java) {
                    runBlocking { repository.share("a", ShareRepository.Kind.ENTRY, "x".toByteArray()) }
                }
            assertThat(error.message).contains("Could not reach GitHub")
            assertThat(repository.shares.first()).isEmpty()
        }

    @Test
    fun `nothing is sent without a token`() {
        val error =
            assertThrows(ShareException::class.java) {
                runBlocking { repository.share("a", ShareRepository.Kind.ENTRY, "x".toByteArray()) }
            }
        assertThat(error.message).contains("token")
        assertThat(requests).isEmpty()
    }

    @Test
    fun `test reports push permission`() =
        runBlocking {
            token.save("t".toCharArray())
            responses += ok("""{"full_name":"owner/share","permissions":{"admin":false,"push":true,"pull":true}}""")

            val access = repository.test()

            assertThat(requests.single().url).isEqualTo("https://api.github.com/repos/owner/share")
            assertThat(access).isEqualTo(ShareRepository.Access("owner/share", push = true))
        }

    @Test
    fun `test without permissions in the answer says so`() =
        runBlocking {
            token.save("t".toCharArray())
            responses += ok("""{"full_name":"owner/share"}""")
            assertThat(repository.test().push).isNull()
        }

    @Test
    fun `the token is sealed, never kept in the clear by the store`() {
        token.save("github_pat_SECRET".toCharArray())
        assertThat(token.saved.value).isTrue()
        assertThat(token.open()!!.concatToString()).isEqualTo("github_pat_SECRET")
        token.delete()
        assertThat(token.saved.value).isFalse()
        assertThat(token.open()).isNull()
    }

    @Test
    fun `addresses and folders are normalised`() {
        assertThat(
            ShareRepository.normalizeBaseUrl(" https://example.com/share "),
        ).isEqualTo("https://example.com/share/")
        assertThat(
            ShareRepository.normalizeBaseUrl("https://example.com/share/"),
        ).isEqualTo("https://example.com/share/")
        assertThat(
            ShareRepository.normalizeBaseUrl("https://example.com/index.html"),
        ).isEqualTo("https://example.com/index.html")
        assertThat(ShareRepository.normalizePrefix("/s/")).isEqualTo("s/")
        assertThat(ShareRepository.normalizePrefix("")).isEqualTo("")
    }

    @Test
    fun `the list survives its own encoding, names with spaces included`() {
        val shares =
            listOf(
                ShareRepository.Share(
                    "0123456789abcdef0123456789abcdef",
                    "web/my bank",
                    ShareRepository.Kind.ENTRY,
                    42,
                ),
                ShareRepository.Share(
                    "fedcba9876543210fedcba9876543210",
                    "wifi/home",
                    ShareRepository.Kind.PASSWORD,
                    43,
                ),
            )
        assertThat(ShareRepository.decode(ShareRepository.encode(shares))).isEqualTo(shares)
    }
}
