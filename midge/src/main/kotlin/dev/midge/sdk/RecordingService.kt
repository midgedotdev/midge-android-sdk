package dev.midge.sdk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Records the screen while the person shows the bug: MediaProjection into a MediaRecorder, an MP4 in the app's cache.
 * Android needs a foreground service of type mediaProjection for that, with a notification (it has a Stop button too).
 */
class RecordingService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var output: File? = null
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRecording()
            ACTION_START -> start(intent)
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIFICATION_ID, notification())
        val result = intent.getIntExtra(EXTRA_RESULT, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        val manager = getSystemService(MediaProjectionManager::class.java)
        val projection = data?.let { runCatching { manager.getMediaProjection(result, it) }.getOrNull() }
            ?: return fail("Android didn’t allow the recording. Try again.")
        this.projection = projection
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = Handler(Looper.getMainLooper()).post { stopRecording() }.let {}
        }, Handler(Looper.getMainLooper()))

        val (width, height, dpi) = size()
        val file = File(cacheDir, "midge-report-${System.currentTimeMillis()}.mp4")
        output = file
        val recorder = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()).apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(width, height)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(4_000_000)
            setOutputFile(file.absolutePath)
        }
        try {
            recorder.prepare()
            display = projection.createVirtualDisplay("Midge", width, height, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.surface, null, null)
            recorder.start()
        } catch (error: Exception) {
            recorder.release()
            return fail("The recording couldn’t start: ${error.message ?: error.javaClass.simpleName}")
        }
        this.recorder = recorder
        ReportFlow.recordingStarted()
    }

    /** The screen's size, scaled so the long side is at most 1920, in multiples of 16 (what encoders accept). */
    private fun size(): Triple<Int, Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        val metrics = DisplayMetrics()
        val (w, h) = if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.let { it.width() to it.height() }
        else @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics).let { metrics.widthPixels to metrics.heightPixels }
        val scale = minOf(1.0, 1920.0 / max(w, h))
        fun fit(v: Int) = max(16, ((v * scale) / 16).roundToInt() * 16)
        return Triple(fit(w), fit(h), resources.displayMetrics.densityDpi)
    }

    private fun stopRecording() {
        if (stopping) return
        stopping = true
        val ok = runCatching { recorder?.stop() }.isSuccess && recorder != null
        recorder?.release()
        recorder = null
        display?.release()
        display = null
        projection?.stop()
        projection = null
        val file = output
        if (ok && file != null && file.length() > 0) ReportFlow.recorded(file) else {
            file?.delete()
            ReportFlow.recordingFailed("The recording was too short or couldn’t be saved. Try again.")
        }
        finish()
    }

    private fun fail(message: String) {
        ReportFlow.recordingFailed(message)
        finish()
    }

    private fun finish() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        if (!stopping && recorder != null) stopRecording()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Bug report recording", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while you record the screen for a bug report."
            })
        }
        val stop = PendingIntent.getService(this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.midge_record)
            .setContentTitle("Recording a bug report")
            .setContentText("Show what went wrong, then tap Stop.")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "midge_recording"
        private const val NOTIFICATION_ID = 7431
        private const val ACTION_START = "dev.midge.sdk.START"
        private const val ACTION_STOP = "dev.midge.sdk.STOP"
        private const val EXTRA_RESULT = "result"
        private const val EXTRA_DATA = "data"

        internal fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, RecordingService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_RESULT, resultCode).putExtra(EXTRA_DATA, data)
            context.startForegroundService(intent)
        }

        internal fun stop(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))
        }
    }
}
