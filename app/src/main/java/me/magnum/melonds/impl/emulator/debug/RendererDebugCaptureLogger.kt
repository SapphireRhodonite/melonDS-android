package me.magnum.melonds.impl.emulator.debug

import android.graphics.Bitmap
import android.util.Log
import me.magnum.melonds.domain.model.VideoRenderer
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.CRC32

private const val TAG = "RendererDebugCapture"
private const val CAPTURE_3D_LINE_WIDTH = 256
private const val CAPTURE_3D_LINE_HEIGHT = 192

internal data class RendererDebugCaptureResult(
    val captureId: String,
    val success: Boolean,
    val outputDir: File? = null,
)

internal object RendererDebugCaptureLogger {
    fun dumpPauseMenuCapture(
        configuredRenderer: VideoRenderer,
        outputDir: File? = null,
    ): RendererDebugCaptureResult {
        val captureId = java.lang.Long.toHexString(System.currentTimeMillis())
        Log.w(
            TAG,
            "captureId=$captureId stage=begin configuredRenderer=${configuredRenderer.name.lowercase(Locale.US)}",
        )

        RendererDebugBridge.dumpCurrentRendererSnapshot()

        val screenFrame = RendererDebugBridge.captureCurrentFrame()
        val packedTopPrimary = RendererDebugBridge.captureCurrentPackedTopPrimary()
        val packedBottomPrimary = RendererDebugBridge.captureCurrentPackedBottomPrimary()
        val renderer3dDimensions = RendererDebugBridge.captureCurrent3dDimensions()
        val renderer3dWidth = renderer3dDimensions?.getOrNull(0) ?: 0
        val renderer3dHeight = renderer3dDimensions?.getOrNull(1) ?: 0
        val frame3d = RendererDebugBridge.captureCurrent3dFrame()
        val captureFrame3d = RendererDebugBridge.captureCurrent3dCaptureFrame()
        val depth3d = RendererDebugBridge.captureCurrent3dDepth()
        val attr3d = RendererDebugBridge.captureCurrent3dAttributes()
        val coverage3d = RendererDebugBridge.captureCurrent3dCoverage()

        val resolvedOutputDir = outputDir?.takeIf { directory ->
            directory.exists() || directory.mkdirs()
        }

        saveFramePng(
            outputDir = resolvedOutputDir,
            captureId = captureId,
            kind = "screenFrame",
            width = RendererDebugBridge.CAPTURE_WIDTH,
            height = RendererDebugBridge.CAPTURE_HEIGHT,
            pixels = screenFrame,
        )
        saveFramePng(
            outputDir = resolvedOutputDir,
            captureId = captureId,
            kind = "packedTopPrimary",
            width = CAPTURE_3D_LINE_WIDTH,
            height = CAPTURE_3D_LINE_HEIGHT,
            pixels = packedTopPrimary,
        )
        saveFramePng(
            outputDir = resolvedOutputDir,
            captureId = captureId,
            kind = "packedBottomPrimary",
            width = CAPTURE_3D_LINE_WIDTH,
            height = CAPTURE_3D_LINE_HEIGHT,
            pixels = packedBottomPrimary,
        )
        saveFramePng(
            outputDir = resolvedOutputDir,
            captureId = captureId,
            kind = "renderer3dFrame",
            width = renderer3dWidth,
            height = renderer3dHeight,
            pixels = frame3d,
        )
        saveFramePng(
            outputDir = resolvedOutputDir,
            captureId = captureId,
            kind = "renderer3dCaptureFrame",
            width = CAPTURE_3D_LINE_WIDTH,
            height = CAPTURE_3D_LINE_HEIGHT,
            pixels = captureFrame3d,
        )

        Log.w(
            TAG,
            "captureId=$captureId kind=meta screen=${describeBufferShape(RendererDebugBridge.CAPTURE_WIDTH, RendererDebugBridge.CAPTURE_HEIGHT, screenFrame)} packedTop=${describeBufferShape(CAPTURE_3D_LINE_WIDTH, CAPTURE_3D_LINE_HEIGHT, packedTopPrimary)} packedBottom=${describeBufferShape(CAPTURE_3D_LINE_WIDTH, CAPTURE_3D_LINE_HEIGHT, packedBottomPrimary)} renderer3d=${describeBufferShape(renderer3dWidth, renderer3dHeight, frame3d)} renderer3dCapture=${describeBufferShape(CAPTURE_3D_LINE_WIDTH, CAPTURE_3D_LINE_HEIGHT, captureFrame3d)} depth=${describeBufferShape(renderer3dWidth, renderer3dHeight, depth3d)} attr=${describeBufferShape(renderer3dWidth, renderer3dHeight, attr3d)} coverage=${describeBufferShape(renderer3dWidth, renderer3dHeight, coverage3d)}",
        )

        logFrameSummary(
            captureId = captureId,
            kind = "screenFrame",
            width = RendererDebugBridge.CAPTURE_WIDTH,
            height = RendererDebugBridge.CAPTURE_HEIGHT,
            pixels = screenFrame,
        )
        logFrameSummary(
            captureId = captureId,
            kind = "packedTopPrimary",
            width = CAPTURE_3D_LINE_WIDTH,
            height = CAPTURE_3D_LINE_HEIGHT,
            pixels = packedTopPrimary,
        )
        logFrameSummary(
            captureId = captureId,
            kind = "packedBottomPrimary",
            width = CAPTURE_3D_LINE_WIDTH,
            height = CAPTURE_3D_LINE_HEIGHT,
            pixels = packedBottomPrimary,
        )
        logFrameSummary(
            captureId = captureId,
            kind = "renderer3dFrame",
            width = renderer3dWidth,
            height = renderer3dHeight,
            pixels = frame3d,
        )
        logFrameSummary(
            captureId = captureId,
            kind = "renderer3dCaptureFrame",
            width = CAPTURE_3D_LINE_WIDTH,
            height = CAPTURE_3D_LINE_HEIGHT,
            pixels = captureFrame3d,
        )
        logDepthSummary(
            captureId = captureId,
            width = renderer3dWidth,
            height = renderer3dHeight,
            values = depth3d,
        )
        logAttrSummary(
            captureId = captureId,
            width = renderer3dWidth,
            height = renderer3dHeight,
            values = attr3d,
        )
        logCoverageSummary(
            captureId = captureId,
            width = renderer3dWidth,
            height = renderer3dHeight,
            values = coverage3d,
        )

        val success = hasData(screenFrame)
            || hasData(packedTopPrimary)
            || hasData(packedBottomPrimary)
            || hasData(frame3d)
            || hasData(captureFrame3d)
            || hasData(depth3d)
            || hasData(attr3d)
            || hasData(coverage3d)
        Log.w(
            TAG,
            "captureId=$captureId stage=end success=${if (success) 1 else 0}",
        )
        return RendererDebugCaptureResult(
            captureId = captureId,
            success = success,
            outputDir = resolvedOutputDir,
        )
    }

    private fun hasData(values: IntArray?): Boolean {
        return values != null && values.isNotEmpty()
    }

    private fun describeBufferShape(width: Int, height: Int, values: IntArray?): String {
        val data = values
        if (data == null || data.isEmpty()) {
            return "${width}x${height}:empty"
        }

        val expectedSize = if (width > 0 && height > 0) width * height else -1
        return if (expectedSize > 0 && expectedSize == data.size) {
            "${width}x${height}:${data.size}"
        } else {
            "${width}x${height}:${data.size}:expected=$expectedSize"
        }
    }

    private fun logFrameSummary(
        captureId: String,
        kind: String,
        width: Int,
        height: Int,
        pixels: IntArray?,
    ) {
        val data = pixels
        if (data == null || data.isEmpty()) {
            Log.w(TAG, "captureId=$captureId kind=$kind unavailable=1")
            return
        }

        var nonBlack = 0
        var nonTransparent = 0
        var opaque = 0
        var magenta = 0
        var minAlpha = 255
        var maxAlpha = 0
        for (pixel in data) {
            val rgb = pixel and 0x00FFFFFF
            val alpha = (pixel ushr 24) and 0xFF
            if (rgb != 0) nonBlack++
            if (alpha != 0) nonTransparent++
            if (alpha == 0xFF) opaque++
            if (rgb == 0x00FF00FF) magenta++
            if (alpha < minAlpha) minAlpha = alpha
            if (alpha > maxAlpha) maxAlpha = alpha
        }

        Log.w(
            TAG,
            "captureId=$captureId kind=$kind size=${width}x${height} pixels=${data.size} crc32=${crc32Hex(data)} nonBlack=$nonBlack nonTransparent=$nonTransparent opaque=$opaque magenta=$magenta alphaRange=$minAlpha-$maxAlpha samples=${buildSamplePreview(width, height, data)}",
        )
    }

    private fun saveFramePng(
        outputDir: File?,
        captureId: String,
        kind: String,
        width: Int,
        height: Int,
        pixels: IntArray?,
    ) {
        val directory = outputDir ?: return
        val data = pixels ?: return
        if (width <= 0 || height <= 0 || data.size != width * height) {
            return
        }

        val file = File(directory, "${captureId}_${kind}.png")
        try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(data, 0, width, 0, 0, width, height)
            FileOutputStream(file).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            bitmap.recycle()
            Log.w(TAG, "captureId=$captureId kind=$kind png=${file.absolutePath}")
        } catch (error: Exception) {
            Log.w(TAG, "captureId=$captureId kind=$kind png_save_failed=1", error)
        }
    }

    private fun logDepthSummary(
        captureId: String,
        width: Int,
        height: Int,
        values: IntArray?,
    ) {
        val data = values
        if (data == null || data.isEmpty()) {
            Log.w(TAG, "captureId=$captureId kind=renderer3dDepth unavailable=1")
            return
        }

        var minValue = Long.MAX_VALUE
        var maxValue = Long.MIN_VALUE
        var zeroCount = 0
        for (value in data) {
            val unsignedValue = value.toLong() and 0xFFFFFFFFL
            if (unsignedValue < minValue) minValue = unsignedValue
            if (unsignedValue > maxValue) maxValue = unsignedValue
            if (unsignedValue == 0L) zeroCount++
        }

        Log.w(
            TAG,
            "captureId=$captureId kind=renderer3dDepth size=${width}x${height} pixels=${data.size} crc32=${crc32Hex(data)} min=${hex32(minValue)} max=${hex32(maxValue)} zero=$zeroCount samples=${buildSamplePreview(width, height, data)}",
        )
    }

    private fun logAttrSummary(
        captureId: String,
        width: Int,
        height: Int,
        values: IntArray?,
    ) {
        val data = values
        if (data == null || data.isEmpty()) {
            Log.w(TAG, "captureId=$captureId kind=renderer3dAttr unavailable=1")
            return
        }

        var nonZero = 0
        var edgePixels = 0
        var fogPixels = 0
        var backFacingPixels = 0
        val polyIdCounts = IntArray(64)

        for (value in data) {
            if (value != 0) nonZero++
            if ((value and 0xF) != 0) edgePixels++
            if ((value and (1 shl 15)) != 0) fogPixels++
            if ((value and (1 shl 4)) != 0) backFacingPixels++

            val polyId = (value ushr 24) and 0x3F
            polyIdCounts[polyId]++
        }

        var uniquePolyIds = 0
        for (count in polyIdCounts) {
            if (count > 0)
                uniquePolyIds++
        }

        val topPolyIdEntries = ArrayList<Pair<Int, Int>>()
        for (polyId in polyIdCounts.indices) {
            val count = polyIdCounts[polyId]
            if (count > 0)
                topPolyIdEntries.add(polyId to count)
        }
        topPolyIdEntries.sortByDescending { it.second }
        val topPolyIds = if (topPolyIdEntries.isEmpty()) {
            "none"
        } else {
            topPolyIdEntries.take(6).joinToString(separator = ",") { "${it.first}:${it.second}" }
        }

        Log.w(
            TAG,
            "captureId=$captureId kind=renderer3dAttr size=${width}x${height} pixels=${data.size} crc32=${crc32Hex(data)} nonZero=$nonZero edge=$edgePixels fog=$fogPixels backFacing=$backFacingPixels uniquePolyIds=$uniquePolyIds topPolyIds=$topPolyIds samples=${buildSamplePreview(width, height, data)}",
        )
    }

    private fun logCoverageSummary(
        captureId: String,
        width: Int,
        height: Int,
        values: IntArray?,
    ) {
        val data = values
        if (data == null || data.isEmpty()) {
            Log.w(TAG, "captureId=$captureId kind=renderer3dCoverage unavailable=1")
            return
        }

        var nonZero = 0
        var fullCoverage = 0
        var maxCoverage = 0
        var totalCoverage = 0L
        for (value in data) {
            val coverage = value and 0x1F
            if (coverage != 0) nonZero++
            if (coverage == 0x1F) fullCoverage++
            if (coverage > maxCoverage) maxCoverage = coverage
            totalCoverage += coverage.toLong()
        }

        val meanCoverage = if (data.isEmpty()) {
            0.0
        } else {
            totalCoverage.toDouble() / data.size.toDouble()
        }

        Log.w(
            TAG,
            "captureId=$captureId kind=renderer3dCoverage size=${width}x${height} pixels=${data.size} crc32=${crc32Hex(data)} nonZero=$nonZero full31=$fullCoverage max=$maxCoverage mean=${"%.3f".format(Locale.US, meanCoverage)} samples=${buildSamplePreview(width, height, data)}",
        )
    }

    private fun buildSamplePreview(width: Int, height: Int, values: IntArray): String {
        if (width <= 0 || height <= 0 || values.isEmpty()) {
            return "none"
        }

        val samplePoints = linkedMapOf<String, Pair<Int, Int>>(
            "tl" to (0 to 0),
            "tc" to (width / 2 to 0),
            "c" to (width / 2 to height / 2),
            "bc" to (width / 2 to (height - 1)),
            "br" to ((width - 1) to (height - 1)),
        )

        return samplePoints.entries.joinToString(separator = ",") { (label, point) ->
            val x = point.first.coerceIn(0, width - 1)
            val y = point.second.coerceIn(0, height - 1)
            val index = y * width + x
            val value = if (index in values.indices) {
                hex32(values[index])
            } else {
                "out"
            }
            "$label:$value"
        }
    }

    private fun crc32Hex(values: IntArray): String {
        val crc32 = CRC32()
        for (value in values) {
            crc32.update(value and 0xFF)
            crc32.update((value ushr 8) and 0xFF)
            crc32.update((value ushr 16) and 0xFF)
            crc32.update((value ushr 24) and 0xFF)
        }
        return hex32(crc32.value)
    }

    private fun hex32(value: Int): String {
        return hex32(value.toLong() and 0xFFFFFFFFL)
    }

    private fun hex32(value: Long): String {
        return java.lang.Long.toHexString(value and 0xFFFFFFFFL)
            .padStart(8, '0')
            .uppercase(Locale.US)
    }
}
