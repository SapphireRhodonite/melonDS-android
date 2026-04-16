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
import me.magnum.melonds.domain.model.ControllerConfiguration
import me.magnum.melonds.domain.model.SaveStateSlot
import me.magnum.melonds.domain.model.VideoRenderer
import me.magnum.melonds.impl.emulator.debug.RendererDebugCaptureLogger
import me.magnum.melonds.impl.emulator.debug.RendererDebugBridge
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
            context.debugCommandAction(ACTION_SET_BGOBJ_LOG_SUFFIX) -> handleSetBgObjLog(entryPoint, intent)
            context.debugCommandAction(ACTION_SET_SLOT2_ANALOG_SUFFIX) -> handleSetSlot2Analog(intent)
            context.debugCommandAction(ACTION_SET_SLOT2_ANALOG_MAPPING_SUFFIX) -> handleSetSlot2AnalogMapping(entryPoint, intent)
            context.debugCommandAction(ACTION_SET_VULKAN_FALLBACKS_SUFFIX) -> handleSetVulkanFallbacks(intent)
            context.debugCommandAction(ACTION_SAVE_STATE_SUFFIX) -> handleSaveState(context, entryPoint, intent)
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

    private fun handleSetBgObjLog(entryPoint: DebugCommandEntryPoint, intent: Intent) {
        val enabled = intent.firstBooleanExtra(EXTRA_ENABLED, EXTRA_VALUE)
            ?: throw IllegalArgumentException("Missing enabled extra")
        entryPoint.sharedPreferences().edit(commit = true) {
            putBoolean(KEY_RENDERER_DEBUG_BGOBJ_ENABLED, enabled)
        }
        val refreshed = DebugCommandStateStore.requestSettingsRefresh()
        Log.w(TAG, "action=set_bgobj_log enabled=${if (enabled) 1 else 0} refreshed=${if (refreshed) 1 else 0}")
    }

    private fun handleSetSlot2Analog(intent: Intent) {
        val x = intent.firstFloatExtra(EXTRA_X, EXTRA_VALUE_X, EXTRA_VALUE)
            ?: throw IllegalArgumentException("Missing x/value extra")
        val y = intent.firstFloatExtra(EXTRA_Y, EXTRA_VALUE_Y, EXTRA_VALUE)
            ?: throw IllegalArgumentException("Missing y/value extra")
        val clampedX = x.coerceIn(-1f, 1f)
        val clampedY = y.coerceIn(-1f, 1f)
        MelonEmulator.setSlot2AnalogInput(clampedX, clampedY)
        Log.w(TAG, "action=set_slot2_analog x=$clampedX y=$clampedY")
    }

    private fun handleSetSlot2AnalogMapping(entryPoint: DebugCommandEntryPoint, intent: Intent) {
        val settingsRepository = entryPoint.settingsRepository()
        val currentConfiguration = settingsRepository.getControllerConfiguration()
        val currentMapping = currentConfiguration.slot2AnalogMapping

        val nextMapping = currentMapping.copy(
            axisXCode = intent.firstNullableIntExtra(EXTRA_AXIS_X, EXTRA_AXIS, EXTRA_X) ?: currentMapping.axisXCode,
            axisYCode = intent.firstNullableIntExtra(EXTRA_AXIS_Y, EXTRA_AXIS, EXTRA_Y) ?: currentMapping.axisYCode,
            invertX = intent.firstBooleanExtra(EXTRA_INVERT_X) ?: currentMapping.invertX,
            invertY = intent.firstBooleanExtra(EXTRA_INVERT_Y) ?: currentMapping.invertY,
            deadzone = (intent.firstFloatExtra(EXTRA_DEADZONE) ?: currentMapping.deadzone).coerceIn(0f, 1f),
            deviceId = if (intent.hasExtra(EXTRA_DEVICE_ID)) {
                intent.firstNullableIntExtra(EXTRA_DEVICE_ID)?.takeIf { it >= 0 }
            } else {
                currentMapping.deviceId
            },
        )

        val updatedConfiguration = ControllerConfiguration(
            configList = currentConfiguration.inputMapper.map { it.copy() },
            slot2AnalogMapping = nextMapping,
        )
        settingsRepository.setControllerConfiguration(updatedConfiguration)
        val refreshed = DebugCommandStateStore.requestSettingsRefresh()
        Log.w(
            TAG,
            "action=set_slot2_analog_mapping axisX=${nextMapping.axisXCode} axisY=${nextMapping.axisYCode} invertX=${if (nextMapping.invertX) 1 else 0} invertY=${if (nextMapping.invertY) 1 else 0} deadzone=${"%.3f".format(Locale.US, nextMapping.deadzone)} deviceId=${nextMapping.deviceId ?: -1} refreshed=${if (refreshed) 1 else 0}",
        )
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

    private suspend fun handleSaveState(
        context: Context,
        entryPoint: DebugCommandEntryPoint,
        intent: Intent,
    ) {
        val stateUri = resolveStateUri(context, entryPoint, intent)
            ?: throw IllegalArgumentException("Missing save target. Provide slot or path.")
        val pauseAfterSave = intent.getBooleanExtra(EXTRA_PAUSE_AFTER, false)
        MelonEmulator.pauseEmulation()
        val success = try {
            MelonEmulator.saveState(stateUri)
        } finally {
            if (pauseAfterSave) {
                DebugCommandStateStore.setDebugPauseHeld(true)
            } else {
                DebugCommandStateStore.setDebugPauseHeld(false)
                MelonEmulator.resumeEmulation()
            }
        }
        Log.w(
            TAG,
            "action=save_state uri=$stateUri success=${if (success) 1 else 0} pauseAfter=${if (pauseAfterSave) 1 else 0}",
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
        val resumeFrames = intent.firstNullableIntExtra(EXTRA_RESUME_FRAMES, EXTRA_FRAMES)?.coerceAtLeast(0) ?: 0
        if (!pauseWasHeld) {
            MelonEmulator.pauseEmulation()
        } else if (resumeMs > 0 || resumeFrames > 0) {
            DebugCommandStateStore.setDebugPauseHeld(false)
            val startFrame = RendererDebugBridge.getCurrentFrameIndexForDebug()
            MelonEmulator.resumeEmulation()
            waitForRendererFrameOrTimeout(
                renderer = renderer,
                startFrame = startFrame,
                resumeFrames = resumeFrames,
                timeoutMs = resumeMs.toLong(),
            )
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
            "action=dump_renderer_capture renderer=${renderer.name.lowercase(Locale.US)} refreshed=${if (refreshed) 1 else 0} paused=${if (pauseWasHeld) 1 else 0} resumeMs=$resumeMs resumeFrames=$resumeFrames captureId=${result.captureId} success=${if (result.success) 1 else 0} outputDir=${result.outputDir?.absolutePath ?: "none"}",
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

    private suspend fun waitForRendererFrameOrTimeout(
        renderer: VideoRenderer,
        startFrame: Int,
        resumeFrames: Int,
        timeoutMs: Long,
    ) {
        val effectiveTimeoutMs = when {
            timeoutMs > 0L -> timeoutMs
            resumeFrames > 0 -> 5_000L
            else -> 0L
        }

        if (effectiveTimeoutMs <= 0L) {
            return
        }

        val targetFrame = if (resumeFrames > 0 && startFrame >= 0) {
            startFrame + resumeFrames
        } else {
            Int.MIN_VALUE
        }
        val deadlineAt = System.nanoTime() + effectiveTimeoutMs * 1_000_000L
        while (System.nanoTime() < deadlineAt) {
            val currentFrame = RendererDebugBridge.getCurrentFrameIndexForDebug()
            val hasReachedTargetFrame = targetFrame == Int.MIN_VALUE || currentFrame >= targetFrame
            val rendererReady = renderer != VideoRenderer.VULKAN || RendererDebugBridge.isCurrentFrameReadyForDebug()
            if (hasReachedTargetFrame && rendererReady) {
                return
            }
            delay(8L)
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

    private fun Intent.firstFloatExtra(vararg keys: String): Float? {
        keys.forEach { key ->
            if (!hasExtra(key)) {
                return@forEach
            }

            val raw = extras?.get(key)
            when (raw) {
                is Float -> return raw
                is Double -> return raw.toFloat()
                is Int -> return raw.toFloat()
                is String -> raw.toFloatOrNull()?.let { return it }
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
        private const val KEY_RENDERER_DEBUG_BGOBJ_ENABLED = "video_renderer_debug_bgobj_enabled"

        private const val EXTRA_RENDERER = "renderer"
        private const val EXTRA_SCALE = "scale"
        private const val EXTRA_IR = "ir"
        private const val EXTRA_ENABLED = "enabled"
        private const val EXTRA_X = "x"
        private const val EXTRA_Y = "y"
        private const val EXTRA_VALUE_X = "value_x"
        private const val EXTRA_VALUE_Y = "value_y"
        private const val EXTRA_AXIS = "axis"
        private const val EXTRA_AXIS_X = "axis_x"
        private const val EXTRA_AXIS_Y = "axis_y"
        private const val EXTRA_INVERT_X = "invert_x"
        private const val EXTRA_INVERT_Y = "invert_y"
        private const val EXTRA_DEADZONE = "deadzone"
        private const val EXTRA_DEVICE_ID = "device_id"
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
        private const val EXTRA_RESUME_FRAMES = "resume_frames"
        private const val EXTRA_DURATION_MS = "duration_ms"
        private const val EXTRA_FRAMES = "frames"
        private const val EXTRA_VALUE = "value"

        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private const val ACTION_SET_RENDERER_SUFFIX = "SET_RENDERER"
        private const val ACTION_SET_IR_SUFFIX = "SET_IR"
        private const val ACTION_SET_JIT_SUFFIX = "SET_JIT"
        private const val ACTION_SET_BGOBJ_LOG_SUFFIX = "SET_BGOBJ_LOG"
        private const val ACTION_SET_SLOT2_ANALOG_SUFFIX = "SET_SLOT2_ANALOG"
        private const val ACTION_SET_SLOT2_ANALOG_MAPPING_SUFFIX = "SET_SLOT2_ANALOG_MAPPING"
        private const val ACTION_SET_VULKAN_FALLBACKS_SUFFIX = "SET_VULKAN_FALLBACKS"
        private const val ACTION_SAVE_STATE_SUFFIX = "SAVE_STATE"
        private const val ACTION_LOAD_STATE_SUFFIX = "LOAD_STATE"
        private const val ACTION_DUMP_RENDERER_CAPTURE_SUFFIX = "DUMP_RENDERER_CAPTURE"
    }

    private fun Context.debugCommandAction(suffix: String): String {
        return "$packageName.$suffix"
    }
}
