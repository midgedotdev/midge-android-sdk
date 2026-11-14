package dev.midge.sdk

import android.app.Application
import android.content.Context
import android.util.Log
import okhttp3.Interceptor
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * MidgeSDK's whole public surface.
 *
 * ```kotlin
 * Midge.start(this, link = "rl_…")          // in Application.onCreate
 * Midge.metadata(mapOf("plan" to "pro", "userId" to user.id))
 * Midge.user(name = user.name, email = user.email)
 * Midge.report()                            // from your own "Report a bug" button; shaking the phone also opens it
 * OkHttpClient.Builder().addInterceptor(Midge.okHttpInterceptor())
 * ```
 */
object Midge {
    class Options {
        /** Shaking the device opens the report sheet. */
        var shakeToReport = true
        /** Records the app's HTTP requests made through [okHttpInterceptor]. */
        var captureNetwork = true
        /** Adds the app's own logcat lines (and [log]) to the report. */
        var captureLogs = true
        /** midge.dev, or your own Midge server. */
        var origin = "https://midge.dev"
    }

    internal class Config(val token: String, val options: Options, val app: Application)

    @Volatile internal var config: Config? = null
        private set
    @Volatile internal var context: JSONObject = JSONObject()
        private set
    @Volatile internal var person: Pair<String, String> = "" to ""
        private set

    /**
     * Starts Midge with a recording link's token (Settings › Workspace › Recording links on midge.dev; the part after
     * `/record/`). Reports go to that link's workspace, once an admin registers this app's application id there.
     */
    @JvmStatic @JvmOverloads
    fun start(context: Context, link: String, options: Options = Options()) {
        val app = context.applicationContext as Application
        config = Config(link.trim(), options, app)
        Foreground.install(app)
        if (options.shakeToReport) Shake.install(app)
    }

    /** Add it to your OkHttpClient (Retrofit, Coil and Ktor's OkHttp engine use one) to put its requests in reports. */
    @JvmStatic
    fun okHttpInterceptor(): Interceptor = MidgeInterceptor()

    /**
     * `url.openConnection()` for code that uses HttpURLConnection (and Volley's HurlStack): the same connection, with
     * its request in reports.
     *
     * ```kotlin
     * val connection = Midge.openConnection(URL("https://api.example.com/cart"))
     * Volley.newRequestQueue(context, object : HurlStack() { override fun createConnection(url: URL) = Midge.openConnection(url) })
     * ```
     */
    @JvmStatic
    fun openConnection(url: URL): HttpURLConnection = watch(url.openConnection())

    /** An HttpURLConnection you opened yourself (with a proxy, say), watched the same way. Other URL schemes pass through. */
    @JvmStatic
    fun watch(connection: java.net.URLConnection): HttpURLConnection {
        val http = connection as? HttpURLConnection ?: throw IllegalArgumentException("Not an HTTP connection: ${connection.url}")
        return if (http is MidgeConnection) http else MidgeConnection(http)
    }

    /**
     * A request made with another client (Cronet, Ktor's own engines, gRPC…): call it when the response arrives. Headers
     * and bodies are optional; they're redacted on the phone like the rest.
     */
    @JvmStatic @JvmOverloads
    fun network(
        method: String,
        url: String,
        status: Int,
        durationMs: Long,
        requestHeaders: Map<String, String> = emptyMap(),
        responseHeaders: Map<String, String> = emptyMap(),
        requestBody: String? = null,
        responseBody: String? = null,
        error: String? = null,
    ) {
        if (config?.options?.captureNetwork == false || isMidge(url)) return
        val pairs = { m: Map<String, String> -> org.json.JSONArray(m.map { (k, v) -> org.json.JSONArray(listOf(k.lowercase(), v)) }) }
        val event = JSONObject().put("kind", "network").put("at", EventLog.now() - durationMs).put("method", method.uppercase())
            .put("url", url).put("type", "fetch").put("status", status).put("durationMs", durationMs)
            .put("requestHeaders", pairs(requestHeaders)).put("responseHeaders", pairs(responseHeaders))
        requestBody?.let { event.put("requestBody", it.take(MidgeInterceptor.BODY_LIMIT)) }
        responseBody?.let {
            event.put("responseBody", it.take(MidgeInterceptor.BODY_LIMIT))
            if (it.length > MidgeInterceptor.BODY_LIMIT) event.put("truncated", true)
        }
        error?.let { event.put("error", it) }
        EventLog.add(event)
    }

    /**
     * Context that's useful in every report (user id, plan, feature flags); the latest value is sent. Hidden fields
     * (tokens, passwords, your workspace's rules) are redacted like the rest of the logs.
     */
    @JvmStatic
    fun metadata(values: Map<String, Any?>) {
        context = runCatching { JSONObject(values) }.getOrElse { JSONObject() }
    }

    /** Who's reporting: fills the report sheet's name and email so they don't type them. */
    @JvmStatic @JvmOverloads
    fun user(name: String = "", email: String = "") {
        person = name to email
    }

    enum class Level(internal val value: String) { DEBUG("debug"), INFO("info"), WARN("warn"), ERROR("error") }

    /** A line in the report's console tab. */
    @JvmStatic @JvmOverloads
    fun log(message: String, level: Level = Level.INFO) {
        EventLog.add(JSONObject().put("kind", "console").put("at", EventLog.now()).put("level", level.value).put("text", message))
    }

    /** Opens the report sheet: the person records the screen, says what happened and sends. */
    @JvmStatic
    fun report() {
        val config = config ?: return run { Log.w("MidgeSDK", "Call Midge.start(context, link) before Midge.report().") }
        ReportFlow.begin(config.app)
    }

    internal fun isMidge(url: String): Boolean {
        val origin = config?.options?.origin?.trimEnd('/') ?: return false
        return url.startsWith("$origin/api/record/") || url.startsWith("$origin/api/uploads/")
    }
}
