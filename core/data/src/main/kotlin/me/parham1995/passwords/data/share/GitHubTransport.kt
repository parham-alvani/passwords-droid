package me.parham1995.passwords.data.share

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** One exchange with GitHub's REST API; an interface so tests need no network. */
fun interface GitHubTransport {
    /** Throws [IOException] when GitHub could not be reached; an HTTP error is a [Response]. */
    fun send(request: Request): Response

    class Request(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: String? = null,
    ) {
        /** Never the headers: one of them is the token. */
        override fun toString(): String = "$method $url"
    }

    data class Response(
        val code: Int,
        val body: String,
    )

    companion object {
        val HTTP =
            GitHubTransport { request ->
                val connection = URI(request.url).toURL().openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = request.method
                    connection.connectTimeout = TIMEOUT
                    connection.readTimeout = TIMEOUT
                    connection.useCaches = false
                    request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                    request.body?.let { body ->
                        connection.doOutput = true
                        connection.setRequestProperty("Content-Type", "application/json")
                        connection.outputStream.use { it.write(body.toByteArray()) }
                    }
                    val code = connection.responseCode
                    val failed = code >= HttpURLConnection.HTTP_BAD_REQUEST
                    val stream = if (failed) connection.errorStream else connection.inputStream
                    Response(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
                } finally {
                    connection.disconnect()
                }
            }

        private const val TIMEOUT = 20_000
    }
}
