package me.magnum.melonds.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.magnum.melonds.MelonDSAndroidInterface
import me.magnum.melonds.MelonEmulator
import me.magnum.melonds.domain.model.SaveStateSlot
import me.magnum.melonds.domain.model.VideoRenderer
import me.magnum.melonds.impl.emulator.debug.RendererDebugCaptureLogger
import java.io.File
import java.util.Locale

internal class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                handleIntent(context.applicationContext, intent)
            } catch (error: Exception) {
                Log.w(TAG, "Debug command failed: action=${intent.action}", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleIntent(context: Context, intent: Intent) {
        val entryPoint = DebugCommandEntryPoint.resolve(context)
        when (intent.action) {
            context.debugCommandAction(ACTION_SET_RENDERER_SUFFIX) -> handleSetRenderer(entryPoint, intent)
            context.debugCommandAction(ACTION_SET_IR_SUFFIX) -> handleSetInternalResolution(entryPoint, intent)
            context.debugCommandAction(ACTION_SET_JIT_SUFFIX) -> handleSetJit(entryPoint, intent)
            context.debugCommandAction(ACTION_SET_VULKAN_FALLBACKS_SUFFIX) -> handleSetVulkanFallbacks(intent)
            context.debugCommandAction(ACTION_LOAD_STATE_SUFFIX) -> handleLoadState(context, entryPoint, intent)
            context.debugCommandAction(ACTION_DUMP_RENDERER_CAPTURE_SUFFIX) -> handleDumpRendererCapture(context, entryPoint, intent)
            else -> Log.w(TAG, "Ignored unknown action=${intent.action}")
        }
    }

    private fun handleSetRenderer(entryPoint: DebugCommandEntryPoint, intent: Intent) {
        val rendererName = intent.firstStringExtra(EXTRA_RENDERER, EXTRA_VALUE)
            ?: throw IllegalArgumentException("Missing renderer extra")
        val renderer = parseRenderer(rendererName)
            ?: throw IllegalArgumentException("Unsupported renderer=$rendererName")
        entryPoint.sharedPreferences().edit(commit = true) {
            putString(KEY_VIDEO_RENDERER, renderer.name.lowercase(Locale.US))
        }
        val refreshed = DebugCommandStateStore.requestSettingsRefresh()
        Log.w(TAG, "action=set_renderer renderer=${renderer.name.lowercase(Locale.US)} refreshed=${if (refreshed) 1 else 0}")
    }

    private fun handleSetInternalResolution(entryPoint: DebugCommandEntryPoint, intent: Intent) {
        val scale = intent.firstIntExtra(EXTRA_SCALE, EXTRA_IR, EXTRA_VALUE)
        require(scale in 1..8) { "Unsupported internal resolution=$scale" }
        entryPoint.sharedPreferences().edit(commit = true) {
            putString(KEY_VIDEO_INTERNAL_RESOLUTION, scale.toString())
        }
        val refreshed = DebugCommandStateStore.requestSettingsRefresh()
        Log.w(TAG, "action=set_ir scale=$scale refreshed=${if (refreshed) 1 else 0}")
    }

    private fun handleSetJit(entryPoint: DebugCommandEntryPoint, intent: Intent) {
        val enabled = intent.firstBooleanExtra(EXTRA_ENABLED, EXTRA_VALUE)
            ?: throw IllegalArgumentException("Missing enabled extra")
        entryPoint.sharedPreferences().edit(commit = true) {
            putBoolean(KEY_ENABLE_JIT, enabled)
        }
        val refreshed = DebugCommandStateStore.requestSettingsRefresh()
        Log.w(TAG, "action=set_jit enabled=${if (enabled) 1 else 0} refreshed=${if (refreshed) 1 else 0}")
    }

    private fun handleSetVulkanFallbacks(intent: Intent) {
        val forceTimelineOff = intent.firstBooleanExtra(EXTRA_TIMELINE_OFF)
            ?: intent.firstBooleanExtra(EXTRA_TIMELINE)?.not()
            ?: false
        val forceDynamicIndexingOff = intent.firstBooleanExtra(EXTRA_DYNAMIC_INDEXING_OFF)
            ?: intent.firstBooleanExtra(EXTRA_DYNAMIC_INDEXING)?.not()
            ?: false
        MelonDSAndroidInterface.setVulkanCompatibilityOverrides(
            disableTimelineSemaphores = forceTimelineOff,
            disableDynamicTextureIndexing = forceDynamicIndexingOff,
        )
        val refreshed = DebugCommandStateStore.requestSettingsRefresh()
        Log.w(
            TAG,
            "action=set_vulkan_fallbacks timelineOff=${if (forceTimelineOff) 1 else 0} dynamicIndexingOff=${if (forceDynamicIndexingOff) 1 else 0} refreshed=${if (refreshed) 1 else 0}",
        )
    }

    private suspend fun handleLoadState(
        context: Context,
        entryPoint: DebugCommandEntryPoint,
        intent: Intent,
    ) {
        val stateUri = resolveStateUri(context, entryPoint, intent)
            ?: throw IllegalArgumentException("Missing load target. Provide slot or path.")
        val pauseAfterLoad = intent.getBooleanExtra(EXTRA_PAUSE_AFTER, false)
        MelonEmulator.pauseEmulation()
        val success = try {
            MelonEmulator.loadState(stateUri)
        } finally {
            if (pauseAfterLoad) {
                DebugCommandStateStore.setDebugPauseHeld(true)
            } else {
                DebugCommandStateStore.setDebugPauseHeld(false)
                MelonEmulator.resumeEmulation()
            }
        }
        Log.w(
            TAG,
            "action=load_state uri=$stateUri success=${if (success) 1 else 0} pauseAfter=${if (pauseAfterLoad) 1 else 0}",
        )
    }

    private suspend fun handleDumpRendererCapture(
        context: Context,
        entryPoint: DebugCommandEntryPoint,
        intent: Intent,
    ) {
        entryPoint.sharedPreferences().edit(commit = true) {
            putBoolean(KEY_RENDERER_DEBUG_TOOLS_ENABLED, true)
        }
        val refreshed = DebugCommandStateStore.requestSettingsRefresh()
        if (refreshed) {
            delay(350L)
        }

        val renderer = entryPoint.settingsRepository().getCurrentVideoRenderer()
        val outputDir = File(context.cacheDir, "renderer-debug-captures")
        val pauseWasHeld = DebugCommandStateStore.isDebugPauseHeld()
        val resumeMs = intent.firstNullableIntExtra(EXTRA_RESUME_MS, EXTRA_DURATION_MS)?.coerceAtLeast(0) ?: 0
        if (!pauseWasHeld) {
            MelonEmulator.pauseEmulation()
        } else if (resumeMs > 0) {
            DebugCommandStateStore.setDebugPauseHeld(false)
            MelonEmulator.resumeEmulation()
            delay(resumeMs.toLong())
            MelonEmulator.pauseEmulation()
        }
        val result = try {
            RendererDebugCaptureLogger.dumpPauseMenuCapture(
                configuredRenderer = renderer,
                outputDir = outputDir,
            )
        } finally {
            DebugCommandStateStore.setDebugPauseHeld(false)
            MelonEmulator.resumeEmulation()
        }
        Log.w(
            TAG,
            "action=dump_renderer_capture renderer=${renderer.name.lowercase(Locale.US)} refreshed=${if (refreshed) 1 else 0} paused=${if (pauseWasHeld) 1 else 0} resumeMs=$resumeMs captureId=${result.captureId} success=${if (result.success) 1 else 0} outputDir=${result.outputDir?.absolutePath ?: "none"}",
        )
    }

    private suspend fun resolveStateUri(
        context: Context,
        entryPoint: DebugCommandEntryPoint,
        intent: Intent,
    ): Uri? {
        intent.firstStringExtra(EXTRA_PATH, EXTRA_URI)?.let { pathOrUri ->
            return parseUri(pathOrUri)
        }

        val slot = intent.firstNullableIntExtra(EXTRA_SLOT, EXTRA_VALUE) ?: return null
        require(slot in 0..8) { "Unsupported save state slot=$slot" }

        val romUri = intent.firstStringExtra(EXTRA_ROM_URI)?.let(Uri::parse)
            ?: DebugCommandStateStore.getLastRomUri(context)
            ?: return null
        val rom = entryPoint.romsRepository().getRomAtUri(romUri) ?: return null
        return entryPoint.saveStatesRepository().getRomSaveStateUri(
            rom,
            SaveStateSlot(slot, exists = true, lastUsedDate = null, screenshot = null),
        )
    }

    private fun parseRenderer(value: String): VideoRenderer? {
        return when (value.trim().lowercase(Locale.US)) {
            "software", "soft" -> VideoRenderer.SOFTWARE
            "opengl", "gl" -> VideoRenderer.OPENGL
            "vulkan", "vk" -> VideoRenderer.VULKAN
            else -> null
        }
    }

    private fun parseUri(pathOrUri: String): Uri {
        val file = File(pathOrUri)
        return if (file.isAbsolute) {
            Uri.fromFile(file)
        } else {
            Uri.parse(pathOrUri)
        }
    }

    private fun Intent.firstStringExtra(vararg keys: String): String? {
        return keys.firstNotNullOfOrNull { key ->
            getStringExtra(key)?.takeIf { value -> value.isNotBlank() }
        }
    }

    private fun Intent.firstIntExtra(vararg keys: String): Int {
        return firstNullableIntExtra(*keys)
            ?: throw IllegalArgumentException("Missing integer extra. Tried keys=${keys.joinToString()}")
    }

    private fun Intent.firstNullableIntExtra(vararg keys: String): Int? {
        keys.forEach { key ->
            if (!hasExtra(key)) {
                return@forEach
            }

            val raw = extras?.get(key)
            when (raw) {
                is Int -> return raw
                is String -> raw.toIntOrNull()?.let { return it }
            }
        }

        return null
    }

    private fun Intent.firstBooleanExtra(vararg keys: String): Boolean? {
        keys.forEach { key ->
            if (!hasExtra(key)) {
                return@forEach
            }

            val raw = extras?.get(key)
            when (raw) {
                is Boolean -> return raw
                is String -> when (raw.trim().lowercase(Locale.US)) {
                    "1", "true", "on", "yes", "enabled" -> return true
                    "0", "false", "off", "no", "disabled" -> return false
                }
                is Int -> return raw != 0
            }
        }

        return null
    }

    private companion object {
        private const val TAG = "DebugCommand"
        private const val KEY_VIDEO_RENDERER = "video_renderer"
        private const val KEY_VIDEO_INTERNAL_RESOLUTION = "video_internal_resolution"
        private const val KEY_ENABLE_JIT = "enable_jit"
        private const val KEY_RENDERER_DEBUG_TOOLS_ENABLED = "video_renderer_debug_tools_enabled"

        private const val EXTRA_RENDERER = "renderer"
        private const val EXTRA_SCALE = "scale"
        private const val EXTRA_IR = "ir"
        private const val EXTRA_ENABLED = "enabled"
        private const val EXTRA_TIMELINE = "timeline"
        private const val EXTRA_TIMELINE_OFF = "timeline_off"
        private const val EXTRA_DYNAMIC_INDEXING = "dynamic_indexing"
        private const val EXTRA_DYNAMIC_INDEXING_OFF = "dynamic_indexing_off"
        private const val EXTRA_SLOT = "slot"
        private const val EXTRA_PATH = "path"
        private const val EXTRA_URI = "uri"
        private const val EXTRA_ROM_URI = "rom_uri"
        private const val EXTRA_PAUSE_AFTER = "pause_after"
        private const val EXTRA_RESUME_MS = "resume_ms"
        private const val EXTRA_DURATION_MS = "duration_ms"
        private const val EXTRA_VALUE = "value"

        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private const val ACTION_SET_RENDERER_SUFFIX = "SET_RENDERER"
        private const val ACTION_SET_IR_SUFFIX = "SET_IR"
        private const val ACTION_SET_JIT_SUFFIX = "SET_JIT"
        private const val ACTION_SET_VULKAN_FALLBACKS_SUFFIX = "SET_VULKAN_FALLBACKS"
        private const val ACTION_LOAD_STATE_SUFFIX = "LOAD_STATE"
        private const val ACTION_DUMP_RENDERER_CAPTURE_SUFFIX = "DUMP_RENDERER_CAPTURE"
    }

    private fun Context.debugCommandAction(suffix: String): String {
        return "$packageName.$suffix"
    }
}
