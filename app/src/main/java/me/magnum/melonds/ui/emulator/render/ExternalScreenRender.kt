package me.magnum.melonds.ui.emulator.render

import android.opengl.GLES30
import me.magnum.melonds.common.opengl.Shader
import me.magnum.melonds.common.opengl.ShaderFactory
import me.magnum.melonds.common.opengl.VideoFilterShaderProvider
import me.magnum.melonds.domain.model.SCREEN_HEIGHT
import me.magnum.melonds.domain.model.SCREEN_WIDTH
import me.magnum.melonds.domain.model.ScreenAlignment
import me.magnum.melonds.domain.model.VideoFiltering
import me.magnum.melonds.domain.model.consoleAspectRatio
import me.magnum.melonds.domain.model.render.PresentFrameWrapper
import me.magnum.melonds.ui.RdsRotation
import me.magnum.melonds.ui.emulator.model.RuntimeRendererConfiguration
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.roundToInt

class ExternalScreenRender(
    private var rotateLeft: Boolean,
    private var keepAspectRatio: Boolean,
    private var integerScale: Boolean,
    private var verticalAlignment: ScreenAlignment,
    private var fillHeight: Boolean,
    private var fillWidth: Boolean,
) : EmulatorRenderer {

    private var shader: Shader? = null
    private var screensVbo = 0
    private var screensVao = 0

    private var videoFiltering: VideoFiltering = VideoFiltering.NONE

    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var areVerticesDirty = false
    private var areRenderSettingsDirty = false
    private var viewportLock = Any()

    private var lastScreenTextureId = 0
    private var lastScreenTextureFiltering = Int.MIN_VALUE

    override fun updateRendererConfiguration(newRendererConfiguration: RuntimeRendererConfiguration?) {
        synchronized(viewportLock) {
            val newFiltering = newRendererConfiguration?.videoFiltering ?: VideoFiltering.NONE
            if (videoFiltering != newFiltering) {
                videoFiltering = newFiltering
                areRenderSettingsDirty = true
            }
        }
    }

    override fun setLeftRotationEnabled(enabled: Boolean) {
        synchronized(viewportLock) {
            rotateLeft = enabled
            areVerticesDirty = true
        }
    }

    override fun onSurfaceCreated() {
        shader = ShaderFactory.createShaderProgram(
            VideoFilterShaderProvider.getShaderSource(videoFiltering)
        )

        val buffers = IntArray(2)
        GLES30.glGenBuffers(1, buffers, 0)
        GLES30.glGenVertexArrays(1, buffers, 1)
        screensVbo = buffers[0]
        screensVao = buffers[1]
        areVerticesDirty = true
    }

    private fun updateScreenVertices() {
        val (actualWidth, actualHeight) = if (rotateLeft) {
            surfaceHeight to surfaceWidth
        } else {
            surfaceWidth to surfaceHeight
        }

        val (targetWidth, targetHeight) = computeTargetDimensions(actualWidth, actualHeight)

        if (rotateLeft) {
            val rotated = buildVertexCoords(actualHeight, actualWidth, targetHeight, targetWidth)
            RdsRotation.rotateLeft(rotated)
            uploadVertices(rotated)
        } else {
            val coords = buildVertexCoords(actualWidth, actualHeight, targetWidth, targetHeight)
            uploadVertices(coords)
        }
    }

    private fun uploadVertices(coords: FloatArray) {
        val lineRelativeSize = 1f / (SCREEN_HEIGHT * 2 + 2).toFloat()
        val uvs = if (true /*screen == DsExternalScreen.TOP*/) {
            floatArrayOf(
                0f, 0.5f - lineRelativeSize,
                0f, 0f,
                1f, 0f,
                0f, 0.5f - lineRelativeSize,
                1f, 0f,
                1f, 0.5f - lineRelativeSize
            )
        } else {
            floatArrayOf(
                0f, 1f,
                0f, 0.5f + lineRelativeSize,
                1f, 0.5f + lineRelativeSize,
                0f, 1f,
                1f, 0.5f + lineRelativeSize,
                1f, 1f
            )
        }

        val vertexData = floatArrayOf(
            coords[0], coords[1],   uvs[0], uvs[1], 1f,
            coords[2], coords[3],   uvs[2], uvs[3], 1f,
            coords[4], coords[5],   uvs[4], uvs[5], 1f,
            coords[6], coords[7],   uvs[6], uvs[7], 1f,
            coords[8], coords[9],   uvs[8], uvs[9], 1f,
            coords[10], coords[11], uvs[10], uvs[11], 1f,
        )

        val vertexBufferSize = vertexData.size * Float.SIZE_BYTES
        val vertexBuffer = ByteBuffer.allocateDirect(vertexBufferSize)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertexData)
            .position(0)

        GLES30.glBindVertexArray(screensVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, screensVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertexBufferSize, vertexBuffer, GLES30.GL_STATIC_DRAW)
    }

    private fun computeTargetDimensions(actualWidth: Int, actualHeight: Int): Pair<Int, Int> {
        val base = when {
            integerScale -> computeIntegerScaleDimensions(actualWidth, actualHeight)
            keepAspectRatio -> computeAspectDimensions(actualWidth, actualHeight)
            else -> actualWidth to actualHeight
        }
        val canFill = integerScale || keepAspectRatio
        val width = (if (canFill && fillWidth) actualWidth else base.first).coerceAtLeast(1).coerceAtMost(actualWidth)
        val height = (if (canFill && fillHeight) actualHeight else base.second).coerceAtLeast(1).coerceAtMost(actualHeight)
        return width to height
    }

    private fun buildVertexCoords(actualWidth: Int, actualHeight: Int, targetWidth: Int, targetHeight: Int): FloatArray {
        val leftMargin = ((actualWidth - targetWidth) / 2f).coerceAtLeast(0f)
        val topMargin = when (verticalAlignment) {
            ScreenAlignment.TOP -> 0f
            ScreenAlignment.CENTER -> ((actualHeight - targetHeight) / 2f).coerceAtLeast(0f)
            ScreenAlignment.BOTTOM -> (actualHeight - targetHeight).toFloat().coerceAtLeast(0f)
        }
        val relativeWidth = targetWidth * 2f / actualWidth
        val relativeHeight = targetHeight * 2f / actualHeight
        val left = -1f + (leftMargin * 2f / actualWidth)
        val right = left + relativeWidth
        val top = 1f - (topMargin * 2f / actualHeight)
        val bottom = top - relativeHeight

        return floatArrayOf(
            left, bottom,
            left, top,
            right, top,
            left, bottom,
            right, top,
            right, bottom,
        )
    }

    private fun computeIntegerScaleDimensions(actualWidth: Int, actualHeight: Int): Pair<Int, Int> {
        val widthScale = actualWidth / SCREEN_WIDTH
        val heightScale = actualHeight / SCREEN_HEIGHT
        val maxScale = min(widthScale, heightScale)
        val scale = if (maxScale <= 0) {
            min(
                actualWidth.toFloat() / SCREEN_WIDTH,
                actualHeight.toFloat() / SCREEN_HEIGHT,
            )
        } else {
            maxScale.toFloat()
        }

        val width = (SCREEN_WIDTH * scale).roundToInt().coerceAtLeast(1).coerceAtMost(actualWidth)
        val height = (SCREEN_HEIGHT * scale).roundToInt().coerceAtLeast(1).coerceAtMost(actualHeight)
        return width to height
    }

    private fun computeAspectDimensions(actualWidth: Int, actualHeight: Int): Pair<Int, Int> {
        val viewRatio = actualWidth.toFloat() / actualHeight.toFloat()
        return if (viewRatio > consoleAspectRatio) {
            val height = actualHeight
            val width = (height * consoleAspectRatio).roundToInt().coerceAtLeast(1).coerceAtMost(actualWidth)
            width to height
        } else {
            val width = actualWidth
            val height = (width / consoleAspectRatio).roundToInt().coerceAtLeast(1).coerceAtMost(actualHeight)
            width to height
        }
    }

    fun setFillHeight(enabled: Boolean) {
        synchronized(viewportLock) {
            if (fillHeight != enabled) {
                fillHeight = enabled
                areVerticesDirty = true
            }
        }
    }

    fun setFillWidth(enabled: Boolean) {
        synchronized(viewportLock) {
            if (fillWidth != enabled) {
                fillWidth = enabled
                areVerticesDirty = true
            }
        }
    }

    private fun updateShader() {
        // Delete previous shader
        shader?.delete()

        val shaderSource = VideoFilterShaderProvider.getShaderSource(videoFiltering)
        shader = ShaderFactory.createShaderProgram(shaderSource)
        lastScreenTextureFiltering = Int.MIN_VALUE
    }

    override fun onSurfaceChanged(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        areVerticesDirty = true
    }

    override fun drawFrame(presentFrameWrapper: PresentFrameWrapper) {
        synchronized(viewportLock) {
            if (areVerticesDirty) {
                updateScreenVertices()
                areVerticesDirty = false
            }

            if (areRenderSettingsDirty) {
                updateShader()
                areRenderSettingsDirty = false
            }
        }

        if (!presentFrameWrapper.isValidFrame) {
            return
        }

        val textureId = presentFrameWrapper.textureId

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        shader?.let { shader ->
            shader.use()

            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
            if (lastScreenTextureId != textureId || lastScreenTextureFiltering != shader.textureFiltering) {
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, shader.textureFiltering)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, shader.textureFiltering)
                lastScreenTextureId = textureId
                lastScreenTextureFiltering = shader.textureFiltering
            }

            GLES30.glBindVertexArray(screensVao)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, screensVbo)
            if (shader.attribPos >= 0) {
                GLES30.glVertexAttribPointer(shader.attribPos, 2, GLES30.GL_FLOAT, false, 5 * Float.SIZE_BYTES, 0)
            }
            if (shader.attribUv >= 0) {
                GLES30.glVertexAttribPointer(shader.attribUv, 2, GLES30.GL_FLOAT, false, 5 * Float.SIZE_BYTES, 2 * Float.SIZE_BYTES)
            }
            if (shader.attribAlpha >= 0) {
                GLES30.glVertexAttribPointer(shader.attribAlpha, 1, GLES30.GL_FLOAT, false, 5 * Float.SIZE_BYTES, (2 + 2) * Float.SIZE_BYTES)
            }
            if (shader.uniformTex >= 0) {
                GLES30.glUniform1i(shader.uniformTex, 0)
            }
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        }
    }

    fun setKeepAspectRatio(keep: Boolean) {
        synchronized(viewportLock) {
            if (keepAspectRatio != keep) {
                keepAspectRatio = keep
                areVerticesDirty = true
            }
        }
    }

    fun setIntegerScale(enabled: Boolean) {
        synchronized(viewportLock) {
            if (integerScale != enabled) {
                integerScale = enabled
                areVerticesDirty = true
            }
        }
    }

    fun setVerticalAlignment(alignment: ScreenAlignment) {
        synchronized(viewportLock) {
            if (verticalAlignment != alignment) {
                verticalAlignment = alignment
                areVerticesDirty = true
            }
        }
    }

}
