package ai.ankimcp.ankidroid.local

import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [HttpServerEngine] on NanoHTTPD 2.3.1 (D53). The only file that imports NanoHTTPD.
 *
 * Changes from NanoHTTPD's defaults: at most [maxConnections] connections, each on a pooled
 * thread (the default starts an unbounded thread per connection); no temporary files (the
 * library's known CVEs are in its temp-file handling for multipart uploads, which this server
 * never parses); no gzip; the body is read here, capped at [HttpServerEngine.MAX_BODY_BYTES].
 */
class NanoHttpServerEngine(
    private val maxConnections: Int = MAX_CONNECTIONS,
    private val socketReadTimeoutMs: Int = SOCKET_READ_TIMEOUT_MS,
) : HttpServerEngine {
    private var server: Server? = null

    @Synchronized
    override fun start(
        host: String,
        port: Int,
        screen: (HttpRequest) -> HttpResponse?,
        handler: suspend (HttpRequest) -> HttpResponse,
    ) {
        check(server == null) { "already started" }
        val created = Server(host, port, handler, screen, maxConnections)
        try {
            created.start(socketReadTimeoutMs, true)
        } catch (e: IOException) {
            created.stop()
            throw e
        }
        server = created
    }

    @Synchronized
    override fun stop() {
        server?.stop()
        server = null
    }

    private class Server(
        host: String,
        port: Int,
        private val handler: suspend (HttpRequest) -> HttpResponse,
        private val screen: (HttpRequest) -> HttpResponse?,
        maxConnections: Int,
    ) : NanoHTTPD(host, port) {
        private val runner = BoundedRunner(maxConnections)

        init {
            setAsyncRunner(runner)
            setTempFileManagerFactory { NoTempFiles }
        }

        override fun useGzipWhenAccepted(r: Response): Boolean = false

        override fun serve(session: IHTTPSession): Response {
            // NanoHTTPD adds these two pseudo-headers with the peer address.
            val headers = session.headers.filterKeys { it != "remote-addr" && it != "http-client-ip" }
            val screened = try {
                screen(HttpRequest(session.method.name, session.uri, headers, ByteArray(0)))
            } catch (e: Exception) {
                HttpResponse(500, "text/plain", "Internal error")
            }
            if (screened != null) {
                // Refused on the headers: the body is not kept and the connection is not reused.
                // A small body already on its way is drained first, or closing with unread data
                // sends a TCP RST that can make the client lose the 403.
                drainBriefly(session.inputStream, headers["content-length"])
                return screened.toNano().apply { closeConnection(true) }
            }
            val problem = bodyProblem(headers)
            val body = if (problem == null) readBody(session.inputStream, headers["content-length"]) else ByteArray(0)
            val request = HttpRequest(session.method.name, session.uri, headers, body, problem)
            val response = try {
                runBlocking { handler(request) }
            } catch (e: Exception) {
                HttpResponse(500, "text/plain", "Internal error")
            }
            return response.toNano().apply {
                // An unread body would be parsed as the next request on this connection.
                if (problem != null) closeConnection(true)
            }
        }

        override fun stop() {
            super.stop()
            runner.shutdown()
        }
    }

    /** Runs connections on a fixed pool; a connection beyond it is closed at once. */
    private class BoundedRunner(maxConnections: Int) : NanoHTTPD.AsyncRunner {
        private val threadNumber = AtomicInteger()
        private val executor = ThreadPoolExecutor(
            maxConnections,
            maxConnections,
            IDLE_THREAD_SECONDS,
            TimeUnit.SECONDS,
            SynchronousQueue(),
        ) { runnable ->
            Thread(runnable, "local-mcp-http-${threadNumber.incrementAndGet()}").apply { isDaemon = true }
        }.apply { allowCoreThreadTimeOut(true) }
        private val running = Collections.synchronizedSet(LinkedHashSet<NanoHTTPD.ClientHandler>())

        override fun exec(clientHandler: NanoHTTPD.ClientHandler) {
            running += clientHandler
            try {
                executor.execute(clientHandler)
            } catch (e: RejectedExecutionException) {
                running -= clientHandler
                clientHandler.close()
            }
        }

        override fun closed(clientHandler: NanoHTTPD.ClientHandler) {
            running -= clientHandler
        }

        override fun closeAll() {
            synchronized(running) { running.toList() }.forEach { it.close() }
        }

        fun shutdown() {
            executor.shutdownNow()
        }
    }

    private object NoTempFiles : NanoHTTPD.TempFileManager {
        override fun clear() = Unit

        override fun createTempFile(filenameHint: String?): NanoHTTPD.TempFile =
            throw IOException("Temporary files are disabled")
    }

    private class Status(private val code: Int) : NanoHTTPD.Response.IStatus {
        override fun getRequestStatus(): Int = code

        override fun getDescription(): String = "$code ${REASONS[code] ?: "Status"}"
    }

    companion object {
        /** Enough for a couple of clients with keep-alive connections. */
        const val MAX_CONNECTIONS = 8

        /** Idle keep-alive connections are closed after this long. */
        const val SOCKET_READ_TIMEOUT_MS = 5_000

        private const val IDLE_THREAD_SECONDS = 30L

        private val REASONS = mapOf(
            200 to "OK",
            202 to "Accepted",
            400 to "Bad Request",
            401 to "Unauthorized",
            403 to "Forbidden",
            404 to "Not Found",
            405 to "Method Not Allowed",
            406 to "Not Acceptable",
            411 to "Length Required",
            413 to "Payload Too Large",
            415 to "Unsupported Media Type",
            500 to "Internal Server Error",
            503 to "Service Unavailable",
            504 to "Gateway Timeout",
        )

        internal fun bodyProblem(headers: Map<String, String>): HttpRequest.BodyProblem? {
            val length = headers["content-length"]
            if (length == null) {
                return if (headers["transfer-encoding"] != null) HttpRequest.BodyProblem.LengthRequired else null
            }
            val bytes = length.trim().toLongOrNull() ?: return HttpRequest.BodyProblem.LengthRequired
            return when {
                bytes < 0 -> HttpRequest.BodyProblem.LengthRequired
                bytes > HttpServerEngine.MAX_BODY_BYTES -> HttpRequest.BodyProblem.TooLarge
                else -> null
            }
        }

        /** Discards at most [DRAIN_MAX_BYTES] of an announced body, for at most [DRAIN_DEADLINE_MS], reading only what has arrived. */
        private fun drainBriefly(input: InputStream, contentLength: String?) {
            val announced = contentLength?.trim()?.toLongOrNull() ?: return
            val limit = minOf(announced, DRAIN_MAX_BYTES.toLong()).toInt()
            if (limit <= 0) return
            val buffer = ByteArray(minOf(limit, 8_192))
            val deadline = System.nanoTime() + DRAIN_DEADLINE_MS * 1_000_000
            var drained = 0
            try {
                while (drained < limit && System.nanoTime() < deadline) {
                    val available = input.available()
                    if (available <= 0) {
                        Thread.sleep(DRAIN_POLL_MS)
                        continue
                    }
                    val read = input.read(buffer, 0, minOf(available, buffer.size, limit - drained))
                    if (read < 0) return
                    drained += read
                }
            } catch (_: IOException) {
                // The peer went away; nothing to protect.
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        private const val DRAIN_MAX_BYTES = 64 * 1024
        private const val DRAIN_DEADLINE_MS = 500L
        private const val DRAIN_POLL_MS = 10L

        private fun readBody(input: InputStream, contentLength: String?): ByteArray {
            val length = contentLength?.trim()?.toIntOrNull() ?: return ByteArray(0)
            val body = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = input.read(body, offset, length - offset)
                // NanoHTTPD closes the connection quietly on this exact message.
                if (read < 0) throw SocketException("NanoHttpd Shutdown")
                offset += read
            }
            return body
        }

        private fun HttpResponse.toNano(): NanoHTTPD.Response {
            val bytes = body.toByteArray(Charsets.UTF_8)
            return NanoHTTPD.newFixedLengthResponse(Status(status), contentType, ByteArrayInputStream(bytes), bytes.size.toLong())
                .also { response -> headers.forEach { (name, value) -> response.addHeader(name, value) } }
        }
    }
}
