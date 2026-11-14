package dev.midge.sdk

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.Permission
import java.security.cert.Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/**
 * HttpURLConnection with Midge watching: every call goes to the real connection unchanged, and once the response is
 * read (or the connection fails or is disconnected) the request lands in the report like OkHttp's do. Bodies are
 * copied as they pass, up to 64 KB, text only. An HttpsURLConnection, so code that casts for TLS settings keeps working.
 */
internal class MidgeConnection(private val real: HttpURLConnection) : HttpsURLConnection(real.url) {
    private val https = real as? HttpsURLConnection
    private val started = EventLog.now()
    /** Kept as they're set: the platform hides some (Authorization) from getRequestProperties. */
    private val requestHeaders = linkedMapOf<String, MutableList<String>>()
    private val sent = Capped()
    private val received = Capped()
    private var recorded = false

    /** Keeps the first 64 KB of what passes through, and whether there was more. */
    private class Capped {
        val bytes = ByteArrayOutputStream()
        var more = false
        fun add(b: ByteArray, off: Int, len: Int) {
            val room = MidgeInterceptor.BODY_LIMIT - bytes.size()
            if (len > room) more = true
            if (room > 0) bytes.write(b, off, minOf(len, room))
        }
    }

    private fun snapshotHeaders() {
        runCatching { real.requestProperties }.getOrNull()?.forEach { (k, v) -> if (k != null && k !in requestHeaders) requestHeaders[k] = v.toMutableList() }
    }

    @Synchronized
    private fun record(error: Throwable? = null) {
        if (recorded || Midge.config?.options?.captureNetwork == false || Midge.isMidge(real.url.toString())) return
        recorded = true
        val status = if (error != null) 0 else runCatching { real.responseCode }.getOrDefault(0)
        val event = JSONObject().put("kind", "network").put("at", started).put("method", real.requestMethod)
            .put("url", real.url.toString()).put("type", "fetch").put("status", status)
            .put("durationMs", (EventLog.now() - started).toInt())
            .put("requestHeaders", pairs(requestHeaders))
        if (error == null) runCatching { event.put("responseHeaders", pairs(real.headerFields)) }
        if (sent.bytes.size() > 0 && text(requestHeaders.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.firstOrNull())) event.put("requestBody", sent.bytes.toString(Charsets.UTF_8.name()))
        if (received.bytes.size() > 0 && text(runCatching { real.contentType }.getOrNull())) {
            event.put("responseBody", received.bytes.toString(Charsets.UTF_8.name()))
            if (received.more) event.put("truncated", true)
        }
        error?.let { event.put("error", it.message ?: it.javaClass.simpleName) }
        EventLog.add(event)
    }

    private fun text(type: String?) = MidgeInterceptor.isText(type?.toMediaTypeOrNull())

    private fun pairs(map: Map<out String?, List<String>>): JSONArray = JSONArray().apply {
        map.forEach { (k, vs) -> if (k != null) vs.forEach { put(JSONArray(listOf(k.lowercase(), it))) } }
    }

    private inline fun <T> watch(block: () -> T): T = try {
        block()
    } catch (e: java.io.IOException) {
        if (e !is java.io.FileNotFoundException) record(e)  // a 4xx/5xx body is read from the error stream instead
        throw e
    }

    // Reading the response: the body is copied as it's read; closing or the end of it records the request.
    override fun getInputStream(): InputStream { snapshotHeaders(); return Tee(watch { real.inputStream }) }
    override fun getErrorStream(): InputStream? { snapshotHeaders(); return real.errorStream?.let(::Tee) }

    private inner class Tee(input: InputStream) : FilterInputStream(input) {
        override fun read(): Int = super.read().also { if (it >= 0) received.add(byteArrayOf(it.toByte()), 0, 1) else record() }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) received.add(b, off, it) else if (it < 0) record() }
        override fun close() { super.close(); record() }
    }

    override fun getOutputStream(): OutputStream {
        snapshotHeaders()
        return object : FilterOutputStream(watch { real.outputStream }) {
            override fun write(b: Int) { out.write(b); sent.add(byteArrayOf(b.toByte()), 0, 1) }
            override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); sent.add(b, off, len) }
        }
    }

    override fun getResponseCode(): Int { snapshotHeaders(); return watch { real.responseCode } }
    override fun getResponseMessage(): String? = watch { real.responseMessage }
    override fun connect() { snapshotHeaders(); watch { real.connect() } }
    override fun disconnect() { record(); real.disconnect() }

    // Everything else, as the real connection does it.
    override fun usingProxy() = real.usingProxy()
    override fun getURL(): URL = real.url
    override fun setRequestMethod(method: String) { real.requestMethod = method }
    override fun getRequestMethod(): String = real.requestMethod
    override fun setRequestProperty(key: String, value: String?) {
        real.setRequestProperty(key, value)
        requestHeaders.keys.firstOrNull { it.equals(key, true) }?.let(requestHeaders::remove)
        if (value != null) requestHeaders[key] = mutableListOf(value)
    }
    override fun addRequestProperty(key: String, value: String?) {
        real.addRequestProperty(key, value)
        if (value != null) (requestHeaders.entries.firstOrNull { it.key.equals(key, true) }?.value ?: mutableListOf<String>().also { requestHeaders[key] = it }).add(value)
    }
    override fun getRequestProperty(key: String): String? = real.getRequestProperty(key)
    override fun getRequestProperties(): Map<String, List<String>> = real.requestProperties
    override fun getHeaderField(name: String?): String? = real.getHeaderField(name)
    override fun getHeaderField(n: Int): String? = real.getHeaderField(n)
    override fun getHeaderFieldKey(n: Int): String? = real.getHeaderFieldKey(n)
    override fun getHeaderFields(): Map<String?, List<String>> = real.headerFields
    override fun getHeaderFieldInt(name: String?, default: Int) = real.getHeaderFieldInt(name, default)
    override fun getHeaderFieldLong(name: String?, default: Long) = real.getHeaderFieldLong(name, default)
    override fun getHeaderFieldDate(name: String?, default: Long) = real.getHeaderFieldDate(name, default)
    override fun getContentType(): String? = real.contentType
    override fun getContentLength() = real.contentLength
    override fun getContentLengthLong() = real.contentLengthLong
    override fun getContentEncoding(): String? = real.contentEncoding
    override fun getDate() = real.date
    override fun getExpiration() = real.expiration
    override fun getLastModified() = real.lastModified
    override fun getContent(): Any? = real.content
    override fun getPermission(): Permission? = real.permission
    override fun setDoInput(v: Boolean) { real.doInput = v }
    override fun getDoInput() = real.doInput
    override fun setDoOutput(v: Boolean) { real.doOutput = v }
    override fun getDoOutput() = real.doOutput
    override fun setAllowUserInteraction(v: Boolean) { real.allowUserInteraction = v }
    override fun getAllowUserInteraction() = real.allowUserInteraction
    override fun setUseCaches(v: Boolean) { real.useCaches = v }
    override fun getUseCaches() = real.useCaches
    override fun setIfModifiedSince(v: Long) { real.ifModifiedSince = v }
    override fun getIfModifiedSince() = real.ifModifiedSince
    override fun setDefaultUseCaches(v: Boolean) { real.defaultUseCaches = v }
    override fun getDefaultUseCaches() = real.defaultUseCaches
    override fun setConnectTimeout(v: Int) { real.connectTimeout = v }
    override fun getConnectTimeout() = real.connectTimeout
    override fun setReadTimeout(v: Int) { real.readTimeout = v }
    override fun getReadTimeout() = real.readTimeout
    override fun setInstanceFollowRedirects(v: Boolean) { real.instanceFollowRedirects = v }
    override fun getInstanceFollowRedirects() = real.instanceFollowRedirects
    override fun setFixedLengthStreamingMode(v: Int) = real.setFixedLengthStreamingMode(v)
    override fun setFixedLengthStreamingMode(v: Long) = real.setFixedLengthStreamingMode(v)
    override fun setChunkedStreamingMode(v: Int) = real.setChunkedStreamingMode(v)
    override fun toString() = real.toString()

    // HTTPS: the real connection's TLS, when it has one.
    private fun tls(): HttpsURLConnection = https ?: throw IllegalStateException("Not an HTTPS connection: ${real.url}")
    override fun getCipherSuite(): String = tls().cipherSuite
    override fun getLocalCertificates(): Array<Certificate>? = tls().localCertificates
    override fun getServerCertificates(): Array<Certificate> = tls().serverCertificates
    override fun setSSLSocketFactory(factory: SSLSocketFactory) { tls().sslSocketFactory = factory }
    override fun getSSLSocketFactory(): SSLSocketFactory = tls().sslSocketFactory
    override fun setHostnameVerifier(v: HostnameVerifier) { tls().hostnameVerifier = v }
    override fun getHostnameVerifier(): HostnameVerifier = tls().hostnameVerifier
}
