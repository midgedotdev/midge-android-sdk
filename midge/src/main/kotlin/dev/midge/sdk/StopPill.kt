package dev.midge.sdk

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * The red Stop pill with the timer, at the top of whichever of the app's screens the person is on while they record.
 * It's a view in the activity's own window (no "draw over other apps" permission), moved along as they navigate.
 */
internal object StopPill {
    private var view: View? = null
    private var time: TextView? = null
    private var shown = false

    private val follow: (Activity?) -> Unit = { activity -> if (shown && activity != null) attach(activity) }
    private val update: () -> Unit = {
        val s = ReportFlow.elapsedSeconds
        time?.text = String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    fun show() {
        if (shown) return
        shown = true
        Foreground.observe(follow)
        ReportFlow.observe(update)
        Foreground.activity?.let { attach(it) }
    }

    fun hide() {
        if (!shown) return
        shown = false
        Foreground.stopObserving(follow)
        ReportFlow.stopObserving(update)
        detach()
    }

    private fun detach() {
        view?.let { (it.parent as? ViewGroup)?.removeView(it) }
        view = null
        time = null
    }

    private fun attach(activity: Activity) {
        if (activity is ReportActivity) return
        val root = activity.window?.decorView as? FrameLayout ?: return
        detach()
        val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, activity.resources.displayMetrics).toInt() }
        val pill = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(8f), dp(16f), dp(8f))
            background = GradientDrawable().apply { cornerRadius = dp(20f).toFloat(); setColor(Color.rgb(0xE5, 0x39, 0x35)) }
            elevation = dp(6f).toFloat()
            contentDescription = "Stop recording the bug report"
            isClickable = true
            setOnClickListener { ReportFlow.stop() }
        }
        pill.addView(View(activity).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        }, LinearLayout.LayoutParams(dp(8f), dp(8f)).apply { marginEnd = dp(8f) })
        val clock = TextView(activity).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            fontFeatureSettings = "tnum"
        }
        pill.addView(clock)
        pill.addView(TextView(activity).apply {
            text = "Stop"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10f) })
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        params.topMargin = dp(8f) + statusBar(root)
        root.addView(pill, params)
        view = pill
        time = clock
        update()
    }

    private fun statusBar(root: View): Int {
        val insets = root.rootWindowInsets ?: return 0
        return if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.statusBars()).top
        else @Suppress("DEPRECATION") insets.systemWindowInsetTop
    }
}
