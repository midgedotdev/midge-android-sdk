package dev.midge.sdk

import android.content.Context
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptSandbox
import kotlinx.coroutines.guava.await
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Turns what was captured between two moments into a gzip log bundle with the extension's own code
 * (`midge-bundle.js`, the same file the Mac app and MidgeSDK for iPhone run in JavaScriptCore), here in Jetpack's
 * JavaScriptSandbox: the WebView's V8, in an isolated process. The rules stay in one place; nothing is translated to
 * Kotlin.
 */
internal object Bundler {
    class Result(val gzip: ByteArray, val consoleErrors: Int, val failedRequests: Int)

    /** Without the transaction-limit feature, what crosses to the sandbox has to stay under Binder's 1 MB. */
    private const val SMALL_INPUT = 700_000

    fun isSupported(): Boolean = runCatching { JavaScriptSandbox.isSupported() }.getOrDefault(false)

    suspend fun build(
        context: Context,
        events: List<JSONObject>,
        startedAt: Long,
        endedAt: Long,
        device: Map<String, String>,
        redaction: RecordingLinkClient.Redaction?,
    ): Result? {
        if (!isSupported()) return null
        val script = context.assets.open("midge-bundle.js").bufferedReader().use { it.readText() }
        val options = JSONObject().put("startedAt", startedAt).put("endedAt", endedAt).put("pauses", JSONArray())
            .put("device", JSONObject(device as Map<*, *>)).put("source", "sdk-android")
        redaction?.let { options.put("redaction", JSONObject().put("fields", JSONArray(it.fields)).put("headers", JSONArray(it.headers))) }

        val sandbox = JavaScriptSandbox.createConnectedInstanceAsync(context.applicationContext).await()
        try {
            val unlimited = sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT)
            val parameters = IsolateStartupParameters().apply {
                if (unlimited) maxEvaluationReturnSizeBytes = 64 * 1024 * 1024
            }
            val isolate = sandbox.createIsolate(parameters)
            try {
                var input = JSONArray(events).toString()
                if (!unlimited && input.length > SMALL_INPUT) {  // bodies first, then the oldest events
                    var kept = withoutBodies(events)
                    input = JSONArray(kept).toString()
                    while (input.length > SMALL_INPUT && kept.isNotEmpty()) {
                        kept = kept.drop(kept.size / 4 + 1)
                        input = JSONArray(kept).toString()
                    }
                }
                isolate.evaluateJavaScriptAsync(script).await()
                val output = isolate.evaluateJavaScriptAsync(
                    "midgeBuildBundle(${JSONObject.quote(input)}, ${JSONObject.quote(options.toString())})"
                ).await()
                val json = JSONObject(output)
                val summary = json.optJSONObject("summary") ?: JSONObject()
                return Result(gzip(json.getJSONObject("bundle").toString().toByteArray()), summary.optInt("consoleErrors"), summary.optInt("failedRequests"))
            } finally {
                isolate.close()
            }
        } finally {
            sandbox.close()
        }
    }

    private fun withoutBodies(events: List<JSONObject>): List<JSONObject> = events.map { event ->
        JSONObject(event.toString()).apply { remove("requestBody"); remove("responseBody") }
    }

    fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()
}
