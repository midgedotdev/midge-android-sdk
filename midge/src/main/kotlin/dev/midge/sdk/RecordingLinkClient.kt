package dev.midge.sdk

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * midge.dev's public recording-link endpoints (`/api/record/<token>/`), as the website SDK and MidgeSDK for iPhone use
 * them, with `X-Midge-App: <application id>`. Its own OkHttpClient, so the app's interceptors never see these calls.
 */
internal class RecordingLinkClient(
    origin: String,
    private val token: String,
    val app: String,
    private val http: OkHttpClient = DEFAULT_HTTP,
) {
    private val base = "${origin.trimEnd('/')}/api/record/$token/"

    class Failure(message: String, val code: String? = null) : IOException(message)

    class Redaction(val fields: List<String>, val headers: List<String>)

    class Info(
        val title: String,
        val workspace: String,
        val maxDurationMs: Long,
        val maxBytes: Long,
        val siteVerified: Boolean?,
        val redaction: Redaction?,
    )

    class Upload(val url: String, val chunkBytes: Int)

    class Started(val id: String, val upload: Upload, val uploads: Map<String, Upload>, val finish: String)

    private fun call(path: String, body: JSONObject?): JSONObject {
        val request = Request.Builder().url(base + path).header("X-Midge-App", app).header("Accept", "application/json")
            .apply { if (body != null) post(body.toString().toRequestBody(JSON)) }
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (!response.isSuccessful) throw refusal(response.code, json)
            return json
        }
    }

    /** midge.dev's refusal in plain words; a full workspace (507) gets its own sentence and code. */
    private fun refusal(status: Int, json: JSONObject): Failure {
        val detail = json.optString("detail")
        if (status == 507 || detail == WORKSPACE_FULL) return Failure(WORKSPACE_FULL_MESSAGE, WORKSPACE_FULL)
        return Failure(detail.ifEmpty { "Midge answered $status." }, json.optString("code").ifEmpty { null })
    }

    fun info(): Info {
        val json = call("", null)
        val redaction = json.optJSONObject("redaction")?.let { Redaction(it.optJSONArray("fields").strings(), it.optJSONArray("headers").strings()) }
        return Info(
            title = json.optString("title"),
            workspace = json.optString("workspace"),
            maxDurationMs = json.optLong("max_duration_ms", 900_000),
            maxBytes = json.optLong("max_bytes", 0),
            siteVerified = if (json.has("site_verified")) json.optBoolean("site_verified") else null,
            redaction = redaction,
        )
    }

    fun submit(body: JSONObject): Started {
        val json = call("submit/", body)
        fun upload(o: JSONObject) = Upload(o.getString("url"), o.optInt("chunk_bytes", 50 * 1024 * 1024))
        val uploads = json.optJSONObject("uploads")?.let { all -> all.keys().asSequence().associateWith { upload(all.getJSONObject(it)) } }.orEmpty()
        return Started(json.getString("id"), upload(json.getJSONObject("upload")), uploads, json.getString("finish"))
    }

    fun finish(claim: String) {
        call("finish/", JSONObject().put("finish", claim))
    }

    /** Resumable upload in chunks (PATCH with Upload-Offset), as the extension does. */
    fun upload(file: File, target: Upload, progress: (Long) -> Unit) {
        RandomAccessFile(file, "r").use { input ->
            val size = input.length()
            var offset = 0L
            val buffer = ByteArray(minOf(target.chunkBytes.toLong(), size.coerceAtLeast(1)).toInt())
            while (offset < size) {
                input.seek(offset)
                val n = input.read(buffer)
                if (n <= 0) break
                val request = Request.Builder().url(target.url)
                    .header("Upload-Offset", offset.toString())
                    .patch(buffer.copyOf(n).toRequestBody(OCTETS))
                    .build()
                http.newCall(request).execute().use { response ->
                    if (response.code == 429 || response.code == 507) {  // full workspace or daily limit: stop, say why
                        throw refusal(response.code, runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrElse { JSONObject() })
                    }
                    if (!response.isSuccessful && response.code != 409) throw Failure("The upload stopped. Check your connection and try again.")
                    offset = response.header("Upload-Offset")?.toLongOrNull() ?: (offset + n)
                }
                progress(offset)
            }
        }
    }

    companion object {
        /** midge.dev's detail when the workspace is over its storage budget (HTTP 507). */
        const val WORKSPACE_FULL = "workspace_storage_full"
        const val WORKSPACE_FULL_MESSAGE = "This workspace is out of storage and can’t take new reports. Let the people who shared this link know."
        private val JSON = "application/json".toMediaType()
        private val OCTETS = "application/offset+octet-stream".toMediaType()
        private val DEFAULT_HTTP = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS)
            .build()

        private fun JSONArray?.strings(): List<String> = this?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty()
    }
}
