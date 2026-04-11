package me.magnum.melonds.domain.model.emulator.validation

import me.magnum.melonds.domain.model.ConfigurationDirResult
import me.magnum.melonds.domain.model.ConsoleType
import me.magnum.melonds.domain.model.VideoRenderer

sealed class FirmwareLaunchPreconditionCheckResult {
    data class Success(val consoleType: ConsoleType) : FirmwareLaunchPreconditionCheckResult()
    data class BiosConfigurationIncorrect(val configurationDirectoryResult: ConfigurationDirResult) : FirmwareLaunchPreconditionCheckResult()
    data class RendererUnsupported(val renderer: VideoRenderer) : FirmwareLaunchPreconditionCheckResult()
    data class RendererInitFailed(val renderer: VideoRenderer) : FirmwareLaunchPreconditionCheckResult()
}
