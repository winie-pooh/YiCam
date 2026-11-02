/**
 * Responsible for: a local HTTP server for download tests: Range 206, a mode that ignores Range, a cut after N bytes, a chosen ETag, a wrong Content-Range start.
 * Not responsible for: assertions (DownloaderTest.kt).
 */
package com.example.yicam

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors

class RangeHttpServer(
    val body: ByteArray,
    var ignoreRange: Boolean = false,
    var etag: String = "\"test-etag\"",
    var cutAfter: Int? = null,
    var statusOverride: Int? = null,
    var forceContentRangeStart: Long? = null,
    var beforeBody: java.util.concurrent.CountDownLatch? = null,
) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val port: Int get() = server.address.port
    val url: String get() = "http://127.0.0.1:$port/DCIM/101MEDIA/YDXJ0001.jpg"

    init {
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/DCIM/101MEDIA/YDXJ0001.jpg") { exchange ->
            try {
                val override = statusOverride
                if (override != null) {
                    exchange.sendResponseHeaders(override, -1)
                    return@createContext
                }
                val rangeHeader = exchange.requestHeaders.getFirst("Range")
                val ifRange = exchange.requestHeaders.getFirst("If-Range")
                exchange.responseHeaders.add("ETag", etag)
                val useRange = !ignoreRange && rangeHeader != null &&
                    (ifRange == null || ifRange == etag)
                val match = Regex("""bytes=(\d+)-""").find(rangeHeader.orEmpty())
                val start = if (useRange) match?.groupValues?.get(1)?.toIntOrNull() ?: 0 else 0
                val from = start.coerceIn(0, body.size)
                var payload = body.copyOfRange(from, body.size)
                val cut = cutAfter
                if (cut != null && payload.size > cut) payload = payload.copyOfRange(0, cut)
                if ((useRange && from > 0) || cut != null || forceContentRangeStart != null) {
                    val advertised = forceContentRangeStart ?: from.toLong()
                    val last = advertised + payload.size - 1
                    exchange.responseHeaders.add("Content-Range", "bytes $advertised-$last/${body.size}")
                    exchange.sendResponseHeaders(206, payload.size.toLong())
                } else {
                    exchange.sendResponseHeaders(200, payload.size.toLong())
                }
                beforeBody?.await()
                exchange.responseBody.write(payload)
            } finally {
                exchange.close()
            }
        }
        server.start()
    }

    override fun close() {
        server.stop(0)
    }
}
