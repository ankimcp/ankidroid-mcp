package ai.ankimcp.ankidroid.local

import ai.ankimcp.ankidroid.auth.BlobStorage
import ai.ankimcp.ankidroid.auth.CredentialCipher
import ai.ankimcp.ankidroid.auth.EncryptedBlob
import ai.ankimcp.ankidroid.log.TunnelLog
import ai.ankimcp.ankidroid.settings.InMemoryKeyValueStore
import ai.ankimcp.ankidroid.settings.LocalServerSettings
import ai.ankimcp.ankidroid.tunnel.ElapsedClock
import ai.ankimcp.ankidroid.tunnel.JsonRpcHandler
import ai.ankimcp.ankidroid.tunnel.WakeLocks
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.BindException
import java.security.KeyStoreException

@OptIn(ExperimentalCoroutinesApi::class)
class LocalServerTest {
    private class FakeWifi : WifiAddressSource {
        override val address = MutableStateFlow<WifiAddress>(WifiAddress.None)
        var started = 0
        var stopped = 0

        fun set(host: String?) {
            address.value = host?.let { WifiAddress.Usable(it) } ?: WifiAddress.None
        }

        override fun start() {
            started++
        }

        override fun stop() {
            stopped++
        }
    }

    private class FakeEngine(private val failWith: IOException?) : HttpServerEngine {
        var boundTo: Pair<String, Int>? = null
        var stopped = false
        var handler: (suspend (HttpRequest) -> HttpResponse)? = null
        var screen: ((HttpRequest) -> HttpResponse?)? = null

        override fun start(
            host: String,
            port: Int,
            screen: (HttpRequest) -> HttpResponse?,
            handler: suspend (HttpRequest) -> HttpResponse,
        ) {
            failWith?.let { throw it }
            boundTo = host to port
            this.handler = handler
            this.screen = screen
        }

        override fun stop() {
            stopped = true
        }
    }

    private class MemoryStorage : BlobStorage {
        var blob: EncryptedBlob? = null
        override fun read() = blob
        override fun write(blob: EncryptedBlob) {
            this.blob = blob
        }
        override fun clear() {
            blob = null
        }
    }

    private class PlainCipher(var failWith: Exception? = null, var encryptFailWith: Exception? = null) : CredentialCipher {
        override fun encrypt(plaintext: ByteArray): EncryptedBlob {
            encryptFailWith?.let { throw it }
            return EncryptedBlob(byteArrayOf(0), plaintext)
        }

        override fun decrypt(blob: EncryptedBlob): ByteArray {
            failWith?.let { throw it }
            return blob.ciphertext
        }
    }

    private val log = TunnelLog(mirrorToLogcat = false)
    /** Most tests run with "Require access token" on; the open default has its own test. */
    private val settings = LocalServerSettings(InMemoryKeyValueStore()).apply {
        setEnabled(true)
        setRequireToken(true)
    }
    private val wifi = FakeWifi()
    private val engines = mutableListOf<FakeEngine>()
    private var nextBindFailure: IOException? = null

    /** Failures for the next engines, in order, after [nextBindFailure]. */
    private val bindFailures = ArrayDeque<IOException>()
    private val storage = MemoryStorage()
    private val cipher = PlainCipher()
    private val tokens = LocalServerTokenStore(storage, cipher, log::warn)

    private fun TestScope.server(tokenStore: LocalServerTokenStore = tokens): LocalServer {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return LocalServer(
            scope = backgroundScope,
            settings = settings,
            tokens = tokenStore,
            wifi = wifi,
            engineFactory = {
                FakeEngine(nextBindFailure ?: bindFailures.removeFirstOrNull()).also {
                    nextBindFailure = null
                    engines += it
                }
            },
            handler = JsonRpcHandler { _, _ -> """{"jsonrpc":"2.0","id":1,"result":{}}""" },
            log = log,
            wakeLocks = WakeLocks { _, _ -> AutoCloseable {} },
            clock = ElapsedClock { 0L },
            ioDispatcher = dispatcher,
        )
    }

    private val StateFlow<LocalServerState>.running get() = value as LocalServerState.Running

    @Test
    fun `waits for Wi-Fi, then binds the Wi-Fi address on the configured port`() = runTest {
        val server = server()
        server.start()
        runCurrent()

        assertEquals(LocalServerState.NoWifi, server.state.value)
        assertTrue(engines.isEmpty())
        assertEquals(1, wifi.started)
        assertTrue(tokens.token.value != null)

        wifi.set("192.168.1.20")
        runCurrent()

        assertEquals("192.168.1.20" to 8765, engines.single().boundTo)
        assertEquals("http://192.168.1.20:8765/mcp", server.state.running.url)
        assertTrue(log.entries.value.any { it.message == "Local server listening on 192.168.1.20:8765" })
    }

    @Test
    fun `with the open default no token is created and requests need none, but Host is still checked`() = runTest {
        settings.setRequireToken(false)
        wifi.set("192.168.1.20")
        val server = server()
        server.start()
        runCurrent()

        assertNull(tokens.token.value)
        assertNull(storage.blob)
        val engine = engines.single()
        val open = HttpRequest("POST", "/mcp", mapOf("host" to "192.168.1.20:8765"), """{"jsonrpc":"2.0","id":1,"method":"ping"}""".toByteArray())
        assertNull(engine.screen!!(open))
        assertEquals(200, engine.handler!!(open).status)
        val rebound = HttpRequest("POST", "/mcp", mapOf("host" to "evil.example:8765"), ByteArray(0))
        assertEquals(403, engine.screen!!(rebound)!!.status)

        // Turning the switch on applies to the next request, no restart.
        settings.setRequireToken(true)
        tokens.getOrCreate()
        assertEquals(403, engine.handler!!(open).status)
    }

    @Test
    fun `a Wi-Fi with only a public address keeps the server off`() = runTest {
        wifi.set("192.168.1.20")
        val server = server()
        server.start()
        runCurrent()

        wifi.address.value = WifiAddress.PublicOnly
        runCurrent()

        assertTrue(engines.single().stopped)
        assertEquals(LocalServerState.PublicAddress, server.state.value)
        assertEquals(1, engines.size)
    }

    @Test
    fun `rebinds when the address or port changes and closes the old socket`() = runTest {
        wifi.set("192.168.1.20")
        val server = server()
        server.start()
        runCurrent()

        wifi.set("10.0.0.5")
        runCurrent()
        assertTrue(engines[0].stopped)
        assertEquals("10.0.0.5" to 8765, engines[1].boundTo)

        settings.setPort(9000)
        runCurrent()
        assertTrue(engines[1].stopped)
        assertEquals("10.0.0.5" to 9000, engines[2].boundTo)

        wifi.set(null)
        runCurrent()
        assertTrue(engines[2].stopped)
        assertEquals(LocalServerState.NoWifi, server.state.value)
    }

    @Test
    fun `requests are checked against the bound address and the stored token, also before the body`() = runTest {
        wifi.set("192.168.1.20")
        val server = server()
        server.start()
        runCurrent()
        val engine = engines.single()
        fun request(auth: String) = HttpRequest(
            "POST",
            "/mcp",
            mapOf("host" to "192.168.1.20:8765", "authorization" to auth),
            """{"jsonrpc":"2.0","id":1,"method":"ping"}""".toByteArray(),
        )

        assertEquals(200, engine.handler!!(request("Bearer ${tokens.token.value}")).status)
        assertEquals(403, engine.handler!!(request("Bearer nope")).status)
        assertEquals(403, engine.screen!!(request("Bearer nope"))!!.status)
        assertNull(engine.screen!!(request("Bearer ${tokens.token.value}")))

        assertTrue(server.regenerateToken())
        assertEquals(200, engine.handler!!(request("Bearer ${tokens.token.value}")).status)
    }

    @Test
    fun `a port in use is reported, and a later change retries`() = runTest {
        wifi.set("192.168.1.20")
        nextBindFailure = BindException("Address already in use")
        val server = server()
        server.start()
        runCurrent()

        assertEquals(LocalServerState.Failed("Port 8765 is in use. Pick another port."), server.state.value)

        settings.setPort(8766)
        runCurrent()
        assertEquals(8766, server.state.running.port)
    }

    @Test
    fun `an address that vanished before the bind means waiting for Wi-Fi, then the next address binds`() = runTest {
        wifi.set("192.168.1.20")
        nextBindFailure = BindException("Cannot assign requested address")
        val server = server()
        server.start()
        runCurrent()

        assertEquals(LocalServerState.NoWifi, server.state.value)

        wifi.set(null)
        runCurrent()
        wifi.set("192.168.1.21")
        runCurrent()
        assertEquals("192.168.1.21", server.state.running.host)
        assertTrue(LocalServer.isAddressGone(BindException("bind failed: EADDRNOTAVAIL (Cannot assign requested address)")))
        assertFalse(LocalServer.isAddressGone(BindException("Address already in use")))
    }

    private fun addressGone() = BindException("Cannot assign requested address")

    @Test
    fun `after address-gone the same address is re-checked and bound, without a monitor change`() = runTest {
        wifi.set("192.168.1.20")
        nextBindFailure = addressGone()
        val server = server()
        server.start()
        runCurrent()
        assertEquals(LocalServerState.NoWifi, server.state.value)

        advanceTimeBy(1_999)
        assertEquals(1, engines.size)
        advanceTimeBy(1)
        runCurrent()

        assertEquals(2, engines.size)
        assertEquals("192.168.1.20", server.state.running.host)
    }

    @Test
    fun `address-gone retries stop after three tries`() = runTest {
        wifi.set("192.168.1.20")
        repeat(4) { bindFailures.addLast(addressGone()) }
        val server = server()
        server.start()
        runCurrent()

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2, engines.size)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(3, engines.size)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(4, engines.size)

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(4, engines.size)
        assertEquals(LocalServerState.NoWifi, server.state.value)
    }

    @Test
    fun `address-gone retries end on a new monitor value or on stop`() = runTest {
        wifi.set("192.168.1.20")
        nextBindFailure = addressGone()
        val server = server()
        server.start()
        runCurrent()

        wifi.set("192.168.1.21")
        runCurrent()
        assertEquals("192.168.1.21", server.state.running.host)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(2, engines.size)

        // A retry pending when the server stops never runs.
        nextBindFailure = addressGone()
        wifi.set("192.168.1.22")
        runCurrent()
        assertEquals(3, engines.size)
        server.stop()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(3, engines.size)
        assertEquals(LocalServerState.Off, server.state.value)
    }

    @Test
    fun `stop is idempotent and also clears a failed start whose run already ended`() = runTest {
        val failing = LocalServerTokenStore(MemoryStorage(), PlainCipher(encryptFailWith = KeyStoreException("boom")), log::warn)
        val server = server(failing)
        server.start()
        runCurrent()
        assertTrue(server.state.value is LocalServerState.Failed)
        assertFalse(server.isStarted)

        // The service calls stop() whenever the switch goes off, without checking isStarted.
        server.stop()
        server.stop()
        runCurrent()

        assertEquals(LocalServerState.Off, server.state.value)
    }

    @Test
    fun `a failure does not survive turning the server off and on`() = runTest {
        wifi.set(null)
        nextBindFailure = BindException("Address already in use")
        val server = server()
        server.start()
        runCurrent()
        wifi.set("192.168.1.20")
        runCurrent()
        assertTrue(server.state.value is LocalServerState.Failed)

        server.stop()
        runCurrent()
        wifi.set(null)
        server.start()
        assertEquals(LocalServerState.Off, server.state.value)
        runCurrent()
        assertEquals(LocalServerState.NoWifi, server.state.value)
    }

    @Test
    fun `stop closes the socket, stops watching Wi-Fi and reports Off`() = runTest {
        wifi.set("192.168.1.20")
        val server = server()
        server.start()
        runCurrent()

        server.stop()
        runCurrent()

        assertTrue(engines.single().stopped)
        assertEquals(1, wifi.stopped)
        assertEquals(LocalServerState.Off, server.state.value)
        assertFalse(server.isStarted)
        assertTrue(log.entries.value.any { it.message == "Local server stopped" })

        // Address changes after stop do nothing.
        wifi.set("10.0.0.5")
        runCurrent()
        assertEquals(1, engines.size)
    }

    @Test
    fun `start is a no-op when the setting is off or already started`() = runTest {
        wifi.set("192.168.1.20")
        val server = server()
        settings.setEnabled(false)
        server.start()
        runCurrent()
        assertEquals(LocalServerState.Off, server.state.value)
        assertEquals(0, wifi.started)

        settings.setEnabled(true)
        server.start()
        server.start()
        runCurrent()
        assertEquals(1, engines.size)
        assertEquals(1, wifi.started)
    }

    @Test
    fun `stop then start in a row ends up running`() = runTest {
        wifi.set("192.168.1.20")
        val server = server()
        server.start()
        runCurrent()

        server.stop()
        server.start()
        runCurrent()

        assertTrue(engines[0].stopped)
        assertFalse(engines[1].stopped)
        assertEquals("192.168.1.20", server.state.running.host)
    }

    @Test
    fun `a token that cannot be read now keeps the server from starting and stops the Wi-Fi watch`() = runTest {
        tokens.regenerate()
        val stuck = LocalServerTokenStore(storage, PlainCipher(failWith = IllegalStateException("Keystore busy")), log::warn)
        wifi.set("192.168.1.20")
        val server = server(stuck)
        server.start()
        runCurrent()

        assertTrue(server.state.value is LocalServerState.Failed)
        assertTrue(engines.isEmpty())
        assertNull(stuck.token.value)
        assertEquals(1, wifi.stopped)
    }

    @Test
    fun `a Keystore failure creating the first token is a Failed state, not a crash`() = runTest {
        val failing = LocalServerTokenStore(MemoryStorage(), PlainCipher(encryptFailWith = KeyStoreException("boom")), log::warn)
        wifi.set("192.168.1.20")
        val server = server(failing)
        server.start()
        runCurrent()

        val state = server.state.value as LocalServerState.Failed
        assertTrue(state.reason, state.reason.contains("access token"))
        assertTrue(engines.isEmpty())
        assertEquals(1, wifi.stopped)
        assertTrue(log.entries.value.any { it.message == "Could not store a new local server token: KeyStoreException" })
    }

    @Test
    fun `a failed regenerate keeps the old token`() = runTest {
        wifi.set("192.168.1.20")
        val server = server()
        server.start()
        runCurrent()
        val old = tokens.token.value

        cipher.encryptFailWith = KeyStoreException("boom")
        assertFalse(server.regenerateToken())

        assertEquals(old, tokens.token.value)
        assertTrue(tokens.saveError.value != null)
        assertTrue(server.state.value is LocalServerState.Running)
    }
}
