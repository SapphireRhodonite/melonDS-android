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
import me.magnum.melonds.MelonEmulator
import me.magnum.melonds.domain.model.SaveStateSlot
import me.magnum.melonds.domain.model.VideoRenderer
import java.io.File
import java.util.LinkedHashSet
import java.util.Locale

internal class ReleaseStateCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                DebugCommandExecutionLock.withLock {
                    handleIntent(context.applicationContext, intent)
                }
            } catch (error: Exception) {
                Log.w(TAG, "Release state command failed: action=${intent.action}", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleIntent(context: Context, intent: Intent) {
        val entryPoint = DebugCommandEntryPoint.resolve(context)
        val toolsEnabled = entryPoint.sharedPreferences().getBoolean(KEY_RENDERER_DEBUG_TOOLS_ENABLED, false)
        val propertyEnabled = readBooleanSystemProperty(RELEASE_STATE_COMMANDS_PROPERTY)
        if (!toolsEnabled || !propertyEnabled) {
            Log.w(
                TAG,
                "action=ignored_release_state_command actionName=${intent.action} toolsEnabled=${if (toolsEnabled) 1 else 0} propertyEnabled=${if (propertyEnabled) 1 else 0}",
            )
            return
        }

        when (intent.action) {
            context.debugCommandAction(ACTION_SET_RENDERER_SUFFIX) -> handleSetRenderer(entryPoint, intent)
            context.debugCommandAction(ACTION_SET_IR_SUFFIX) -> handleSetInternalResolution(entryPoint, intent)
            context.debugCommandAction(ACTION_SET_FAST_FORWARD_SUFFIX) -> handleSetFastForward(intent)
            context.debugCommandAction(ACTION_SET_VULKAN_SIMPLE_PIPELINE_SUFFIX) -> handleSetVulkanSimplePipeline(entryPoint, intent)
            context.debugCommandAction(ACTION_SAVE_STATE_SUFFIX) -> handleSaveState(context, entryPoint, intent)
            context.debugCommandAction(ACTION_LOAD_STATE_SUFFIX) -> handleLoadState(context, entryPoint, intent)
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
        Log.w(
            TAG,
            "action=set_renderer mode=release renderer=${renderer.name.lowercase(Locale.US)} refreshed=${if (refreshed) 1 else 0}",
        )
    }

    private fun handleSetInternalResolution(entryPoint: DebugCommandEntryPoint, intent: Intent) {
        val scale = intent.firstNullableIntExtra(EXTRA_SCALE, EXTRA_IR, EXTRA_VALUE)
            ?: throw IllegalArgumentException("Missing internal resolution extra")
        require(scale in 1..8) { "Unsupported internal resolution=$scale" }
        entryPoint.sharedPreferences().edit(commit = true) {
            putString(KEY_VIDEO_INTERNAL_RESOLUTION, scale.toString())
        }
        val refreshed = DebugCommandStateStore.requestSettingsRefresh()
        Log.w(TAG, "action=set_ir mode=release scale=$scale refreshed=${if (refreshed) 1 else 0}")
    }

    private fun handleSetFastForward(intent: Intent) {
        val enabled = intent.firstBooleanExtra(EXTRA_ENABLED, EXTRA_VALUE)
            ?: throw IllegalArgumentException("Missing enabled extra")
        MelonEmulator.setFastForwardEnabled(enabled)
        Log.w(TAG, "action=set_fast_forward mode=release enabled=${if (enabled) 1 else 0}")
    }

    private fun handleSetVulkanSimplePipeline(entryPoint: DebugCommandEntryPoint, intent: Intent) {
        val enabled = intent.firstBooleanExtra(EXTRA_ENABLED, EXTRA_VALUE)
            ?: throw IllegalArgumentException("Missing enabled extra")
        entryPoint.sharedPreferences().edit(commit = true) {
            putBoolean(KEY_VIDEO_VULKAN_SIMPLE_PIPELINE_ENABLED, enabled)
        }
        val refreshed = DebugCommandStateStore.requestSettingsRefresh()
        Log.w(
            TAG,
            "action=set_vulkan_simple_pipeline mode=release enabled=${if (enabled) 1 else 0} refreshed=${if (refreshed) 1 else 0}",
        )
    }

    private suspend fun handleLoadState(
        context: Context,
        entryPoint: DebugCommandEntryPoint,
        intent: Intent,
    ) {
        val stateUri = resolveStateUri(context, entryPoint, intent, preferExistingSlotFallback = true)
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
            "action=load_state mode=release uri=$stateUri success=${if (success) 1 else 0} pauseAfter=${if (pauseAfterLoad) 1 else 0}",
        )
    }

    private suspend fun handleSaveState(
        context: Context,
        entryPoint: DebugCommandEntryPoint,
        intent: Intent,
    ) {
        val stateUri = resolveStateUri(context, entryPoint, intent, preferExistingSlotFallback = false)
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
            "action=save_state mode=release uri=$stateUri success=${if (success) 1 else 0} pauseAfter=${if (pauseAfterSave) 1 else 0}",
        )
    }

    private suspend fun resolveStateUri(
        context: Context,
        entryPoint: DebugCommandEntryPoint,
        intent: Intent,
        preferExistingSlotFallback: Boolean,
    ): Uri? {
        intent.firstStringExtra(EXTRA_PATH, EXTRA_URI)?.let { pathOrUri ->
            return parseUri(pathOrUri)
        }

        val slot = intent.firstNullableIntExtra(EXTRA_SLOT, EXTRA_VALUE) ?: return null
        require(slot in 0..8) { "Unsupported save state slot=$slot" }

        val romUri = resolveRomUriForSlot(context, intent) ?: return null
        val rom = entryPoint.romsRepository().getRomAtUri(romUri) ?: return null
        val resolvedUri = entryPoint.saveStatesRepository().getRomSaveStateUri(
            rom,
            SaveStateSlot(slot, exists = true, lastUsedDate = null, screenshot = null),
        )
        if (!preferExistingSlotFallback) {
            return resolvedUri
        }

        val fallbackUri = resolveExistingSlotFallbackUri(
            preferredUri = resolvedUri,
            romFileName = rom.fileName,
            slot = slot,
        ) ?: return resolvedUri
        Log.w(
            TAG,
            "action=slot_fallback mode=release slot=$slot preferred=$resolvedUri fallback=$fallbackUri",
        )
        return fallbackUri
    }

    private suspend fun resolveRomUriForSlot(context: Context, intent: Intent): Uri? {
        intent.firstStringExtra(EXTRA_ROM_URI)?.let { return Uri.parse(it) }

        var romUri = DebugCommandStateStore.getLastRomUri(context)
        if (romUri != null) {
            return romUri
        }

        val deadlineAt = System.nanoTime() + ROM_URI_RESOLVE_TIMEOUT_MS * 1_000_000L
        while (romUri == null && System.nanoTime() < deadlineAt) {
            delay(ROM_URI_RESOLVE_STEP_MS)
            romUri = DebugCommandStateStore.getLastRomUri(context)
        }
        return romUri
    }

    private fun resolveExistingSlotFallbackUri(
        preferredUri: Uri,
        romFileName: String,
        slot: Int,
    ): Uri? {
        if (preferredUri.scheme != "file") {
            return null
        }
        val preferredPath = preferredUri.path ?: return null
        val preferredFile = File(preferredPath)
        if (preferredFile.exists() && preferredFile.length() > 0L) {
            return null
        }
        val parentDirectory = preferredFile.parentFile
            ?.takeIf { it.exists() && it.isDirectory }
            ?: return null

        val romName = romFileName.substringBeforeLast('.', romFileName).trim()
        if (romName.isEmpty()) {
            return null
        }
        val candidates = buildAlternativeSaveStateNames(romName).asSequence()
            .map { candidateName -> File(parentDirectory, "$candidateName.ml$slot") }
            .firstOrNull { candidateFile -> candidateFile.exists() && candidateFile.length() > 0L }
            ?: return null
        return Uri.fromFile(candidates)
    }

    private fun buildAlternativeSaveStateNames(romName: String): List<String> {
        val normalized = romName.trim()
        if (normalized.isEmpty()) {
            return emptyList()
        }

        val names = LinkedHashSet<String>()
        val analogSuffixes = listOf(" Analog", " (Analog)", " [Analog]", "[Analog]")
        analogSuffixes.forEach { suffix ->
            if (normalized.endsWith(suffix, ignoreCase = true)) {
                val stripped = normalized.dropLast(suffix.length).trimEnd()
                if (stripped.isNotEmpty()) {
                    names.add(stripped)
                }
            }
        }
        if (!normalized.endsWith(" Analog", ignoreCase = true)) {
            names.add("$normalized Analog")
        }
        return names.toList()
    }

    private fun parseRenderer(value: String): VideoRenderer? {
        return when (value.trim().lowercase(Locale.US)) {
            "software", "soft" -> VideoRenderer.SOFTWARE
            "opengl", "gl" -> VideoRenderer.OPENGL
            "vulkan", "vk" -> VideoRenderer.VULKAN
            else -> null
        }
    }

    private fun readBooleanSystemProperty(key: String): Boolean {
        return try {
            val process = ProcessBuilder(GETPROP_BINARY, key)
                .redirectErrorStream(true)
                .start()
            val value = process.inputStream.bufferedReader().use { it.readText().trim() }
            process.waitFor()
            when (value.lowercase(Locale.US)) {
                "1", "true", "on", "yes", "enabled" -> true
                else -> false
            }
        } catch (error: Exception) {
            Log.w(TAG, "Failed to read system property key=$key", error)
            false
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

    @Suppress("DEPRECATION")
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
        private const val KEY_RENDERER_DEBUG_TOOLS_ENABLED = "video_renderer_debug_tools_enabled"
        private const val KEY_VIDEO_RENDERER = "video_renderer"
        private const val KEY_VIDEO_INTERNAL_RESOLUTION = "video_internal_resolution"
        private const val KEY_VIDEO_VULKAN_SIMPLE_PIPELINE_ENABLED = "video_vulkan_simple_pipeline_enabled"
        private const val RELEASE_STATE_COMMANDS_PROPERTY = "debug.melonds.release_state_commands"
        private const val GETPROP_BINARY = "/system/bin/getprop"

        private const val EXTRA_RENDERER = "renderer"
        private const val EXTRA_SCALE = "scale"
        private const val EXTRA_IR = "ir"
        private const val EXTRA_ENABLED = "enabled"
        private const val EXTRA_SLOT = "slot"
        private const val EXTRA_PATH = "path"
        private const val EXTRA_URI = "uri"
        private const val EXTRA_ROM_URI = "rom_uri"
        private const val EXTRA_PAUSE_AFTER = "pause_after"
        private const val EXTRA_VALUE = "value"

        private const val ROM_URI_RESOLVE_TIMEOUT_MS = 4_000L
        private const val ROM_URI_RESOLVE_STEP_MS = 100L

        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private const val ACTION_SET_RENDERER_SUFFIX = "SET_RENDERER"
        private const val ACTION_SET_IR_SUFFIX = "SET_IR"
        private const val ACTION_SET_FAST_FORWARD_SUFFIX = "SET_FAST_FORWARD"
        private const val ACTION_SET_VULKAN_SIMPLE_PIPELINE_SUFFIX = "SET_VULKAN_SIMPLE_PIPELINE"
        private const val ACTION_SAVE_STATE_SUFFIX = "SAVE_STATE"
        private const val ACTION_LOAD_STATE_SUFFIX = "LOAD_STATE"
    }

    private fun Context.debugCommandAction(suffix: String): String {
        return "$packageName.$suffix"
    }
}
