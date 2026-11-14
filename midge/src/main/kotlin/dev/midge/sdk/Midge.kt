package dev.midge.sdk

import android.app.Application
import android.content.Context
import android.util.Log
import okhttp3.Interceptor
import org.json.JSONObject

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
