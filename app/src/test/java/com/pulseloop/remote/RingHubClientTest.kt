package com.pulseloop.remote

import android.content.SharedPreferences
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RingHubClientTest {

    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    private fun client(token: String = "secret") = RingHubClient(server.url("/").toString(), token)

    private fun batch() = RingHubBatch(exportedAt = 1, appVersion = "t", deviceId = "ring-1")

    @Test
    fun `batch is posted with the bearer token to the ingest path`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true,"ingestId":7,"deviceId":"ring-1","counts":{"measurements":{"received":1,"inserted":1}},"watermark":{"ingestId":7,"serverTime":"x","latest":{}}}"""))
        val result = client().postBatch(batch())

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/ingest/batch", request.path)
        assertEquals("Bearer secret", request.getHeader("Authorization"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        assertTrue(request.body.readUtf8().contains("\"deviceId\":\"ring-1\""))
        assertEquals(7L, result.ingestId)
        assertEquals(1, result.counts["measurements"]!!.inserted)
    }

    @Test
    fun `health needs no token, sync state sends it`() = runTest {
        server.enqueue(MockResponse().setBody("""{"status":"ok","version":"0.1.0"}"""))
        server.enqueue(MockResponse().setBody("""{"ingestId":3,"serverTime":"2026-10-24T10:00:00Z","latest":{"measurements":1792485016000,"sleepSessions":null}}"""))
        val c = client()
        assertEquals("0.1.0", c.health().version)
        val state = c.syncState()
        assertNull(server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer secret", server.takeRequest().getHeader("Authorization"))
        assertEquals(1_792_485_016_000L, state.latest["measurements"])
        assertNull(state.latest["sleepSessions"])
    }

    @Test
    fun `http failures map to retryable and final kinds`() = runTest {
        suspend fun failure(code: Int): RingHubException {
            server.enqueue(MockResponse().setResponseCode(code).setBody("""{"detail":"x"}"""))
            return runCatching { client().postBatch(batch()) }.exceptionOrNull() as RingHubException
        }
        assertEquals(RingHubException.Kind.UNAUTHORIZED, failure(401).kind)
        assertFalse(failure(401).retryable)
        assertTrue(failure(503).retryable)
        assertTrue(failure(500).retryable)
        assertEquals(RingHubException.Kind.REJECTED, failure(422).kind)
        assertFalse(failure(422).retryable)
        // A redirect is never followed: the token stays on the validated host.
        assertEquals(RingHubException.Kind.REJECTED, failure(307).kind)
    }

    @Test
    fun `a server that is off is unreachable and retryable`() = runTest {
        val url = server.url("/").toString()
        server.shutdown()
        val e = runCatching { RingHubClient(url, "t").postBatch(batch()) }.exceptionOrNull() as RingHubException
        assertEquals(RingHubException.Kind.UNREACHABLE, e.kind)
        assertTrue(e.retryable)
        server = MockWebServer().apply { start() }  // for @After
    }

    @Test
    fun `cleartext only to LAN and Tailscale hosts`() {
        assertNull(RingHubClient.validate("http://pc-casa:8765"))          // MagicDNS short name
        assertNull(RingHubClient.validate("http://pc-casa.tail1234.ts.net:8765"))
        assertNull(RingHubClient.validate("100.101.102.103:8765"))          // Tailscale CGNAT
        assertNull(RingHubClient.validate("http://192.168.1.20:8765"))
        assertNull(RingHubClient.validate("https://ringhub.example.com"))
        assertEquals(RingHubClient.Companion.Problem.PUBLIC_CLEARTEXT, RingHubClient.validate("http://ringhub.example.com"))
        assertEquals(RingHubClient.Companion.Problem.PUBLIC_CLEARTEXT, RingHubClient.validate("http://8.8.8.8:8765"))
        assertEquals(RingHubClient.Companion.Problem.UNSUPPORTED_SCHEME, RingHubClient.validate("ftp://pc-casa"))
        assertEquals(RingHubClient.Companion.Problem.BLANK, RingHubClient.validate("  "))
        assertEquals("http://pc-casa:8765", RingHubClient.normalize(" pc-casa:8765/ "))
    }

    // MARK: prefs store

    private class FakeSharedPreferences : SharedPreferences {
        val map = HashMap<String, Any>()
        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor(this)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class Editor(private val prefs: FakeSharedPreferences) : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor { if (value != null) prefs.map[key!!] = value; return this }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
            override fun remove(key: String?): SharedPreferences.Editor { prefs.map.remove(key); return this }
            override fun clear(): SharedPreferences.Editor { prefs.map.clear(); return this }
            override fun commit(): Boolean = true
            override fun apply() {}
        }
    }

    @Test
    fun `watermarks only move forward and survive a reload`() {
        val backing = FakeSharedPreferences()
        val store = RingHubPrefsStore(backing)
        assertNull(store.watermark(RingHubStream.MEASUREMENTS))
        store.advance(RingHubStream.MEASUREMENTS, 2_000)
        store.advance(RingHubStream.MEASUREMENTS, 1_000)
        store.advance(RingHubStream.SLEEP_SESSIONS, 500)
        assertEquals(2_000L, store.watermark(RingHubStream.MEASUREMENTS))

        val reloaded = RingHubPrefsStore(backing)
        assertEquals(2_000L, reloaded.watermark(RingHubStream.MEASUREMENTS))
        assertEquals(500L, reloaded.watermark(RingHubStream.SLEEP_SESSIONS))
        reloaded.update { it.copy(enabled = true, serverUrl = "http://pc-casa:8765") }
        assertEquals("http://pc-casa:8765", RingHubPrefsStore(backing).current.serverUrl)

        reloaded.clearWatermarks()
        assertNull(RingHubPrefsStore(backing).watermark(RingHubStream.MEASUREMENTS))
    }
}
