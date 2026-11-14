package dev.midge.sdk

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * Which of the app's activities is on screen: shaking only counts while the app is in the foreground, and the Stop
 * pill sits on whichever activity the person is in while they record.
 */
internal object Foreground {
    private var installed = false
    private var started = 0
    private var resumed: WeakReference<Activity>? = null
    private val listeners = mutableListOf<(Activity?) -> Unit>()

    val activity: Activity? get() = resumed?.get()
    val inForeground: Boolean get() = started > 0

    fun install(app: Application) {
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (started++ == 0) Shake.resume()
            }
            override fun onActivityStopped(activity: Activity) {
                if (--started == 0) Shake.pause()
            }
            override fun onActivityResumed(activity: Activity) {
                resumed = WeakReference(activity)
                listeners.toList().forEach { it(activity) }
            }
            override fun onActivityPaused(activity: Activity) {
                if (resumed?.get() === activity) resumed = null
                listeners.toList().forEach { it(null) }
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** Called with the newly resumed activity, or null when one pauses. Main thread. */
    fun observe(listener: (Activity?) -> Unit) = listeners.add(listener)
    fun stopObserving(listener: (Activity?) -> Unit) = listeners.remove(listener)
}
