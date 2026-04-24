package me.magnum.melonds.ui.emulator.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.view.Surface
import androidx.core.os.bundleOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.magnum.melonds.MelonEmulator
import me.magnum.melonds.domain.model.RuntimeBackground
import me.magnum.melonds.extensions.parcelable
import me.magnum.melonds.ui.emulator.EmulatorSurfaceView
import me.magnum.melonds.ui.emulator.model.VulkanPresentationConfig

class VulkanFrameRenderCoordinator(
    private val context: Context,
) : FrameRenderCoordinator, EmulatorSurfaceView.SurfaceLifecycleListener {

    private data class PendingSurfaceConfig(
        val generation: Int,
        val config: VulkanPresentationConfig?,
    )

    private data class ManagedSurface(
        var surfaceId: Int = 0,
        var config: VulkanPresentationConfig? = null,
        var background: RuntimeBackground = RuntimeBackground.None,
        var generation: Int = 0,
        var pendingSurface: Surface? = null,
    )

    private val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val surfacesLock = Any()
    private val managedSurfaces = mutableMapOf<EmulatorSurfaceView, ManagedSurface>()
    private val pendingConfigs = mutableMapOf<EmulatorSurfaceView, PendingSurfaceConfig>()
    private val frameRenderThread = FrameRenderThread()

    init {
        frameRenderThread.start()
    }

    override fun addSurface(surface: EmulatorSurfaceView) {
        synchronized(surfacesLock) {
            managedSurfaces.putIfAbsent(surface, ManagedSurface())
        }

        surface.setSurfaceLifecycleListener(this)

        val currentSurface = surface.getCurrentSurface()
        if (currentSurface != null) {
            val (width, height) = surface.getCurrentSurfaceSize()
            requestSurfaceAttachmentIfNeeded(surface, currentSurface, width, height)
            refreshSurfacePresentation(surface)
        }
    }

    override fun removeSurface(surface: EmulatorSurfaceView) {
        surface.setSurfaceLifecycleListener(null)

        val surfaceId = synchronized(surfacesLock) {
            pendingConfigs.remove(surface)
            managedSurfaces.remove(surface)?.also {
                it.pendingSurface = null
            }?.surfaceId ?: 0
        }

        frameRenderThread.requestSurfaceDetachment(surfaceId)
    }

    override fun updateSurfacePresentation(
        surface: EmulatorSurfaceView,
        config: VulkanPresentationConfig?,
        background: RuntimeBackground,
    ) {
        val generation = synchronized(surfacesLock) {
            val currentState = managedSurfaces.getOrPut(surface) { ManagedSurface() }
            currentState.config = config
            currentState.background = background
            currentState.generation += 1
            currentState.generation
        }

        val (surfaceId, currentSurface, currentWidth, currentHeight) = synchronized(surfacesLock) {
            val managedSurface = managedSurfaces[surface]
            val (width, height) = surface.getCurrentSurfaceSize()
            Quadruple(
                managedSurface?.surfaceId ?: 0,
                surface.getCurrentSurface(),
                width,
                height,
            )
        }

        if (surfaceId == 0 && currentSurface != null) {
            requestSurfaceAttachmentIfNeeded(surface, currentSurface, currentWidth, currentHeight)
        }

        coordinatorScope.launch {
            val decodedBitmap = withContext(Dispatchers.IO) {
                decodeBackgroundBitmap(background)
            }

            frameRenderThread.requestSurfaceConfiguration(surface, generation, config, decodedBitmap)
        }
    }

    override fun renderFrame(frameDeadlineNanos: Long?) {
        frameRenderThread.requestFrameRender(frameDeadlineNanos)
    }

    override fun stop() {
        coordinatorScope.cancel()
        frameRenderThread.requestStop()
        frameRenderThread.quitSafely()
        frameRenderThread.join()
    }

    override fun onSurfaceCreated(surfaceView: EmulatorSurfaceView, surface: Surface) {
        val (width, height) = surfaceView.getCurrentSurfaceSize()
        requestSurfaceAttachmentIfNeeded(surfaceView, surface, width, height)
        refreshSurfacePresentation(surfaceView)
    }

    override fun onSurfaceChanged(surfaceView: EmulatorSurfaceView, surface: Surface, width: Int, height: Int) {
        val surfaceId = synchronized(surfacesLock) {
            managedSurfaces[surfaceView]?.surfaceId ?: 0
        }

        if (surfaceId == 0) {
            requestSurfaceAttachmentIfNeeded(surfaceView, surface, width, height)
        } else {
            frameRenderThread.requestSurfaceResize(surfaceView, width, height)
        }
        refreshSurfacePresentation(surfaceView)
    }

    override fun onSurfaceDestroyed(surfaceView: EmulatorSurfaceView) {
        val surfaceId = synchronized(surfacesLock) {
            pendingConfigs.remove(surfaceView)
            managedSurfaces[surfaceView]?.also {
                it.pendingSurface = null
            }?.surfaceId ?: 0
        }
        frameRenderThread.requestSurfaceDetachment(surfaceId)
        synchronized(surfacesLock) {
            managedSurfaces[surfaceView]?.let {
                it.surfaceId = 0
                it.pendingSurface = null
            }
        }
    }

    private fun requestSurfaceAttachmentIfNeeded(
        surfaceView: EmulatorSurfaceView,
        surface: Surface,
        width: Int,
        height: Int,
    ) {
        val shouldAttach = synchronized(surfacesLock) {
            val managedSurface = managedSurfaces[surfaceView] ?: return@synchronized false
            if (managedSurface.surfaceId != 0 || managedSurface.pendingSurface != null) {
                false
            } else {
                managedSurface.pendingSurface = surface
                true
            }
        }

        if (shouldAttach) {
            frameRenderThread.requestSurfaceAttachment(surfaceView, surface, width, height)
        }
    }

    private fun refreshSurfacePresentation(surface: EmulatorSurfaceView) {
        val currentState = synchronized(surfacesLock) {
            managedSurfaces[surface]?.copy()
        } ?: return

        updateSurfacePresentation(surface, currentState.config, currentState.background)
    }

    private suspend fun decodeBackgroundBitmap(background: RuntimeBackground): Bitmap? {
        val backgroundUri = background.background?.uri ?: return null
        val decodedBitmap = context.contentResolver.openInputStream(backgroundUri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } ?: return null

        if (decodedBitmap.config == Bitmap.Config.ARGB_8888) {
            return decodedBitmap
        }

        val convertedBitmap = decodedBitmap.copy(Bitmap.Config.ARGB_8888, false)
        if (convertedBitmap !== decodedBitmap) {
            decodedBitmap.recycle()
        }
        return convertedBitmap
    }

    private inner class FrameRenderThread : HandlerThread("VulkanPresentThread") {
        @Volatile
        private var handler: Handler? = null
        private val renderStatistics = RenderStatistics()

        override fun onLooperPrepared() {
            handler = object : Handler(looper) {
                override fun handleMessage(msg: Message) {
                    when (msg.what) {
                        MSG_ATTACH_SURFACE -> attachSurface(
                            msg.obj as EmulatorSurfaceView,
                            msg.data.parcelable(MSG_SURFACE),
                            msg.data.getInt(MSG_WIDTH),
                            msg.data.getInt(MSG_HEIGHT),
                        )
                        MSG_RESIZE_SURFACE -> resizeSurface(
                            msg.obj as EmulatorSurfaceView,
                            msg.data.getInt(MSG_WIDTH),
                            msg.data.getInt(MSG_HEIGHT),
                        )
                        MSG_CONFIGURE_SURFACE -> configureSurface(
                            msg.obj as EmulatorSurfaceView,
                            msg.data.getInt(MSG_GENERATION),
                            msg.data.parcelable(MSG_BACKGROUND_BITMAP),
                        )
                        MSG_DETACH_SURFACE -> detachSurface(msg.arg1)
                        MSG_RENDER_FRAME -> renderFrame(msg.data.getLong(MSG_FRAME_DEADLINE_NS))
                        MSG_STOP -> stopThread()
                    }
                }
            }
        }

        private fun getActiveHandler(): Handler? {
            val currentHandler = handler ?: return null
            if (currentHandler.looper.thread.isAlive) {
                return currentHandler
            }

            if (handler === currentHandler) {
                handler = null
            }
            return null
        }

        fun requestSurfaceAttachment(surfaceView: EmulatorSurfaceView, surface: Surface, width: Int, height: Int) {
            val currentHandler = getActiveHandler() ?: return
            currentHandler.obtainMessage(MSG_ATTACH_SURFACE, surfaceView).also {
                it.data = bundleOf(
                    MSG_SURFACE to surface,
                    MSG_WIDTH to width,
                    MSG_HEIGHT to height,
                )
                try {
                    currentHandler.sendMessage(it)
                } catch (_: IllegalStateException) {
                    if (handler === currentHandler) {
                        currentHandler.removeCallbacksAndMessages(null)
                        handler = null
                    }
                    it.recycle()
                }
            }
        }

        fun requestSurfaceResize(surfaceView: EmulatorSurfaceView, width: Int, height: Int) {
            val currentHandler = getActiveHandler() ?: return
            currentHandler.obtainMessage(MSG_RESIZE_SURFACE, surfaceView).also {
                it.data = bundleOf(
                    MSG_WIDTH to width,
                    MSG_HEIGHT to height,
                )
                try {
                    currentHandler.sendMessage(it)
                } catch (_: IllegalStateException) {
                    if (handler === currentHandler) {
                        currentHandler.removeCallbacksAndMessages(null)
                        handler = null
                    }
                    it.recycle()
                }
            }
        }

        fun requestSurfaceConfiguration(
            surfaceView: EmulatorSurfaceView,
            generation: Int,
            config: VulkanPresentationConfig?,
            backgroundBitmap: Bitmap?,
        ) {
            val shouldQueueMessage = synchronized(surfacesLock) {
                val currentPending = pendingConfigs[surfaceView]
                if (currentPending != null && generation < currentPending.generation) {
                    false
                } else {
                    pendingConfigs[surfaceView] = PendingSurfaceConfig(generation, config)
                    true
                }
            }

            if (!shouldQueueMessage) {
                backgroundBitmap?.recycle()
                return
            }

            val currentHandler = getActiveHandler()
            if (currentHandler == null) {
                backgroundBitmap?.recycle()
                return
            }

            currentHandler.obtainMessage(MSG_CONFIGURE_SURFACE, surfaceView).also {
                it.data = bundleOf(
                    MSG_GENERATION to generation,
                    MSG_HAS_CONFIG to (config != null),
                    MSG_BACKGROUND_BITMAP to backgroundBitmap,
                )
                try {
                    currentHandler.sendMessage(it)
                } catch (_: IllegalStateException) {
                    if (handler === currentHandler) {
                        currentHandler.removeCallbacksAndMessages(null)
                        handler = null
                    }
                    backgroundBitmap?.recycle()
                    it.recycle()
                }
            }
        }

        fun requestSurfaceDetachment(surfaceId: Int) {
            if (surfaceId == 0) {
                return
            }
            val currentHandler = getActiveHandler() ?: return
            currentHandler.obtainMessage(MSG_DETACH_SURFACE, surfaceId, 0).also {
                try {
                    currentHandler.sendMessage(it)
                } catch (_: IllegalStateException) {
                    if (handler === currentHandler) {
                        currentHandler.removeCallbacksAndMessages(null)
                        handler = null
                    }
                    it.recycle()
                }
            }
        }

        fun requestFrameRender(frameDeadlineNanos: Long?) {
            val currentHandler = getActiveHandler() ?: return
            currentHandler.removeMessages(MSG_RENDER_FRAME)
            currentHandler.obtainMessage(MSG_RENDER_FRAME).also {
                it.data = bundleOf(MSG_FRAME_DEADLINE_NS to (frameDeadlineNanos ?: 0L))
                try {
                    currentHandler.sendMessage(it)
                } catch (_: IllegalStateException) {
                    if (handler === currentHandler) {
                        currentHandler.removeCallbacksAndMessages(null)
                        handler = null
                    }
                    it.recycle()
                }
            }
        }

        fun requestStop() {
            val currentHandler = getActiveHandler() ?: return
            try {
                currentHandler.sendEmptyMessage(MSG_STOP)
            } catch (_: IllegalStateException) {
                if (handler === currentHandler) {
                    currentHandler.removeCallbacksAndMessages(null)
                    handler = null
                }
            }
        }

        private fun renderFrame(frameDeadlineNanos: Long) {
            val deadline = frameDeadlineNanos.takeIf { it > 0L } ?: 0L
            val budgetDeadline = if (deadline > 0L) {
                (deadline - renderStatistics.getPresentationBudgetMarginNs()).coerceAtLeast(0L)
            } else {
                0L
            }

            val renderStartNs = System.nanoTime()
            MelonEmulator.presentVulkanFrame(deadline, budgetDeadline)
            renderStatistics.trackRenderEvent(System.nanoTime() - renderStartNs)
        }

        private fun attachSurface(surfaceView: EmulatorSurfaceView, surface: Surface?, width: Int, height: Int) {
            if (surface == null) {
                return
            }

            val shouldAttach = synchronized(surfacesLock) {
                val managedSurface = managedSurfaces[surfaceView]
                managedSurface != null
                    && managedSurface.surfaceId == 0
                    && managedSurface.pendingSurface === surface
                    && surfaceView.getCurrentSurface() === surface
            }
            if (!shouldAttach) {
                return
            }

            val surfaceId = MelonEmulator.attachVulkanSurface(surface, width, height)
            var shouldRefreshPresentation = false
            var staleSurfaceId = 0
            var immediateConfig: VulkanPresentationConfig? = null
            synchronized(surfacesLock) {
                val managedSurface = managedSurfaces[surfaceView]
                if (managedSurface == null || managedSurface.pendingSurface !== surface || surfaceView.getCurrentSurface() !== surface) {
                    staleSurfaceId = surfaceId
                } else {
                    managedSurface.pendingSurface = null
                    managedSurface.surfaceId = surfaceId
                    immediateConfig = managedSurface.config
                    shouldRefreshPresentation = surfaceId != 0
                }
            }

            if (staleSurfaceId != 0) {
                MelonEmulator.detachVulkanSurface(staleSurfaceId)
                return
            }

            if (surfaceId != 0 && immediateConfig != null) {
                // Apply the latest layout immediately on attach so the presenter
                // becomes configured even before the async background decode path
                // finishes or a later UI refresh happens.
                MelonEmulator.configureVulkanSurface(surfaceId, immediateConfig!!, null)
            }

            if (shouldRefreshPresentation) {
                refreshSurfacePresentation(surfaceView)
            }
        }

        private fun resizeSurface(surfaceView: EmulatorSurfaceView, width: Int, height: Int) {
            val surfaceId = synchronized(surfacesLock) {
                managedSurfaces[surfaceView]?.surfaceId ?: 0
            }

            if (surfaceId != 0) {
                MelonEmulator.resizeVulkanSurface(surfaceId, width, height)
            }
        }

        private fun configureSurface(surfaceView: EmulatorSurfaceView, generation: Int, backgroundBitmap: Bitmap?) {
            val (surfaceId, currentGeneration) = synchronized(surfacesLock) {
                val managedSurface = managedSurfaces[surfaceView]
                (managedSurface?.surfaceId ?: 0) to (managedSurface?.generation ?: -1)
            }

            val pendingConfig = synchronized(surfacesLock) {
                pendingConfigs[surfaceView]
            }

            if (surfaceId == 0 || currentGeneration != generation || pendingConfig?.generation != generation) {
                backgroundBitmap?.recycle()
                return
            }

            synchronized(surfacesLock) {
                val currentPending = pendingConfigs[surfaceView]
                if (currentPending?.generation == generation) {
                    pendingConfigs.remove(surfaceView)
                }
            }

            val config = pendingConfig.config
            if (config == null) {
                backgroundBitmap?.recycle()
                return
            }

            MelonEmulator.configureVulkanSurface(surfaceId, config, backgroundBitmap)
            backgroundBitmap?.recycle()
        }

        private fun detachSurface(surfaceId: Int) {
            MelonEmulator.detachVulkanSurface(surfaceId)
        }

        private fun stopThread() {
            val currentHandler = handler
            currentHandler?.removeCallbacksAndMessages(null)
            handler = null

            val surfaceIds = synchronized(surfacesLock) {
                managedSurfaces.values.mapNotNull { it.surfaceId.takeIf { id -> id != 0 } }
            }

            surfaceIds.forEach {
                MelonEmulator.detachVulkanSurface(it)
            }
            synchronized(surfacesLock) {
                pendingConfigs.clear()
            }
        }
    }

    private class RenderStatistics {
        private var estimatedRenderDurationNs = 0L

        fun trackRenderEvent(durationNs: Long) {
            val clampedDurationNs = durationNs.coerceAtLeast(0L)
            estimatedRenderDurationNs = if (estimatedRenderDurationNs == 0L) {
                clampedDurationNs
            } else {
                ((estimatedRenderDurationNs * 7L) + clampedDurationNs) / 8L
            }
        }

        fun getPresentationBudgetMarginNs(): Long {
            val dynamicMarginNs = if (estimatedRenderDurationNs > 0L) {
                (estimatedRenderDurationNs * 5L) / 4L
            } else {
                DEFAULT_PRESENTATION_MARGIN_NS
            }

            return dynamicMarginNs.coerceIn(MIN_PRESENTATION_MARGIN_NS, MAX_PRESENTATION_MARGIN_NS)
        }

        private companion object {
            const val MIN_PRESENTATION_MARGIN_NS = 500_000L
            const val DEFAULT_PRESENTATION_MARGIN_NS = 1_000_000L
            const val MAX_PRESENTATION_MARGIN_NS = 2_000_000L
        }
    }

    private companion object {
        const val MSG_ATTACH_SURFACE = 1
        const val MSG_RESIZE_SURFACE = 2
        const val MSG_CONFIGURE_SURFACE = 3
        const val MSG_DETACH_SURFACE = 4
        const val MSG_RENDER_FRAME = 5
        const val MSG_STOP = 6

        const val MSG_SURFACE = "surface"
        const val MSG_WIDTH = "width"
        const val MSG_HEIGHT = "height"
        const val MSG_GENERATION = "generation"
        const val MSG_HAS_CONFIG = "has-config"
        const val MSG_BACKGROUND_BITMAP = "background-bitmap"
        const val MSG_FRAME_DEADLINE_NS = "frame-deadline"
    }
}

private data class Quadruple<out A, out B, out C, out D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
)
