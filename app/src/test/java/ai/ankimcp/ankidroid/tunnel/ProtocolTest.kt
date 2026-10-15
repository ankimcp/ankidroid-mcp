package ai.ankimcp.ankidroid.tunnel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test
    fun `parses tunnel_established, ignoring unknown fields`() {
        val msg = Protocol.parseServerMessage(
            """{"type":"tunnel_established","url":"https://tunnel.ankimcp.ai/mcp","expiresAt":null,"extra":1}""",
        )
        assertEquals(ServerMessage.TunnelEstablished("https://tunnel.ankimcp.ai/mcp"), msg)
    }

    @Test
    fun `parses request with and without body`() {
        val withBody = Protocol.parseServerMessage(
            """{"type":"request","requestId":"r1","method":"POST","path":"/mcp",
               "headers":{"content-type":"application/json"},"body":"{\"jsonrpc\":\"2.0\"}"}""",
        )
        assertEquals(
            ServerMessage.Request("r1", "POST", "/mcp", mapOf("content-type" to "application/json"), """{"jsonrpc":"2.0"}"""),
            withBody,
        )

        val withoutBody = Protocol.parseServerMessage(
            """{"type":"request","requestId":"r2","method":"GET","path":"/","headers":{}}""",
        ) as ServerMessage.Request
        assertNull(withoutBody.body)
    }

    @Test
    fun `parses ping`() {
        assertEquals(
            ServerMessage.Ping(1704063600000),
            Protocol.parseServerMessage("""{"type":"ping","timestamp":1704063600000}"""),
        )
    }

    @Test
    fun `parses error with and without details`() {
        val withDetails = Protocol.parseServerMessage(
            """{"type":"error","code":"invalid_message","message":"bad","details":[{"path":["x"]}]}""",
        ) as ServerMessage.Error
        assertEquals("invalid_message", withDetails.code)
        assertEquals("bad", withDetails.message)
        assertTrue(withDetails.details != null)

        val plain = Protocol.parseServerMessage("""{"type":"error","code":"rate_limited","message":"slow"}""")
        assertEquals(ServerMessage.Error("rate_limited", "slow"), plain)
    }

    @Test
    fun `rejects unknown type`() {
        val e = assertThrows(ProtocolException::class.java) {
            Protocol.parseServerMessage("""{"type":"response","requestId":"x"}""")
        }
        assertTrue(e.message!!.contains("Unknown server message type"))
    }

    @Test
    fun `rejects missing type, non-objects and invalid json`() {
        assertThrows(ProtocolException::class.java) { Protocol.parseServerMessage("""{"url":"x"}""") }
        assertThrows(ProtocolException::class.java) { Protocol.parseServerMessage("""[1,2]""") }
        assertThrows(ProtocolException::class.java) { Protocol.parseServerMessage("""{not json""") }
        assertThrows(ProtocolException::class.java) { Protocol.parseServerMessage("""{"type":42}""") }
    }

    @Test
    fun `rejects known type with missing fields`() {
        assertThrows(ProtocolException::class.java) {
            Protocol.parseServerMessage("""{"type":"request","method":"POST","path":"/","headers":{}}""")
        }
        assertThrows(ProtocolException::class.java) { Protocol.parseServerMessage("""{"type":"ping"}""") }
    }

    @Test
    fun `encodes response with type discriminator and omits null body`() {
        val encoded = Json.parseToJsonElement(
            Protocol.encode(ClientMessage.Response("r1", 200, mapOf("content-type" to "application/json"))),
        ).jsonObject
        assertEquals("response", encoded["type"]!!.jsonPrimitive.content)
        assertEquals("r1", encoded["requestId"]!!.jsonPrimitive.content)
        assertEquals(200, encoded["statusCode"]!!.jsonPrimitive.content.toInt())
        assertEquals("application/json", (encoded["headers"] as JsonObject)["content-type"]!!.jsonPrimitive.content)
        assertFalse(encoded.containsKey("body"))
    }

    @Test
    fun `encodes response body as a string`() {
        val encoded = Json.parseToJsonElement(
            Protocol.encode(ClientMessage.Response("r1", 200, emptyMap(), """{"id":1}""")),
        ).jsonObject
        assertEquals("""{"id":1}""", encoded["body"]!!.jsonPrimitive.content)
        assertTrue(encoded["body"]!!.jsonPrimitive.isString)
    }

    @Test
    fun `encodes pong echoing timestamp`() {
        assertEquals(
            """{"type":"pong","timestamp":1704063600000}""",
            Protocol.encode(ClientMessage.Pong(1704063600000)),
        )
    }

    @Test
    fun `normalizes client version to semver triple`() {
        assertEquals("0.1.0", ProtocolConstants.normalizeClientVersion("0.1.0"))
        assertEquals("1.2.3", ProtocolConstants.normalizeClientVersion("v1.2.3-beta.1+build"))
        assertNull(ProtocolConstants.normalizeClientVersion("dev"))
    }
}
