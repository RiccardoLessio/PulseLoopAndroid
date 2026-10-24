package com.pulseloop.remote

import com.pulseloop.coach.local.LocalEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/** Why a ring-hub request failed. [retryable] failures leave the data queued for a later pass. */
class RingHubException(val kind: Kind, val httpCode: Int?, message: String) : Exception(message) {
    enum class Kind { UNREACHABLE, UNAUTHORIZED, SERVER, REJECTED }

    val retryable: Boolean get() = kind == Kind.UNREACHABLE || kind == Kind.SERVER
}

/**
 * HTTP client for a self-hosted ring-hub (ringhub/api.py).
 *
 * The base URL is validated by [validate]: plain `http://` only for a host that cannot be on the
 * public internet (loopback, RFC1918, Tailscale's 100.64.0.0/10, `*.ts.net`, a bare LAN hostname),
 * the same rule as the local coach provider ([LocalEndpoint.isPrivateHost]). Redirects are off, so
 * a validated LAN host can't bounce the bearer token and health data to a public `http://` one.
 */
class RingHubClient(
    baseUrl: String,
    private val token: String,
    private val http: OkHttpClient = defaultHttp,
) {
    private val base: String = normalize(baseUrl)
        ?: throw IllegalArgumentException("Invalid ring-hub URL")

    suspend fun health(): RingHubHealth =
        ringHubJson.decodeFromString(RingHubHealth.serializer(), execute(get("/api/v1/health", auth = false)))

    suspend fun syncState(): RingHubServerState =
        ringHubJson.decodeFromString(RingHubServerState.serializer(), execute(get("/api/v1/sync/state", auth = true)))

    /** Every zone on the server, deleted ones included (zones promoted in the dashboard come back this way). */
    suspend fun zones(): List<ZoneDTO> =
        ringHubJson.decodeFromString(ListSerializer(ZoneDTO.serializer()), execute(get("/api/v1/zones", auth = true)))

    /** The places ring-hub detected automatically, most visited first. */
    suspend fun places(): List<com.pulseloop.context.AutoPlace> =
        ringHubJson.decodeFromString(
            ListSerializer(com.pulseloop.context.AutoPlace.serializer()),
            execute(get("/api/v1/places", auth = true)),
        )

    suspend fun postBatch(batch: RingHubBatch): RingHubIngestResult {
        val body = ringHubJson.encodeToString(RingHubBatch.serializer(), batch)
            .toRequestBody(JSON)
        val request = Request.Builder().url("$base/api/v1/ingest/batch")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        return ringHubJson.decodeFromString(RingHubIngestResult.serializer(), execute(request))
    }

    private fun get(path: String, auth: Boolean): Request = Request.Builder().url("$base$path").apply {
        if (auth) header("Authorization", "Bearer $token")
    }.get().build()

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        val (code, text) = try {
            http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
        } catch (e: IOException) {
            throw RingHubException(RingHubException.Kind.UNREACHABLE, null, e.message ?: e.javaClass.simpleName)
        }
        if (code in 200..299) return@withContext text
        val kind = when (code) {
            401, 403 -> RingHubException.Kind.UNAUTHORIZED
            in 500..599 -> RingHubException.Kind.SERVER
            // Request timeout / rate limit: transient, treat like an outage.
            408, 429 -> RingHubException.Kind.SERVER
            else -> RingHubException.Kind.REJECTED
        }
        throw RingHubException(kind, code, "HTTP $code: ${text.take(300)}")
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        private val defaultHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }

        /** Why a base URL can't be used. */
        enum class Problem { BLANK, MALFORMED, UNSUPPORTED_SCHEME, PUBLIC_CLEARTEXT }

        /**
         * `scheme://host[:port][/path]` with no trailing slash; a bare `host:port` is taken as
         * `http://`. Null when it doesn't parse into a scheme and host.
         */
        fun normalize(raw: String): String? {
            var text = raw.trim()
            if (text.isEmpty()) return null
            if (!text.contains("://")) text = "http://$text"
            val uri = try { URI(text) } catch (_: Exception) { return null }
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.takeIf { it.isNotEmpty() } ?: return null
            val port = if (uri.port >= 0) ":${uri.port}" else ""
            val path = (uri.path ?: "").trimEnd('/')
            return "$scheme://$host$port$path"
        }

        fun validate(raw: String): Problem? {
            if (raw.isBlank()) return Problem.BLANK
            val normalized = normalize(raw) ?: return Problem.MALFORMED
            val uri = try { URI(normalized) } catch (_: Exception) { return Problem.MALFORMED }
            val host = uri.host?.lowercase() ?: return Problem.MALFORMED
            return when (uri.scheme?.lowercase()) {
                "https" -> null
                "http" -> if (LocalEndpoint.isPrivateHost(host)) null else Problem.PUBLIC_CLEARTEXT
                else -> Problem.UNSUPPORTED_SCHEME
            }
        }

        fun message(problem: Problem): String = when (problem) {
            Problem.BLANK -> "Inserisci l'indirizzo del server, es. http://pc-casa:8765"
            Problem.MALFORMED -> "Indirizzo non valido: usa host:porta o http://host:porta"
            Problem.UNSUPPORTED_SCHEME -> "Sono supportati solo http:// e https://"
            Problem.PUBLIC_CLEARTEXT ->
                "http:// è permesso solo verso la rete locale o Tailscale (IP privato, 100.x.y.z, " +
                    "nome senza dominio o *.ts.net). Per un server su Internet usa https://"
        }
    }
}
