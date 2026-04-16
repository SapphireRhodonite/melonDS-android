package me.magnum.melonds.debug

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.startup.Initializer
import me.magnum.melonds.ui.emulator.EmulatorActivity

internal class DebugCommandInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        val application = context.applicationContext as? Application ?: return
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    trackActivity(context, activity)
                }

                override fun onActivityStarted(activity: Activity) {
                    trackActivity(context, activity)
                }

                override fun onActivityResumed(activity: Activity) {
                    trackActivity(context, activity)
                }

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

                override fun onActivityDestroyed(activity: Activity) {
                    if (activity is EmulatorActivity) {
                        DebugCommandStateStore.onEmulatorActivityDestroyed(activity)
                    }
                }
            },
        )
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()

    private fun trackActivity(context: Context, activity: Activity) {
        if (activity is EmulatorActivity) {
            DebugCommandStateStore.onEmulatorActivitySeen(context.applicationContext, activity)
        }
    }
}
