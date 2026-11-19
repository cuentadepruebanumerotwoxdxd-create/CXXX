package com.CXXX

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object LocalResourceServer {
    private class Resource(val bytes: ByteArray, val contentType: String)

    private val resources = ConcurrentHashMap<String, Resource>()
    private val counter = AtomicInteger()
    private var server: ServerSocket? = null

    @Synchronized
    private fun start(): ServerSocket {
        server?.takeIf { !it.isClosed }?.let { return it }
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = s
        Thread {
            while (!s.isClosed) {
                val client = try { s.accept() } catch (e: Exception) { break }
                Thread { handle(client) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
        return s
    }

    fun register(bytes: ByteArray, contentType: String, ext: String): String {
        val port = start().localPort
        if (resources.size > 64) resources.clear()
        val name = "${counter.incrementAndGet()}.$ext"
        resources[name] = Resource(bytes, contentType)
        return "http://127.0.0.1:$port/$name"
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            try {
                val input = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val requestLine = input.readLine() ?: return
                while (true) { if (input.readLine().isNullOrEmpty()) break }
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0) ?: return
                val name = parts.getOrNull(1)?.substringAfter("/")?.substringBefore("?").orEmpty()
                val res = resources[name]
                val out = s.getOutputStream()
                if (res == null) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                } else {
                    out.write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: ${res.contentType}\r\nContent-Length: ${res.bytes.size}\r\n" +
                            "Access-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n").toByteArray()
                    )
                    if (method != "HEAD") out.write(res.bytes)
                }
                out.flush()
            } catch (_: Exception) {
            }
        }
    }
}

open class Xtremestream : ExtractorApi() {
    override var name = "Xtremestream"
    override var mainUrl = "https://xtremestream.xyz"
    override val requiresReferer = true

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            resolve(url, referer, callback)
        } catch (e: Throwable) {
            Log.e("CXXX_Perverzija", "failed: ${e::class.java.name}: ${e.message}")
        }
    }

    private suspend fun resolve(url: String, referer: String?, callback: (ExtractorLink) -> Unit) {
        val uri = URI(url)
        val origin = "${uri.scheme}://${uri.host}"

        val page = app.get(url, referer = referer ?: "https://perverzija.com", headers = mapOf("User-Agent" to userAgent)).text
        val boot = Regex("""XSFTK\.boot\(\s*["']([^"']+)["']\s*,\s*["']([^"']+)["']""").find(page) ?: return
        val videoId = boot.groupValues.get(1)
        val token = boot.groupValues.get(2)

        fun api(op: String) = "$origin/player/proto_api.php?data=$videoId&op=$op&vt=${URLEncoder.encode(token, "UTF-8")}"
        val apiHeaders = mapOf("User-Agent" to userAgent, "Referer" to url, "X-XS-P" to "1")

        val key = app.get(api("key"), headers = apiHeaders).okhttpResponse.body.bytes()
        val raw = app.get(api("manifest"), headers = apiHeaders).okhttpResponse.body.bytes()

        val mask = MessageDigest.getInstance("SHA-256").digest("M|$videoId|$token".toByteArray())
        val manifest = JSONObject(String(ByteArray(raw.size) { (raw.get(it).toInt() xor mask.get(it % mask.size).toInt()).toByte() }))

        val cdn = manifest.opt("cdn")
        val hosts: List<String> = when (cdn) {
            is JSONObject -> cdn.keys().asSequence().sortedBy { it.toIntOrNull() ?: 0 }.map { cdn.getString(it) }.toList()
            is JSONArray -> (0 until cdn.length()).map { cdn.getString(it) }
            else -> emptyList()
        }
        if (hosts.isEmpty()) return

        val keyUri = LocalResourceServer.register(key, "application/octet-stream", "key")
        val headers = mapOf("User-Agent" to userAgent, "Referer" to "$origin/", "Origin" to origin, "Accept" to "*/*")

        manifest.keys().asSequence().mapNotNull { it.toIntOrNull() }.sorted().forEach { q ->
            val entry = manifest.optJSONObject(q.toString()) ?: return@forEach
            val durs = entry.optJSONArray("durs") ?: return@forEach
            if (durs.length() == 0) return@forEach

            val playlist = buildString {
                append("#EXTM3U\n#EXT-X-VERSION:3\n")
                append("#EXT-X-TARGETDURATION:${entry.optInt("td", 10)}\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n")
                append("#EXT-X-KEY:METHOD=AES-128,URI=\"$keyUri\"\n")
                for (i in 0 until durs.length()) {
                    append("#EXTINF:${durs.get(i)},\nhttps://${hosts.get(i % hosts.size)}/cdn/down/$videoId/${q}_enc/$q$i.html\n")
                }
                append("#EXT-X-ENDLIST\n")
            }
            val playlistUri = LocalResourceServer.register(playlist.toByteArray(), "application/vnd.apple.mpegurl", "m3u8")

            callback.invoke(newExtractorLink(
                source = this.name,
                name = "${this.name} ${q}p",
                url = playlistUri,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "$origin/"
                this.quality = q
                this.headers = headers
            })
        }
    }
}
