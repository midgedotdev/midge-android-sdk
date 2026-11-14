package dev.midge.sdk

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.TimeZone

/** Sends one report: the video, the log bundle for its time, the person's words. */
internal class Sender(private val context: Context, private val client: RecordingLinkClient, private val includeLogs: Boolean) {
    class Report(
        val video: File,
        val startedAt: Long,
        val endedAt: Long,
        val name: String,
        val email: String,
        val description: String,
    )

    class Sent(val id: String, val logsAttached: Boolean)

    suspend fun send(
        report: Report,
        redaction: RecordingLinkClient.Redaction?,
        metadata: JSONObject,
        progress: (Double) -> Unit,
    ): Sent {
        val videoSize = report.video.length()
        if (videoSize <= 0) throw RecordingLinkClient.Failure("The recording is empty. Record it again.")
        val device = device(context)
        val events = buildList {
            addAll(EventLog.between(report.startedAt.toDouble(), report.endedAt.toDouble()))
            if (includeLogs) addAll(AppLogs.events(report.startedAt.toDouble(), report.endedAt.toDouble()))
            if (metadata.length() > 0) add(JSONObject().put("kind", "metadata").put("at", report.startedAt.toDouble()).put("data", metadata))
        }.sortedBy { it.optDouble("at", 0.0) }
        val bundle = runCatching { Bundler.build(context, events, report.startedAt, report.endedAt, device, redaction) }.getOrNull()
        val logsFile = bundle?.let { File(context.cacheDir, "midge-logs-${System.nanoTime()}.json.gz").apply { writeBytes(it.gzip) } }
        try {
            val body = JSONObject()
                .put("name", report.name).put("email", report.email).put("description", report.description)
                .put("content_type", "video/mp4").put("size", videoSize)
                .put("duration_ms", report.endedAt - report.startedAt)
                .put("page_title", device["app"].orEmpty().take(300))
            if (logsFile != null) body.put("logs_size", logsFile.length())
            val started = client.submit(body)
            val logsBytes = logsFile?.length() ?: 0L
            val total = (videoSize + logsBytes).toDouble()
            val logsTarget = started.uploads["logs"]
            if (logsFile != null && logsTarget != null) client.upload(logsFile, logsTarget) { progress(it / total) }
            client.upload(report.video, started.uploads["video"] ?: started.upload) { progress((logsBytes + it) / total) }
            client.finish(started.finish)
            return Sent(started.id, logsFile != null)
        } finally {
            logsFile?.delete()
        }
    }

    companion object {
        fun device(context: Context): Map<String, String> {
            val pm = context.packageManager
            val info = runCatching {
                if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
                else @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, 0)
            }.getOrNull()
            val build = info?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toString() else @Suppress("DEPRECATION") it.versionCode.toString() }
            val metrics = context.resources.displayMetrics
            return mapOf(
                "app" to context.applicationInfo.loadLabel(pm).toString(),
                "appVersion" to "${info?.versionName.orEmpty()} (${build.orEmpty()})",
                "bundleId" to context.packageName,
                "model" to listOf(Build.MANUFACTURER.replaceFirstChar { it.titlecase(Locale.ROOT) }, Build.MODEL).distinct().joinToString(" "),
                "os" to "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                "screen" to "${metrics.widthPixels}×${metrics.heightPixels}",
                "locale" to Locale.getDefault().toLanguageTag(),
                "timezone" to TimeZone.getDefault().id,
            )
        }
    }
}
