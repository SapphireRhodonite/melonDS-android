package me.magnum.melonds.impl

import android.content.Context
import android.os.Build
import me.magnum.melonds.R
import java.io.File

object AdrenoVulkanDriverSupport {
    fun isSupported(context: Context): Boolean {
        return context.resources.getBoolean(R.bool.adrenotools_enabled) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            Build.SUPPORTED_64_BIT_ABIS.any { it.equals("arm64-v8a", ignoreCase = true) } &&
            isAdrenoDevice()
    }

    fun isAdrenoDevice(): Boolean {
        if (readGpuModel().contains("adreno", ignoreCase = true)) {
            return true
        }

        if (File("/sys/class/kgsl/kgsl-3d0").exists()) {
            return true
        }

        return Build.HARDWARE.equals("qcom", ignoreCase = true)
    }

    private fun readGpuModel(): String {
        return listOf(
            "/sys/class/kgsl/kgsl-3d0/gpu_model",
            "/sys/class/kgsl/kgsl-3d0/gpu_model_name",
            "/proc/gpuinfo",
        ).firstNotNullOfOrNull { path ->
            runCatching {
                File(path)
                    .takeIf { it.isFile }
                    ?.readText()
                    ?.takeIf { it.isNotBlank() }
            }.getOrNull()
        }.orEmpty()
    }
}
