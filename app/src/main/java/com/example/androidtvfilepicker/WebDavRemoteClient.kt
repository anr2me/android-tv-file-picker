package com.example.androidtvfilepicker

import android.net.Uri
import android.util.Xml
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.Pipe
import okio.buffer
import org.xmlpull.v1.XmlPullParser
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * WebDAV client. It uses OkHttp. It supports Basic authentication.
 * The server must support HTTP range requests, if the app reads at random offsets.
 */
class WebDavRemoteClient(private val share: NetShare) : RemoteClient {

    private val base: HttpUrl = share.address.trim().toHttpUrl()
    private val http: OkHttpClient = buildHttp()
    private val authorization: String? =
        if (share.username.isEmpty()) null else Credentials.basic(share.username, share.password)

    // A WebDAV write is one HTTP request. It cannot write at a random offset.
    override val supportsRandomWrite: Boolean = false

    private fun buildHttp(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
        if (share.trustAllCerts) {
            @Suppress("CustomX509TrustManager", "TrustAllX509TrustManager")
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf(trustAll), SecureRandom())
            builder.sslSocketFactory(context.socketFactory, trustAll)
            builder.hostnameVerifier { _, _ -> true }
        }
        return builder.build()
    }

    override fun close() {
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    // ---- HTTP helpers ----

    /** Makes the URL of an item. A folder gets a slash at the end. */
    private fun url(path: String, folder: Boolean = false): HttpUrl {
        val builder = HttpUrl.Builder().scheme(base.scheme).host(base.host).port(base.port)
        base.pathSegments.filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        path.split('/').filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        if (folder) builder.addPathSegment("")
        return builder.build()
    }

    private fun call(builder: Request.Builder): Response {
        authorization?.let { builder.header("Authorization", it) }
        return http.newCall(builder.build()).execute()
    }

    private fun ensureOk(response: Response) {
        val code = response.code
        when {
            response.isSuccessful -> return
            code == 404 || code == 410 -> throw FileNotFoundException("Not found (HTTP $code)")
            code == 401 || code == 403 -> throw IOException("Access denied (HTTP $code)")
            else -> throw IOException("HTTP $code ${response.message}")
        }
    }

    private fun propfind(url: HttpUrl, depth: String): Request.Builder =
        Request.Builder()
            .url(url)
            .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml".toMediaType()))
            .header("Depth", depth)

    // ---- Information ----

    override fun list(path: String): List<RemoteEntry> {
        val url = url(path, true)
        call(propfind(url, "1")).use { response ->
            ensureOk(response)
            val stream = response.body?.byteStream() ?: throw IOException("The server sent no data")
            val self = "/" + url.pathSegments.filter { it.isNotEmpty() }.joinToString("/")
            return parseMultiStatus(stream, self)
        }
    }

    override fun stat(path: String): RemoteEntry? {
        if (path == "/") return RemoteEntry("", true, 0L, 0L)
        call(propfind(url(path), "0")).use { response ->
            if (response.code == 404) return null
            ensureOk(response)
            val stream = response.body?.byteStream() ?: throw IOException("The server sent no data")
            val entry = parseMultiStatus(stream, null).firstOrNull() ?: return null
            return RemoteEntry(path.substringAfterLast('/'), entry.isDir, entry.size, entry.modified)
        }
    }

    /** Reads the answer of a PROPFIND request. [selfPath] is the folder itself. The list skips it. */
    private fun parseMultiStatus(stream: InputStream, selfPath: String?): List<RemoteEntry> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(stream, null)

        val result = ArrayList<RemoteEntry>()
        var href: String? = null
        var isDir = false
        var size = 0L
        var modified = 0L

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "response" -> {
                        href = null
                        isDir = false
                        size = 0L
                        modified = 0L
                    }
                    "href" -> {
                        val text = parser.nextText().trim()
                        if (href == null) href = text
                    }
                    "collection" -> isDir = true
                    "getcontentlength" -> size = parser.nextText().trim().toLongOrNull() ?: 0L
                    "getlastmodified" -> modified = parseDate(parser.nextText().trim())
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "response") {
                href?.let { value ->
                    val decoded = Uri.parse(value).path ?: value
                    val trimmed = decoded.trimEnd('/')
                    val name = trimmed.substringAfterLast('/')
                    val isSelf = selfPath != null && trimmed == selfPath.trimEnd('/')
                    if (name.isNotEmpty() && !isSelf) {
                        result.add(RemoteEntry(name, isDir || decoded.endsWith("/"), size, modified))
                    } else if (selfPath == null) {
                        // A stat request: keep the data of the item itself.
                        result.add(RemoteEntry(name, isDir || decoded.endsWith("/"), size, modified))
                    }
                }
            }
            event = parser.next()
        }
        return result
    }

    private fun parseDate(text: String): Long = try {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(text)?.time ?: 0L
    } catch (e: ParseException) {
        0L
    }

    // ---- Read and write ----

    override fun openRead(path: String): RemoteReader {
        val entry = stat(path) ?: throw FileNotFoundException(path)
        if (entry.isDir) throw FileNotFoundException("This is a folder: $path")
        return DavReader(path, entry.size)
    }

    override fun openWrite(path: String, mode: WriteMode): RemoteWriter {
        if (mode != WriteMode.TRUNCATE) {
            throw IOException("WebDAV supports only a write that replaces the file")
        }
        return DavWriter(path)
    }

    private inner class DavReader(private val path: String, override val size: Long) : RemoteReader {

        override fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int {
            if (length <= 0) return 0
            val request = Request.Builder()
                .url(url(path))
                .header("Range", "bytes=$offset-${offset + length - 1}")
                .get()
            call(request).use { response ->
                if (response.code == 416) return -1
                ensureOk(response)
                if (response.code == 200 && offset > 0) {
                    throw IOException("The server does not support range requests")
                }
                val stream = response.body?.byteStream() ?: return -1
                var total = 0
                while (total < length) {
                    val count = stream.read(buffer, bufferOffset + total, length - total)
                    if (count < 0) break
                    total += count
                }
                return if (total == 0) -1 else total
            }
        }

        override fun close() {}
    }

    /** Sends the data with one PUT request. The data goes through a pipe. */
    private inner class DavWriter(path: String) : RemoteWriter {

        override val startOffset: Long = 0L

        private val pipe = Pipe(1L shl 20)
        private val sink: BufferedSink = pipe.sink.buffer()

        @Volatile
        private var failure: IOException? = null
        private var position = 0L
        private val worker: Thread

        init {
            val body = object : RequestBody() {
                override fun contentType(): MediaType? = null
                override fun isOneShot(): Boolean = true
                override fun writeTo(sink: BufferedSink) {
                    sink.writeAll(pipe.source)
                }
            }
            val request = Request.Builder().url(url(path)).put(body)
            worker = Thread {
                try {
                    call(request).use { ensureOk(it) }
                } catch (e: IOException) {
                    failure = e
                } finally {
                    // Stop a writer that waits for a request that ended.
                    pipe.cancel()
                }
            }
            worker.start()
        }

        override fun write(offset: Long, data: ByteArray, dataOffset: Int, length: Int) {
            failure?.let { throw it }
            if (offset != position) throw IOException("The writes must be in sequence")
            try {
                sink.write(data, dataOffset, length)
            } catch (e: IOException) {
                throw failure ?: e
            }
            position += length
        }

        override fun close() {
            try {
                sink.close()
            } catch (e: IOException) {
                // The worker thread reports the error.
            }
            worker.join(120_000)
            failure?.let { throw it }
        }
    }

    // ---- Change ----

    override fun mkdir(path: String) {
        call(Request.Builder().url(url(path, true)).method("MKCOL", null)).use { ensureOk(it) }
    }

    override fun createEmpty(path: String) {
        call(Request.Builder().url(url(path)).put("".toRequestBody(null))).use { ensureOk(it) }
    }

    override fun delete(path: String) {
        val entry = stat(path) ?: throw FileNotFoundException(path)
        call(Request.Builder().url(url(path, entry.isDir)).delete()).use { ensureOk(it) }
    }

    override fun rename(path: String, newPath: String) {
        val entry = stat(path) ?: throw FileNotFoundException(path)
        val request = Request.Builder()
            .url(url(path, entry.isDir))
            .method("MOVE", null)
            .header("Destination", url(newPath, entry.isDir).toString())
            .header("Overwrite", "F")
        call(request).use { ensureOk(it) }
    }

    private companion object {
        const val PROPFIND_BODY = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<d:propfind xmlns:d=\"DAV:\"><d:prop>" +
            "<d:resourcetype/><d:getcontentlength/><d:getlastmodified/>" +
            "</d:prop></d:propfind>"
    }
}
