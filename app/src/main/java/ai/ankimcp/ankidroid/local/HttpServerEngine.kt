package ai.ankimcp.ankidroid.local

/** One HTTP request as the engine read it. Header names are lower-case. */
class HttpRequest(
    val method: String,
    /** Path without the query string. */
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray,
    /** Set when the engine did not read the body; [body] is then empty. */
    val bodyProblem: BodyProblem? = null,
) {
    enum class BodyProblem {
        /** `Content-Length` above [HttpServerEngine.MAX_BODY_BYTES]. */
        TooLarge,

        /** A body without `Content-Length` (chunked); not supported. */
        LengthRequired,
    }

    fun header(name: String): String? = headers[name.lowercase()]
}

data class HttpResponse(
    val status: Int,
    /** Null for a response without a body. */
    val contentType: String? = null,
    val body: String = "",
    val headers: Map<String, String> = emptyMap(),
)

/**
 * A small HTTP/1.1 server bound to one address. The only implementation is
 * [NanoHttpServerEngine]; the interface keeps the library out of everything else, so
 * [McpHttpAdapter] and [LocalServer] are tested without sockets.
 */
interface HttpServerEngine {
    /**
     * Binds [host]:[port] and serves every request with [handler] until [stop]. [screen] sees
     * each request with its headers only (empty body) before the body is read; a non-null
     * answer is sent at once, the body is never read and the connection is closed.
     * @throws java.io.IOException when the address cannot be bound (port in use, address gone).
     */
    fun start(
        host: String,
        port: Int,
        screen: (HttpRequest) -> HttpResponse? = { null },
        handler: suspend (HttpRequest) -> HttpResponse,
    )

    /** Closes the listening socket and open connections. Safe to call twice. */
    fun stop()

    fun interface Factory {
        fun create(): HttpServerEngine
    }

    companion object {
        /** JSON-RPC messages are small; anything bigger is refused unread. */
        const val MAX_BODY_BYTES = 1L shl 20
    }
}
