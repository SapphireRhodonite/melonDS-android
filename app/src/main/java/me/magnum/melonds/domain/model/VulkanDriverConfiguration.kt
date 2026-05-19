package me.magnum.melonds.domain.model

enum class VulkanDriverMode {
    SYSTEM,
    CUSTOM,
}

data class VulkanDriverConfiguration(
    val mode: VulkanDriverMode,
    val tmpLibDir: String,
    val hookLibDir: String,
    val customDriverDir: String?,
    val customDriverName: String?,
    val customDriverDisplayName: String?,
)

data class VulkanDriverInfo(
    val id: String,
    val displayName: String,
    val driverDir: String,
    val driverName: String,
)
