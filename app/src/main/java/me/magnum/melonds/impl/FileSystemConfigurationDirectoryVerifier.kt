package me.magnum.melonds.impl

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import me.magnum.melonds.domain.model.ConfigurationDirResult
import me.magnum.melonds.domain.model.ConsoleType
import me.magnum.melonds.domain.repositories.SettingsRepository
import me.magnum.melonds.domain.services.ConfigurationDirectoryVerifier

class FileSystemConfigurationDirectoryVerifier(private val context: Context, settingsRepository: SettingsRepository) : ConfigurationDirectoryVerifier(settingsRepository) {
    override fun checkDsConfigurationDirectory(directory: Uri?): ConfigurationDirResult {
        return checkConfigurationDirectory(ConsoleType.DS, directory)
    }

    override fun checkDsiConfigurationDirectory(directory: Uri?): ConfigurationDirResult {
        return checkConfigurationDirectory(ConsoleType.DSi, directory)
    }

    private fun checkConfigurationDirectory(consoleType: ConsoleType, directory: Uri?): ConfigurationDirResult {
        val requiredFiles = getRequiredFilesVerifiers(consoleType)
        if (directory == null) {
            val fileResults = requiredFiles.map { it.key to ConfigurationDirResult.FileStatus.MISSING }
            return ConfigurationDirResult(consoleType, ConfigurationDirResult.Status.UNSET, requiredFiles.keys.toTypedArray(), fileResults.toTypedArray())
        }

        val dirDocument = runCatching {
            DocumentFile.fromTreeUri(context, directory)
        }.getOrNull()
        val isDirectory = runCatching {
            dirDocument?.isDirectory == true
        }.getOrDefault(false)
        if (!isDirectory) {
            val fileResults = requiredFiles.map { it.key to ConfigurationDirResult.FileStatus.MISSING }
            return ConfigurationDirResult(consoleType, ConfigurationDirResult.Status.INVALID, requiredFiles.keys.toTypedArray(), fileResults.toTypedArray())
        }
        val validDirDocument = dirDocument ?: run {
            val fileResults = requiredFiles.map { it.key to ConfigurationDirResult.FileStatus.MISSING }
            return ConfigurationDirResult(consoleType, ConfigurationDirResult.Status.INVALID, requiredFiles.keys.toTypedArray(), fileResults.toTypedArray())
        }

        val fileResults = requiredFiles.map {
            it.key to it.value.invoke(validDirDocument)
        }

        val result = if (fileResults.any { it.second != ConfigurationDirResult.FileStatus.PRESENT }) {
            ConfigurationDirResult.Status.INVALID
        } else {
            ConfigurationDirResult.Status.VALID
        }
        return ConfigurationDirResult(consoleType, result, requiredFiles.keys.toTypedArray(), fileResults.toTypedArray())
    }

    private fun getDSBios7Status(configurationDir: DocumentFile): ConfigurationDirResult.FileStatus {
        return getBiosFileStatus(configurationDir, "bios7.bin", 0x4000.toLong())
    }

    private fun getDSBios9Status(configurationDir: DocumentFile): ConfigurationDirResult.FileStatus {
        return getBiosFileStatus(configurationDir, "bios9.bin", 0x1000.toLong())
    }

    private fun getDSFirmwareStatus(configurationDir: DocumentFile): ConfigurationDirResult.FileStatus {
        val firmwareDocument =configurationDir.findFile("firmware.bin") ?: return ConfigurationDirResult.FileStatus.MISSING
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(firmwareDocument.uri, "r")?.use {
                when (it.length) {
                    AssetFileDescriptor.UNKNOWN_LENGTH -> ConfigurationDirResult.FileStatus.MISSING
                    0x20000.toLong(),
                    0x40000.toLong(),
                    0x80000.toLong() -> ConfigurationDirResult.FileStatus.PRESENT
                    else -> ConfigurationDirResult.FileStatus.INVALID
                }
            } ?: ConfigurationDirResult.FileStatus.MISSING
        }.getOrDefault(ConfigurationDirResult.FileStatus.MISSING)
    }

    private fun getDSiBios7Status(configurationDir: DocumentFile): ConfigurationDirResult.FileStatus {
        return getBiosFileStatus(configurationDir, "bios7.bin", 0x10000.toLong())
    }

    private fun getDSiBios9Status(configurationDir: DocumentFile): ConfigurationDirResult.FileStatus {
        return getBiosFileStatus(configurationDir, "bios9.bin", 0x10000.toLong())
    }

    private fun getDSiFirmwareStatus(configurationDir: DocumentFile): ConfigurationDirResult.FileStatus {
        val firmwareDocument = configurationDir.findFile("firmware.bin") ?: return ConfigurationDirResult.FileStatus.MISSING
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(firmwareDocument.uri, "r")?.use {
                when (it.length) {
                    AssetFileDescriptor.UNKNOWN_LENGTH -> ConfigurationDirResult.FileStatus.MISSING
                    0x20000.toLong() -> ConfigurationDirResult.FileStatus.PRESENT
                    else -> ConfigurationDirResult.FileStatus.INVALID
                }
            } ?: ConfigurationDirResult.FileStatus.MISSING
        }.getOrDefault(ConfigurationDirResult.FileStatus.MISSING)
    }

    private fun getDSiNandStatus(configurationDir: DocumentFile): ConfigurationDirResult.FileStatus {
        val nandDocument = configurationDir.findFile("nand.bin") ?: return ConfigurationDirResult.FileStatus.MISSING
        val isFile = runCatching { nandDocument.isFile }.getOrDefault(false)
        if (!isFile) {
            return ConfigurationDirResult.FileStatus.MISSING
        }

        return runCatching {
            context.contentResolver.openFileDescriptor(nandDocument.uri, "rw")?.use {
                ConfigurationDirResult.FileStatus.PRESENT
            } ?: ConfigurationDirResult.FileStatus.MISSING
        }.getOrDefault(ConfigurationDirResult.FileStatus.MISSING)
    }

    private fun getBiosFileStatus(configurationDir: DocumentFile, fileName: String, requiredSize: Long): ConfigurationDirResult.FileStatus {
        val biosDocument = configurationDir.findFile(fileName) ?: return ConfigurationDirResult.FileStatus.MISSING
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(biosDocument.uri, "r")?.use {
                when (it.length) {
                    AssetFileDescriptor.UNKNOWN_LENGTH -> ConfigurationDirResult.FileStatus.MISSING
                    requiredSize -> ConfigurationDirResult.FileStatus.PRESENT
                    else -> ConfigurationDirResult.FileStatus.INVALID
                }
            } ?: ConfigurationDirResult.FileStatus.MISSING
        }.getOrDefault(ConfigurationDirResult.FileStatus.MISSING)
    }

    private fun getRequiredFilesVerifiers(consoleType: ConsoleType): Map<String, (DocumentFile) -> ConfigurationDirResult.FileStatus> {
        return when(consoleType) {
            ConsoleType.DS -> mapOf(
                    "bios7.bin" to this::getDSBios7Status,
                    "bios9.bin" to this::getDSBios9Status,
                    "firmware.bin" to this::getDSFirmwareStatus
            )
            ConsoleType.DSi -> mapOf(
                    "bios7.bin" to this::getDSiBios7Status,
                    "bios9.bin" to this::getDSiBios9Status,
                    "firmware.bin" to this::getDSiFirmwareStatus,
                    "nand.bin" to this::getDSiNandStatus
            )
        }
    }
}
