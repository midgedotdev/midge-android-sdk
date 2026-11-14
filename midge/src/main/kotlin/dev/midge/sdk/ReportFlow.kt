package dev.midge.sdk

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The report from start to Send: a sheet to start, the screen recorded with a Stop pill over the app, then the
 * person's words and Send. Kept outside the Activity so a rotation or the recording itself doesn't lose it. Main
 * thread, except the network work.
 */
internal object ReportFlow {
    sealed class Phase {
        data object Idle : Phase()
        data object Intro : Phase()
        data object Recording : Phase()
        data object Form : Phase()
        data class Sending(val progress: Double) : Phase()
        data object Sent : Phase()
        data class Failed(val message: String) : Phase()
    }

    var phase: Phase = Phase.Idle
        private set
    var workspace = ""
        private set
    var problem: String? = null
        private set
    var unregistered = false
        private set
    var logsMissing = false
        private set
    var video: File? = null
        private set
    var elapsedSeconds = 0
        private set
    var name = ""
    var email = ""
    var details = ""

    private var app: Application? = null
    private var info: RecordingLinkClient.Info? = null
    private var startedAt = 0L
    private var endedAt = 0L
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val listeners = mutableListOf<() -> Unit>()
    private val tick = object : Runnable {
        override fun run() {
            if (phase != Phase.Recording) return
            elapsedSeconds++
            changed()
            if (elapsedSeconds * 1000L >= (info?.maxDurationMs ?: 900_000)) stop() else main.postDelayed(this, 1000)
        }
    }

    fun observe(listener: () -> Unit) = listeners.add(listener)
    fun stopObserving(listener: () -> Unit) = listeners.remove(listener)
    private fun changed() = listeners.toList().forEach { it() }

    private fun client(): RecordingLinkClient? {
        val config = Midge.config ?: return null
        return RecordingLinkClient(config.options.origin, config.token, config.app.packageName)
    }

    fun begin(app: Application) {
        main.post {
            when (phase) {
                Phase.Idle -> Unit
                Phase.Recording -> return@post
                else -> return@post openSheet(app)  // already open somewhere: bring it back
            }
            this.app = app
            val person = Midge.person
            name = person.first
            email = person.second
            details = ""
            problem = null
            unregistered = false
            phase = Phase.Intro
            openSheet(app)
            val client = client() ?: return@post
            scope.launch {
                try {
                    val info = withContext(Dispatchers.IO) { client.info() }
                    this@ReportFlow.info = info
                    workspace = info.workspace
                    if (info.siteVerified == false) {
                        unregistered = true
                        problem = "This app isn’t registered with ${info.workspace} yet. An admin adds ${client.app} under Settings › Workspace › Recording links on midge.dev."
                    }
                } catch (error: Exception) {
                    problem = if (error is RecordingLinkClient.Failure) error.message else "Midge can’t be reached. Check your connection."
                }
                changed()
            }
        }
    }

    private fun openSheet(app: Application) {
        val from: Activity? = Foreground.activity
        val intent = Intent(from ?: app, ReportActivity::class.java)
        if (from == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        (from ?: app).startActivity(intent)
    }

    /** The person allowed the recording in Android's dialog. */
    fun allowed(activity: Activity, resultCode: Int, data: Intent) {
        RecordingService.start(activity, resultCode, data)
    }

    fun recordingStarted() { main.post {
        startedAt = System.currentTimeMillis()
        elapsedSeconds = 0
        phase = Phase.Recording
        StopPill.show()
        changed()
        main.postDelayed(tick, 1000)
    } }

    fun stop() { main.post {
        if (phase != Phase.Recording) return@post
        endedAt = System.currentTimeMillis()
        main.removeCallbacks(tick)
        StopPill.hide()
        app?.let { RecordingService.stop(it) }
    } }

    fun recorded(file: File) { main.post {
        StopPill.hide()
        if (phase == Phase.Idle) return@post run { file.delete() }  // cancelled while recording
        if (endedAt < startedAt) endedAt = System.currentTimeMillis()  // stopped from the notification or the system
        video = file
        phase = Phase.Form
        changed()
        app?.let { openSheet(it) }
    } }

    fun recordingFailed(message: String) { main.post {
        StopPill.hide()
        main.removeCallbacks(tick)
        if (phase == Phase.Idle) return@post
        problem = message
        phase = Phase.Intro
        changed()
        app?.let { openSheet(it) }
    } }

    fun send() {
        val app = app ?: return
        val video = video ?: return
        val config = Midge.config ?: return
        val client = client() ?: return
        val report = Sender.Report(video, startedAt, endedAt, name.trim(), email.trim(), details.trim())
        phase = Phase.Sending(0.0)
        changed()
        scope.launch {
            try {
                val sent = withContext(Dispatchers.IO) {
                    Sender(app, client, config.options.captureLogs).send(report, info?.redaction, Midge.context) { value ->
                        main.post { if (phase is Phase.Sending) { phase = Phase.Sending(value); changed() } }
                    }
                }
                logsMissing = !sent.logsAttached
                video.delete()
                this@ReportFlow.video = null
                phase = Phase.Sent
                changed()
                delay(if (logsMissing) 4000 else 1600)
                if (phase == Phase.Sent) close()
            } catch (error: Exception) {
                phase = Phase.Failed(error.message ?: "The report wasn’t sent. Try again.")
                changed()
            }
        }
    }

    fun close() {
        main.removeCallbacks(tick)
        if (phase == Phase.Recording) app?.let { RecordingService.stop(it) }
        StopPill.hide()
        video?.delete()
        video = null
        phase = Phase.Idle
        changed()
    }
}
