package me.magnum.melonds.domain.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.magnum.melonds.MelonDSAndroidInterface
import me.magnum.melonds.common.romprocessors.RomFileProcessorFactory
import me.magnum.melonds.domain.model.ConfigurationDirResult
import me.magnum.melonds.domain.model.ConsoleType
import me.magnum.melonds.domain.model.VideoRenderer
import me.magnum.melonds.domain.model.emulator.validation.FirmwareLaunchPreconditionCheckResult
import me.magnum.melonds.domain.model.rom.Rom
import me.magnum.melonds.domain.model.emulator.validation.RomLaunchPreconditionCheckResult
import me.magnum.melonds.domain.model.rom.config.RuntimeConsoleType
import me.magnum.melonds.domain.repositories.SettingsRepository
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EmulatorLaunchPreconditionChecker(
    private val configurationDirectoryVerifier: ConfigurationDirectoryVerifier,
    private val romFileProcessorFactory: RomFileProcessorFactory,
    private val dsiNandManager: DSiNandManager,
    private val settingsRepository: SettingsRepository,
) {

    suspend fun checkRomLaunchPreconditions(rom: Rom): RomLaunchPreconditionCheckResult {
        val renderer = settingsRepository.getEffectiveVideoRenderer(rom.config)
        getRendererValidationFailureOrNull(renderer)?.let {
            return when (it) {
                RendererValidationFailure.UNSUPPORTED -> RomLaunchPreconditionCheckResult.RendererUnsupported(VideoRenderer.VULKAN)
                RendererValidationFailure.INIT_FAILED -> RomLaunchPreconditionCheckResult.RendererInitFailed(VideoRenderer.VULKAN)
            }
        }

        if (rom.isDsiWareTitle) {
            val dsiWareCheckResult = checkDsiWarePreconditions(rom)
            if (dsiWareCheckResult !is RomLaunchPreconditionCheckResult.Success) {
                return dsiWareCheckResult
            }
        }

        val configurationDirResult = getRomConfigurationDirectoryResult(rom)
        if (configurationDirResult.status != ConfigurationDirResult.Status.VALID) {
            return RomLaunchPreconditionCheckResult.BiosConfigurationIncorrect(configurationDirResult)
        }

        return RomLaunchPreconditionCheckResult.Success(rom)
    }

    suspend fun checkFirmwareLaunchPreconditions(consoleType: ConsoleType): FirmwareLaunchPreconditionCheckResult {
        getRendererValidationFailureOrNull(settingsRepository.getCurrentVideoRenderer())?.let {
            return when (it) {
                RendererValidationFailure.UNSUPPORTED -> FirmwareLaunchPreconditionCheckResult.RendererUnsupported(VideoRenderer.VULKAN)
                RendererValidationFailure.INIT_FAILED -> FirmwareLaunchPreconditionCheckResult.RendererInitFailed(VideoRenderer.VULKAN)
            }
        }

        val configurationDirResult = configurationDirectoryVerifier.checkConsoleConfigurationDirectory(consoleType)
        if (configurationDirResult.status != ConfigurationDirResult.Status.VALID) {
            return FirmwareLaunchPreconditionCheckResult.BiosConfigurationIncorrect(configurationDirResult)
        }

        return FirmwareLaunchPreconditionCheckResult.Success(consoleType)
    }

    private suspend fun checkDsiWarePreconditions(rom: Rom): RomLaunchPreconditionCheckResult {
        val romInfo = romFileProcessorFactory.getFileRomProcessorForDocument(rom.uri)?.getRomInfo(rom)

        if (romInfo == null) {
            return RomLaunchPreconditionCheckResult.DSiWareTitleValidationFailed(RomLaunchPreconditionCheckResult.DSiWareTitleValidationFailed.Reason.RomParseError)
        }

        val openNandResult = dsiNandManager.openNand()
        if (openNandResult.isFailure()) {
            return RomLaunchPreconditionCheckResult.DSiWareTitleValidationFailed(RomLaunchPreconditionCheckResult.DSiWareTitleValidationFailed.Reason.NandError)
        }

        // The DSi title ID is equal to the game code, but parsed as a Long in big-endian
        val dsiTitleIdByteData = romInfo.gameCode.encodeToByteArray()
        val dsiTitleId = ByteBuffer.wrap(dsiTitleIdByteData).order(ByteOrder.BIG_ENDIAN).getInt().toLong()
        val isTitleInstalled = dsiNandManager.listTitles().any { it.titleId == dsiTitleId }
        dsiNandManager.closeNand()

        if (!isTitleInstalled) {
            return RomLaunchPreconditionCheckResult.DSiWareTitleValidationFailed(RomLaunchPreconditionCheckResult.DSiWareTitleValidationFailed.Reason.TitleNotInstalled)
        }

        return RomLaunchPreconditionCheckResult.Success(rom)
    }

    private fun getRomConfigurationDirectoryResult(rom: Rom): ConfigurationDirResult {
        val willUseInternalFirmware = !settingsRepository.useCustomBios() && rom.config.runtimeConsoleType == RuntimeConsoleType.DEFAULT
        if (willUseInternalFirmware) {
            return ConfigurationDirResult(ConsoleType.DS, ConfigurationDirResult.Status.VALID, emptyArray(), emptyArray())
        }

        val romTargetConsoleType = rom.config.runtimeConsoleType.targetConsoleType ?: settingsRepository.getDefaultConsoleType()
        return configurationDirectoryVerifier.checkConsoleConfigurationDirectory(romTargetConsoleType)
    }

    private suspend fun getRendererValidationFailureOrNull(renderer: VideoRenderer): RendererValidationFailure? {
        if (renderer != VideoRenderer.VULKAN) {
            return null
        }

        return withContext(Dispatchers.Default) {
            when {
                !MelonDSAndroidInterface.isVulkanRendererSupported() -> RendererValidationFailure.UNSUPPORTED
                !MelonDSAndroidInterface.canInitializeVulkanRenderer() -> RendererValidationFailure.INIT_FAILED
                else -> null
            }
        }
    }

    private enum class RendererValidationFailure {
        UNSUPPORTED,
        INIT_FAILED,
    }
}
