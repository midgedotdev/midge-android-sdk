package dev.midge.sdk

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Response
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Sees the app's HTTP requests: records each one and passes it on unchanged. Bodies are read up to 64 KB without
 * consuming them (the request's from a copy, the response's with peekBody). Midge's own calls are left out.
 */
internal class MidgeInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        if (Midge.config?.options?.captureNetwork == false || Midge.isMidge(url)) return chain.proceed(request)
        val started = EventLog.now()
        val event = JSONObject()
            .put("kind", "network").put("at", started).put("method", request.method).put("url", url).put("type", "fetch")
            .put("requestHeaders", headers(request.headers))
        request.body?.let { body ->
            if (!body.isOneShot() && !body.isDuplex() && body.contentLength() in 0..BODY_LIMIT.toLong() && isText(body.contentType())) {
                runCatching { Buffer().also { body.writeTo(it) }.readUtf8() }.getOrNull()?.let { event.put("requestBody", it) }
            }
        }
        val response = try {
            chain.proceed(request)
        } catch (error: IOException) {
            EventLog.add(event.put("status", 0).put("durationMs", (EventLog.now() - started).toInt())
                .put("error", error.message ?: error.javaClass.simpleName))
            throw error
        }
        event.put("status", response.code).put("durationMs", (EventLog.now() - started).toInt())
            .put("responseHeaders", headers(response.headers))
        if (isText(response.body?.contentType())) {
            runCatching { response.peekBody(BODY_LIMIT + 1L).string() }.getOrNull()?.let { text ->
                if (text.length > BODY_LIMIT) event.put("truncated", true)
                event.put("responseBody", text.take(BODY_LIMIT))
            }
        }
        EventLog.add(event)
        return response
    }

    companion object {
        const val BODY_LIMIT = 65_536

        fun headers(headers: Headers): JSONArray =
            JSONArray(headers.map { (name, value) -> JSONArray(listOf(name.lowercase(), value)) })

        /** Images, video and other binary bodies aren't kept: only what reads as text. */
        fun isText(type: MediaType?): Boolean {
            type ?: return true
            if (type.type == "text") return true
            val sub = type.subtype.lowercase()
            return listOf("json", "xml", "x-www-form-urlencoded", "graphql", "javascript").any { it in sub }
        }
    }
}
