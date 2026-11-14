package dev.midge.sdk

import android.os.Process
import org.json.JSONObject
import java.util.Locale

/**
 * The app's own logcat lines, read when a report is sent: an app may read its own process's log without any
 * permission (`logcat --pid`).
 *
 * Android's own code logs into the same process (ViewRootImpl, OpenGLRenderer, the input method…): many lines a
 * minute that would bury the app's own and count as its errors, as Apple's did on iPhone. Those tags are left out.
 */
internal object AppLogs {
    private val LINE = Regex("""^\s*(\d+\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEFA])\s+(.*?)\s*:\s?(.*)$""")

    /** Tags Android, AndroidX and common platform libraries log under from inside any app. */
    private val FRAMEWORK = setOf(
        "ActivityThread", "AppCompatDelegate", "AssistStructure", "AutofillManager", "BLASTBufferQueue", "Choreographer",
        "CompatChangeReporter", "ContentCaptureHelper", "Conscrypt", "DecorView", "EGL_emulation", "FrameEvents",
        "FrameTracker", "Gralloc4", "HWUI", "HostConnection", "ImeTracker", "InputEventReceiver", "InputMethodManager",
        "InsetsController", "InsetsSourceConsumer", "InteractionJankMonitor", "MediaCodec", "MediaRecorder", "MSHandlerLifeCycle",
        "NetworkSecurityConfig", "OpenGLRenderer", "PhoneWindow", "ProfileInstaller", "RenderInspector", "RemoteInputConnectionImpl",
        "SurfaceComposerClient", "SurfaceControl", "SurfaceView", "TextView", "ThreadedRenderer", "ToolbarActionBar",
        "VRI", "ViewRootImpl", "WindowManager", "WindowOnBackDispatcher", "ashmem", "cr_", "chromium", "libEGL",
        "nativeloader", "studio.deploy", "TrafficStats", "ziparchive", "Zygote", "HidlServiceManagement",
        "AudioSystem", "AudioTrack", "MediaPlayerNative", "MediaPlayer", "CCodec", "Codec2Client", "BufferPoolAccessor",
        "ScreenCapture", "VirtualDisplayAdapter", "MediaProjection", "JavaScriptSandbox", "WebViewFactory", "cr_WebView",
        "MidgeSDK", "OkHttp", "ProcessState",
    )

    fun events(fromMs: Double, toMs: Double, limit: Int = 2000): List<JSONObject> {
        val since = String.format(Locale.US, "%.3f", fromMs / 1000)
        val lines = runCatching {
            val process = ProcessBuilder("logcat", "-d", "-v", "epoch", "--pid=${Process.myPid()}", "-T", since)
                .redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { it.readLines() }.also { process.destroy() }
        }.getOrElse { return emptyList() }
        return parse(lines, fromMs, toMs, limit)
    }

    fun parse(lines: List<String>, fromMs: Double, toMs: Double, limit: Int = 2000): List<JSONObject> {
        val out = ArrayDeque<JSONObject>()
        for (line in lines) {
            val m = LINE.find(line) ?: continue
            val (time, _, _, level, tag, message) = m.destructured
            val at = time.toDouble() * 1000
            if (at < fromMs || at > toMs || isFramework(tag)) continue
            out.addLast(JSONObject().put("kind", "console").put("at", at).put("level", level(level)).put("text", "[$tag] $message"))
            if (out.size > limit) out.removeFirst()
        }
        return out.toList()
    }

    fun isFramework(tag: String): Boolean =
        tag in FRAMEWORK || tag.substringBefore('[') in FRAMEWORK || tag.startsWith("cr_") || tag.startsWith("android.") || tag.startsWith("androidx.")

    fun level(letter: String): String = when (letter) {
        "E", "F", "A" -> "error"
        "W" -> "warn"
        "D", "V" -> "debug"
        else -> "info"
    }
}
