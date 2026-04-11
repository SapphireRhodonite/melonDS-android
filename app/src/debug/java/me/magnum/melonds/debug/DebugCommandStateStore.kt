package me.magnum.melonds.debug

import android.content.Context
import android.net.Uri
import android.util.Log
import me.magnum.melonds.ui.emulator.EmulatorActivity
import me.magnum.melonds.ui.emulator.EmulatorViewModel
import java.lang.ref.WeakReference
import kotlin.Lazy

internal object DebugCommandStateStore {
    private const val TAG = "DebugCommand"
    private const val PREFERENCES_NAME = "debug_command_state"
    private const val KEY_LAST_ROM_URI = "last_rom_uri"

    @Volatile
    private var currentEmulatorActivity = WeakReference<EmulatorActivity>(null)

    @Volatile
    private var debugPauseHeld = false

    fun onEmulatorActivitySeen(context: Context, activity: EmulatorActivity) {
        currentEmulatorActivity = WeakReference(activity)
        val romUri = activity.intent?.data ?: return
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_ROM_URI, romUri.toString())
            .apply()
    }

    fun onEmulatorActivityDestroyed(activity: EmulatorActivity) {
        if (currentEmulatorActivity.get() === activity) {
            currentEmulatorActivity = WeakReference(null)
        }
    }

    fun getLastRomUri(context: Context): Uri? {
        val uri = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_ROM_URI, null)
            ?: return null
        return Uri.parse(uri)
    }

    fun requestSettingsRefresh(): Boolean {
        val activity = currentEmulatorActivity.get() ?: return false
        val viewModel = resolveEmulatorViewModel(activity) ?: return false
        activity.runOnUiThread {
            viewModel.onSettingsChanged()
        }
        return true
    }

    fun isDebugPauseHeld(): Boolean = debugPauseHeld

    fun setDebugPauseHeld(held: Boolean) {
        debugPauseHeld = held
    }

    private fun resolveEmulatorViewModel(activity: EmulatorActivity): EmulatorViewModel? {
        return try {
            val field = EmulatorActivity::class.java.getDeclaredField("viewModel\$delegate")
            field.isAccessible = true
            val delegate = field.get(activity) as? Lazy<*>
            delegate?.value as? EmulatorViewModel
        } catch (error: ReflectiveOperationException) {
            Log.w(TAG, "Failed to resolve EmulatorViewModel from EmulatorActivity", error)
            null
        }
    }
}
