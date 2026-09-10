package bes.max.bmaps.core.network

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.*
import kotlinx.coroutines.runBlocking

class PublicHttpCacheTest {
    @Test fun nativeCachePersistsFreshResponsesAndRevalidatesExpiredResponses() = runBlocking {
        val directory = Files.createTempDirectory("bmaps-http-cache").toFile()
        val requests = AtomicInteger()
        val conditionals = AtomicInteger()
        val server = ServerSocket(0)
        val worker = thread(isDaemon = true) {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    val request = input.readLine()
                    val headers = mutableListOf<String>()
                    while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break; headers += line }
                    requests.incrementAndGet()
                    val conditional = headers.any { it.equals("If-None-Match: \"fixture\"", ignoreCase = true) }
                    if (conditional) conditionals.incrementAndGet()
                    val stale = request.contains("/stale") && !conditional
                    val response = if (conditional) "HTTP/1.1 304 Not Modified\r\n" else "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n"
                    socket.getOutputStream().write((response + "Cache-Control: public, max-age=${if (stale) 0 else 3600}\r\nETag: \"fixture\"\r\nConnection: close\r\n\r\n" + if (conditional) "" else "tile").toByteArray())
                }
            } catch (_: java.net.SocketException) { }
        }
        fun clients() = platformHttpClients(directory)
        val url = "http://127.0.0.1:${server.localPort}"
        var pair = clients()
        try {
            assertEquals("tile", pair.publicCache.get("$url/fresh").bodyAsText())
            assertEquals("tile", pair.publicCache.get("$url/stale").bodyAsText())
            pair.publicCache.close(); pair.uncached.close()
            pair = clients()
            assertEquals("tile", pair.publicCache.get("$url/fresh").bodyAsText())
            assertEquals(2, requests.get())
            assertEquals("tile", pair.publicCache.get("$url/stale").bodyAsText())
            assertEquals(1, conditionals.get())
            pair.uncached.get("$url/fresh").bodyAsText()
            pair.uncached.get("$url/fresh").bodyAsText()
            assertEquals(5, requests.get())
        } finally {
            pair.publicCache.close(); pair.uncached.close()
            server.close(); worker.join(2000)
            directory.deleteRecursively()
        }
    }
}
