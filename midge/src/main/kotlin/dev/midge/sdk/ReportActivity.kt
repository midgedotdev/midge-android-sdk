package dev.midge.sdk

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView

/**
 * The report sheet, drawn over the app: what will be recorded and Record, then (after Stop) the video, what happened,
 * name and email, Send. Plain views, light or dark like the system, so the SDK brings no UI library into the app.
 */
class ReportActivity : Activity() {
    private lateinit var sheet: LinearLayout
    private lateinit var body: FrameLayout
    private val render: () -> Unit = { if (!isFinishing) show() }
    private var shownPhase: ReportFlow.Phase? = null

    private val dark get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val surface get() = if (dark) Color.rgb(0x1E, 0x1E, 0x22) else Color.WHITE
    private val ink get() = if (dark) Color.rgb(0xF2, 0xF2, 0xF5) else Color.rgb(0x16, 0x16, 0x1A)
    private val muted get() = if (dark) Color.rgb(0xA0, 0xA0, 0xAA) else Color.rgb(0x5E, 0x5E, 0x68)
    private val field get() = if (dark) Color.rgb(0x2A, 0x2A, 0x30) else Color.rgb(0xF2, 0xF2, 0xF5)
    private val brand = Color.rgb(0xF2, 0x6B, 0x1D)
    private val danger = Color.rgb(0xE5, 0x39, 0x35)

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(0x66, 0, 0, 0))
            setOnClickListener { if (ReportFlow.phase == ReportFlow.Phase.Intro) cancel() }
        }
        sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(surface)
                cornerRadii = FloatArray(8) { i -> if (i < 4) dp(20f).toFloat() else 0f }
            }
            isClickable = true
            setPadding(dp(20f), dp(10f), dp(20f), dp(20f))
        }
        root.addView(sheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        // Edge to edge: the sheet keeps clear of the navigation bar and moves up with the keyboard.
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bottom = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime()).bottom
            else @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            val top = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars()).top
            else @Suppress("DEPRECATION") insets.systemWindowInsetTop
            sheet.setPadding(dp(20f), dp(10f), dp(20f), dp(20f) + bottom)
            (sheet.layoutParams as FrameLayout.LayoutParams).topMargin = top + dp(24f)
            sheet.requestLayout()
            insets
        }
        sheet.addView(View(this).apply {
            background = GradientDrawable().apply { cornerRadius = dp(3f).toFloat(); setColor(muted) }
            alpha = 0.4f
        }, LinearLayout.LayoutParams(dp(36f), dp(5f)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(10f) })
        sheet.addView(header())
        body = FrameLayout(this)
        sheet.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        ReportFlow.observe(render)
        show()
    }

    override fun onStop() {
        ReportFlow.stopObserving(render)
        super.onStop()
    }

    @Deprecated("Activity's own back handling; this class doesn't use AndroidX.")
    override fun onBackPressed() {
        when (ReportFlow.phase) {
            ReportFlow.Phase.Intro -> cancel()
            // A recording isn't thrown away by Back: the sheet steps aside, and the next shake or button brings it back.
            ReportFlow.Phase.Form, is ReportFlow.Phase.Failed -> finish()
            else -> Unit
        }
    }

    private fun cancel() {
        ReportFlow.close()
        finish()
    }

    private fun header() = FrameLayout(this).apply {
        addView(TextView(this@ReportActivity).apply {
            text = "Report a bug"
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(TextView(this@ReportActivity).apply {
            text = "Cancel"
            setTextColor(brand)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(10f), dp(10f), dp(10f))
            setOnClickListener { if (ReportFlow.phase !is ReportFlow.Phase.Sending) cancel() }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL))
        setPadding(0, 0, 0, dp(12f))
    }

    private fun show() {
        val phase = ReportFlow.phase
        when (phase) {
            ReportFlow.Phase.Idle -> return finish()
            ReportFlow.Phase.Recording -> return finish()  // the app is recorded, not this sheet
            else -> Unit
        }
        // The form keeps its views while typing; everything else is redrawn on each change.
        if (phase == ReportFlow.Phase.Form && shownPhase == ReportFlow.Phase.Form) return
        shownPhase = phase
        body.removeAllViews()
        body.addView(when (phase) {
            ReportFlow.Phase.Intro -> intro()
            ReportFlow.Phase.Form -> form()
            is ReportFlow.Phase.Sending -> sending(phase.progress)
            ReportFlow.Phase.Sent -> sent()
            is ReportFlow.Phase.Failed -> failed(phase.message)
            else -> View(this)
        })
    }

    private fun text(value: String, size: Float = 15f, color: Int = muted) = TextView(this).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setLineSpacing(0f, 1.15f)
    }

    private fun primary(label: String, enabled: Boolean = true, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.DEFAULT_BOLD
        stateListAnimator = null
        val shape = GradientDrawable().apply { cornerRadius = dp(14f).toFloat(); setColor(brand) }
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(0x33, 255, 255, 255)), shape, null)
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.45f
        minHeight = dp(52f)
        setOnClickListener { action() }
    }

    private fun column(vararg views: View, gap: Float = 12f) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) topMargin = dp(gap)
            })
        }
    }

    private fun intro(): View {
        val parts = mutableListOf<View>(
            text("Show what went wrong: Midge records this app’s screen until you tap Stop, then you add a few words.", 16f, ink),
            text("The app’s network requests and logs are attached, with passwords and tokens hidden.", 14f),
        )
        ReportFlow.problem?.let { parts += text(it, 14f, danger) }
        parts += primary("Record the screen", enabled = !ReportFlow.unregistered) { askToRecord() }
        return column(*parts.toTypedArray()).apply { setPadding(0, 0, 0, dp(4f)) }
    }

    private fun askToRecord() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        // The whole screen, so it's one tap: "one app" would make the person find this app in a list first.
        val intent = if (Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else manager.createScreenCaptureIntent()
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_CAPTURE)
    }

    @Deprecated("Activity's own result handling; this class doesn't use AndroidX.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) ReportFlow.allowed(this, resultCode, data)
    }

    private fun input(hint: String, value: String, type: Int, lines: Int = 1, onChange: (String) -> Unit) = EditText(this).apply {
        setText(value)
        this.hint = hint
        inputType = type
        setTextColor(ink)
        setHintTextColor(muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        background = GradientDrawable().apply { cornerRadius = dp(12f).toFloat(); setColor(field) }
        setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
        if (lines > 1) { minLines = lines; maxLines = 8; gravity = Gravity.TOP or Gravity.START }
        addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = onChange(s?.toString().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun form(): View {
        val video = ReportFlow.video
        val player = VideoView(this).apply {
            video?.let { setVideoURI(Uri.fromFile(it)) }
            setMediaController(MediaController(this@ReportActivity).also { it.setAnchorView(this) })
            setOnPreparedListener { it.isLooping = false; seekTo(1) }
        }
        val frame = FrameLayout(this).apply {
            background = GradientDrawable().apply { cornerRadius = dp(14f).toFloat(); setColor(Color.BLACK) }
            clipToOutline = true
            addView(player, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        }
        val content = column(
            frame,
            text("What happened?", 13f, muted).apply { typeface = Typeface.DEFAULT_BOLD },
            input("What did you expect, and what happened instead?", ReportFlow.details,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, lines = 3) { ReportFlow.details = it },
            input("Name (optional)", ReportFlow.name, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME or InputType.TYPE_TEXT_FLAG_CAP_WORDS) { ReportFlow.name = it },
            input("Email (optional)", ReportFlow.email, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS) { ReportFlow.email = it },
            text(if (ReportFlow.workspace.isEmpty()) "" else "Sent to ${ReportFlow.workspace} on Midge, with this app’s logs.", 13f),
            primary("Send") { ReportFlow.send() },
            gap = 10f,
        )
        (frame.layoutParams as LinearLayout.LayoutParams).height = dp(240f)
        return ScrollView(this).apply {
            isFillViewport = false
            addView(content)
        }
    }

    private fun centered(vararg views: View) = column(*views).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(24f), 0, dp(24f))
        for (i in 0 until childCount) (getChildAt(i) as? TextView)?.gravity = Gravity.CENTER
    }

    private fun sending(progress: Double) = centered(
        ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            this.progress = (progress * 1000).toInt()
            progressTintList = ColorStateList.valueOf(brand)
        },
        text("Sending… ${(progress * 100).toInt()}%", 15f),
    )

    private fun sent(): View {
        val parts = mutableListOf<View>(
            text("✓", 44f, Color.rgb(0x2E, 0xA0, 0x43)),
            text("Sent. Thank you!", 17f, ink).apply { typeface = Typeface.DEFAULT_BOLD },
        )
        // Logs are only sent redacted; without Android's JavaScript sandbox (an old WebView) they stay on the phone.
        if (ReportFlow.logsMissing) parts += text("The video was sent without the app’s logs: this phone’s Android System WebView needs an update.", 13f)
        return centered(*parts.toTypedArray())
    }

    private fun failed(message: String): View {
        val parts = mutableListOf<View>(
            text("Not sent", 17f, ink).apply { typeface = Typeface.DEFAULT_BOLD },
            text(message, 15f),
        )
        if (ReportFlow.video != null) parts += primary("Try again") { ReportFlow.send() }
        return centered(*parts.toTypedArray())
    }

    private companion object {
        const val REQUEST_CAPTURE = 4711
    }
}
