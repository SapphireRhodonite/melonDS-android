package me.magnum.melonds.ui.romdetails.ui

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.magnum.melonds.R
import me.magnum.melonds.common.Permission
import me.magnum.melonds.common.contracts.FilePickerContract
import me.magnum.melonds.domain.model.VideoFiltering
import me.magnum.melonds.domain.model.VideoRenderer
import me.magnum.melonds.domain.model.rom.Rom
import me.magnum.melonds.domain.model.rom.config.RomConfig
import me.magnum.melonds.domain.model.rom.config.RomInputMode
import me.magnum.melonds.domain.model.rom.config.RuntimeConsoleType
import me.magnum.melonds.domain.model.rom.config.RuntimeMicSource
import me.magnum.melonds.ui.common.MelonPreviewSet
import me.magnum.melonds.ui.common.component.dialog.SingleChoiceDialog
import me.magnum.melonds.ui.common.component.dialog.TextInputDialog
import me.magnum.melonds.ui.common.component.dialog.rememberSingleChoiceDialogState
import me.magnum.melonds.ui.common.component.dialog.rememberTextInputDialogState
import me.magnum.melonds.ui.inputsetup.InputSetupActivity
import me.magnum.melonds.ui.layouts.LayoutSelectorActivity
import me.magnum.melonds.ui.romdetails.model.RomConfigUiModel
import me.magnum.melonds.ui.romdetails.model.RomConfigUiState
import me.magnum.melonds.ui.romdetails.model.RomConfigUpdateEvent
import me.magnum.melonds.ui.romdetails.model.RomGbaSlotConfigUiModel
import me.magnum.melonds.ui.theme.MelonTheme
import java.util.Date
import java.util.UUID

private const val GLES_3_2 = 0x30002

@Composable
fun RomConfigUi(
    modifier: Modifier,
    contentPadding: PaddingValues,
    rom: Rom,
    romConfigUiState: RomConfigUiState,
    onConfigUpdate: (RomConfigUpdateEvent) -> Unit,
    onCustomInputConfigEdited: () -> Unit,
) {
    when (romConfigUiState) {
        is RomConfigUiState.Loading -> Loading(modifier.padding(contentPadding))
        is RomConfigUiState.Ready -> Content(
            modifier = modifier,
            contentPadding = contentPadding,
            rom = rom,
            romConfig = romConfigUiState.romConfigUiModel,
            onConfigUpdate = onConfigUpdate,
            onCustomInputConfigEdited = onCustomInputConfigEdited,
        )
    }
}

@Composable
private fun Loading(modifier: Modifier) {
    Box(modifier) {
        CircularProgressIndicator(
            modifier = Modifier.align(Alignment.Center),
            color = MaterialTheme.colors.secondary,
        )
    }
}

@Composable
private fun Content(
    modifier: Modifier,
    contentPadding: PaddingValues,
    rom: Rom,
    romConfig: RomConfigUiModel,
    onConfigUpdate: (RomConfigUpdateEvent) -> Unit,
    onCustomInputConfigEdited: () -> Unit,
) {
    val context = LocalContext.current
    val renameDialogState = rememberTextInputDialogState()
    val consoleDialogState = rememberSingleChoiceDialogState<RuntimeConsoleType>()
    val micDialogState = rememberSingleChoiceDialogState<RuntimeMicSource>()
    val inputModeDialogState = rememberSingleChoiceDialogState<RomInputMode>()
    val gbaSlotDialogState = rememberSingleChoiceDialogState<RomGbaSlotConfigUiModel.Type>()
    val videoRendererDialogState = rememberSingleChoiceDialogState<VideoRenderer?>()
    val threadedRenderingDialogState = rememberSingleChoiceDialogState<Boolean?>()
    val internalResolutionDialogState = rememberSingleChoiceDialogState<Int?>()
    val videoFilteringDialogState = rememberSingleChoiceDialogState<VideoFiltering?>()
    val retroArchPresetPathDialogState = rememberTextInputDialogState()
    val retroArchParametersDialogState = rememberTextInputDialogState()

    val customInputSetupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onCustomInputConfigEdited()
    }
    val layoutSelectorLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val layoutId = result.data?.getStringExtra(LayoutSelectorActivity.KEY_SELECTED_LAYOUT_ID)?.let { UUID.fromString(it) }
            onConfigUpdate(RomConfigUpdateEvent.LayoutUpdate(layoutId))
        }
    }
    val gbaRomSelectorLauncher = rememberLauncherForActivityResult(FilePickerContract(Permission.READ)) { result ->
        if (result != null) onConfigUpdate(RomConfigUpdateEvent.GbaRomPathUpdate(result))
    }
    val gbaSaveSelectorLauncher = rememberLauncherForActivityResult(FilePickerContract(Permission.READ_WRITE)) { result ->
        if (result != null) onConfigUpdate(RomConfigUpdateEvent.GbaSavePathUpdate(result))
    }

    val consoleOptions = stringArrayResource(id = R.array.game_runtime_console_type_options)
    val micOptions = stringArrayResource(id = R.array.game_runtime_mic_source_options)
    val inputModeOptions = stringArrayResource(id = R.array.rom_input_mode_options)
    val gbaSlotOptions = stringArrayResource(id = R.array.gba_slot_options)
    val rendererOptions = stringArrayResource(id = R.array.video_renderer_options)
    val internalResolutionOptions = stringArrayResource(id = R.array.video_internal_resolution_options)
    val videoFilteringOptions = stringArrayResource(id = R.array.video_filtering_options)
    val useGlobal = stringResource(R.string.use_global_preference)
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val supportsOpenGlRenderer = (activityManager?.deviceConfigurationInfo?.reqGlEsVersion ?: 0) >= GLES_3_2
    val supportsComputeRenderer = supportsOpenGlRenderer && Build.HARDWARE.equals("qcom", ignoreCase = true)
    fun supportsRenderer(renderer: VideoRenderer): Boolean {
        return when (renderer) {
            VideoRenderer.OPENGL -> supportsOpenGlRenderer
            VideoRenderer.COMPUTE -> supportsComputeRenderer
            else -> true
        }
    }
    val selectedRenderer = romConfig.videoRenderer?.takeIf { supportsRenderer(it) }
    val effectiveRenderer = selectedRenderer ?: romConfig.globalVideoRenderer
    fun supportsFiltering(renderer: VideoRenderer, filtering: VideoFiltering): Boolean {
        return when (renderer) {
            VideoRenderer.VULKAN -> filtering.isSupportedByVulkan()
            else -> filtering.isSupportedByOpenGlSurface()
        }
    }
    val selectedFiltering = romConfig.videoFiltering?.takeIf { supportsFiltering(effectiveRenderer, it) }
    val effectiveGlobalFiltering = romConfig.globalVideoFiltering.takeIf { supportsFiltering(effectiveRenderer, it) }
        ?: VideoFiltering.NONE
    val effectiveFiltering = selectedFiltering ?: effectiveGlobalFiltering
    fun useGlobalWithValue(value: String): String {
        return context.getString(R.string.use_global_preference_with_value, value)
    }
    val globalConsoleLabel = consoleOptions[romConfig.globalRuntimeConsoleType.ordinal + 1]
    val globalMicLabel = micOptions[romConfig.globalRuntimeMicSource.ordinal + 1]
    val globalInputModeLabel = context.getString(R.string.global_controller_mapping)
    val globalLayoutLabel = romConfig.globalLayoutName ?: context.getString(R.string.not_set)
    val globalRendererLabel = rendererOptions[romConfig.globalVideoRenderer.ordinal]
    val globalThreadedRenderingLabel = if (romConfig.globalThreadedRendering) {
        context.getString(R.string.on)
    } else {
        context.getString(R.string.off)
    }
    val globalInternalResolutionLabel = internalResolutionOptions[
        (romConfig.globalInternalResolutionScaling - 1).coerceIn(internalResolutionOptions.indices)
    ]
    val globalVideoFilteringLabel = videoFilteringOptions[effectiveGlobalFiltering.ordinal]
    val globalRetroArchPresetPathLabel = romConfig.globalRetroArchShaderPresetPath ?: context.getString(R.string.not_set)
    val globalRetroArchParametersLabel = romConfig.globalRetroArchShaderParameters ?: context.getString(R.string.not_set)
    val rendererItems = buildList<VideoRenderer?> {
        add(null)
        add(VideoRenderer.SOFTWARE)
        if (supportsOpenGlRenderer) {
            add(VideoRenderer.OPENGL)
        }
        add(VideoRenderer.VULKAN)
        if (supportsComputeRenderer) {
            add(VideoRenderer.COMPUTE)
        }
    }
    val filteringItems = listOf(null) + VideoFiltering.entries.filter { filtering ->
        supportsFiltering(effectiveRenderer, filtering)
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(
                start = contentPadding.calculateStartPadding(LocalLayoutDirection.current),
                end = contentPadding.calculateEndPadding(LocalLayoutDirection.current),
            ),
    ) {
        ConfigSection(title = stringResource(R.string.rom_details_configuration_tab)) {
            ConfigRow(
                title = stringResource(R.string.label_rom_config_custom_name),
                value = romConfig.customName ?: rom.name,
                onClick = {
                    renameDialogState.show(
                        initialText = romConfig.customName ?: rom.name,
                        onConfirm = { newName -> onConfigUpdate(RomConfigUpdateEvent.CustomNameUpdate(newName.ifBlank { null })) },
                    )
                },
            )
        }

        ConfigSection(title = stringResource(R.string.console_type)) {
            ConfigRow(
                title = stringResource(R.string.label_rom_config_console),
                value = if (romConfig.runtimeConsoleType == RuntimeConsoleType.DEFAULT) {
                    useGlobalWithValue(globalConsoleLabel)
                } else {
                    consoleOptions[romConfig.runtimeConsoleType.ordinal]
                },
                enabled = !rom.isDsiWareTitle,
                showDivider = true,
                onClick = {
                    consoleDialogState.show(
                        title = context.getString(R.string.label_rom_config_console),
                        items = RuntimeConsoleType.entries.toList(),
                        labelOf = {
                            if (it == RuntimeConsoleType.DEFAULT) {
                                useGlobalWithValue(globalConsoleLabel)
                            } else {
                                consoleOptions[it.ordinal]
                            }
                        },
                        selected = romConfig.runtimeConsoleType,
                        onSelect = { onConfigUpdate(RomConfigUpdateEvent.RuntimeConsoleUpdate(it)) },
                    )
                },
            )
            ConfigRow(
                title = stringResource(R.string.microphone_source),
                value = if (romConfig.runtimeMicSource == RuntimeMicSource.DEFAULT) {
                    useGlobalWithValue(globalMicLabel)
                } else {
                    micOptions[romConfig.runtimeMicSource.ordinal]
                },
                showDivider = true,
                onClick = {
                    micDialogState.show(
                        title = context.getString(R.string.microphone_source),
                        items = RuntimeMicSource.entries.toList(),
                        labelOf = {
                            if (it == RuntimeMicSource.DEFAULT) {
                                useGlobalWithValue(globalMicLabel)
                            } else {
                                micOptions[it.ordinal]
                            }
                        },
                        selected = romConfig.runtimeMicSource,
                        onSelect = { onConfigUpdate(RomConfigUpdateEvent.RuntimeMicSourceUpdate(it)) },
                    )
                },
            )
            ConfigToggleRow(
                title = stringResource(R.string.label_rom_config_hg_engine_fix),
                isOn = romConfig.useHgEngineFix,
                onToggle = { onConfigUpdate(RomConfigUpdateEvent.UseHgEngineFixUpdate(it)) },
            )
        }

        ConfigSection(title = stringResource(R.string.label_rom_config_video)) {
            ConfigRow(
                title = stringResource(R.string.renderer),
                value = selectedRenderer?.let { rendererOptions[it.ordinal] } ?: useGlobalWithValue(globalRendererLabel),
                showDivider = true,
                onClick = {
                    videoRendererDialogState.show(
                        title = context.getString(R.string.renderer),
                        items = rendererItems,
                        labelOf = { renderer -> renderer?.let { rendererOptions[it.ordinal] } ?: useGlobalWithValue(globalRendererLabel) },
                        selected = selectedRenderer,
                        onSelect = { onConfigUpdate(RomConfigUpdateEvent.VideoRendererUpdate(it)) },
                    )
                },
            )
            AnimatedVisibility(visible = effectiveRenderer == VideoRenderer.SOFTWARE) {
                ConfigRow(
                    title = stringResource(R.string.threaded_rendering),
                    value = when (romConfig.threadedRendering) {
                        true -> stringResource(R.string.on)
                        false -> stringResource(R.string.off)
                        null -> useGlobalWithValue(globalThreadedRenderingLabel)
                    },
                    showDivider = true,
                    onClick = {
                        threadedRenderingDialogState.show(
                            title = context.getString(R.string.threaded_rendering),
                            items = listOf(null, true, false),
                            labelOf = {
                                when (it) {
                                    true -> context.getString(R.string.on)
                                    false -> context.getString(R.string.off)
                                    null -> useGlobalWithValue(globalThreadedRenderingLabel)
                                }
                            },
                            selected = romConfig.threadedRendering,
                            onSelect = { onConfigUpdate(RomConfigUpdateEvent.ThreadedRenderingUpdate(it)) },
                        )
                    },
                )
            }
            AnimatedVisibility(visible = effectiveRenderer == VideoRenderer.OPENGL || effectiveRenderer == VideoRenderer.VULKAN) {
                ConfigRow(
                    title = stringResource(R.string.internal_resolution),
                    value = romConfig.internalResolutionScaling?.let { internalResolutionOptions[(it - 1).coerceIn(internalResolutionOptions.indices)] }
                        ?: useGlobalWithValue(globalInternalResolutionLabel),
                    showDivider = true,
                    onClick = {
                        internalResolutionDialogState.show(
                            title = context.getString(R.string.internal_resolution),
                            items = listOf(null) + (1..internalResolutionOptions.size).toList(),
                            labelOf = { scaling ->
                                scaling?.let { internalResolutionOptions[(it - 1).coerceIn(internalResolutionOptions.indices)] }
                                    ?: useGlobalWithValue(globalInternalResolutionLabel)
                            },
                            selected = romConfig.internalResolutionScaling,
                            onSelect = { onConfigUpdate(RomConfigUpdateEvent.InternalResolutionScalingUpdate(it)) },
                        )
                    },
                )
            }
            ConfigRow(
                title = stringResource(R.string.filter),
                value = selectedFiltering?.let { videoFilteringOptions[it.ordinal] } ?: useGlobalWithValue(globalVideoFilteringLabel),
                showDivider = effectiveRenderer == VideoRenderer.VULKAN && effectiveFiltering == VideoFiltering.RETROARCH,
                onClick = {
                    videoFilteringDialogState.show(
                        title = context.getString(R.string.filter),
                        items = filteringItems,
                        labelOf = { filtering -> filtering?.let { videoFilteringOptions[it.ordinal] } ?: useGlobalWithValue(globalVideoFilteringLabel) },
                        selected = selectedFiltering,
                        onSelect = { onConfigUpdate(RomConfigUpdateEvent.VideoFilteringUpdate(it)) },
                    )
                },
            )
            AnimatedVisibility(visible = effectiveRenderer == VideoRenderer.VULKAN && effectiveFiltering == VideoFiltering.RETROARCH) {
                Column {
                    ConfigRow(
                        title = stringResource(R.string.video_retroarch_shader_preset_title),
                        value = romConfig.retroArchShaderPresetPath ?: useGlobalWithValue(globalRetroArchPresetPathLabel),
                        showDivider = true,
                        onClick = {
                            if (romConfig.hasValidRetroArchShaderRoot) {
                                retroArchPresetPathDialogState.show(
                                    initialText = romConfig.retroArchShaderPresetPath.orEmpty(),
                                    onConfirm = { onConfigUpdate(RomConfigUpdateEvent.RetroArchShaderPresetPathUpdate(it.ifBlank { null })) },
                                )
                            } else {
                                Toast.makeText(context, R.string.retroarch_shader_root_not_valid, Toast.LENGTH_LONG).show()
                            }
                        },
                    )
                    ConfigRow(
                        title = stringResource(R.string.video_retroarch_shader_parameters_title),
                        value = romConfig.retroArchShaderParameters ?: useGlobalWithValue(globalRetroArchParametersLabel),
                        onClick = {
                            if (romConfig.hasValidRetroArchShaderRoot) {
                                retroArchParametersDialogState.show(
                                    initialText = romConfig.retroArchShaderParameters.orEmpty(),
                                    onConfirm = { onConfigUpdate(RomConfigUpdateEvent.RetroArchShaderParametersUpdate(it.ifBlank { null })) },
                                )
                            } else {
                                Toast.makeText(context, R.string.retroarch_shader_root_not_valid, Toast.LENGTH_LONG).show()
                            }
                        },
                    )
                }
            }
        }

        ConfigSection(title = stringResource(R.string.label_rom_config_input_mode)) {
            ConfigRow(
                title = stringResource(R.string.label_rom_config_input_mode),
                value = if (romConfig.inputMode == RomInputMode.GLOBAL) {
                    useGlobalWithValue(globalInputModeLabel)
                } else {
                    inputModeOptions[romConfig.inputMode.ordinal]
                },
                showDivider = romConfig.inputMode == RomInputMode.CUSTOM,
                onClick = {
                    inputModeDialogState.show(
                        title = context.getString(R.string.label_rom_config_input_mode),
                        items = RomInputMode.entries.toList(),
                        labelOf = {
                            if (it == RomInputMode.GLOBAL) {
                                useGlobalWithValue(globalInputModeLabel)
                            } else {
                                inputModeOptions[it.ordinal]
                            }
                        },
                        selected = romConfig.inputMode,
                        onSelect = { onConfigUpdate(RomConfigUpdateEvent.InputModeUpdate(it)) },
                    )
                },
            )
            AnimatedVisibility(visible = romConfig.inputMode == RomInputMode.CUSTOM) {
                ConfigRow(
                    title = stringResource(R.string.label_rom_config_custom_input_mapping),
                    value = stringResource(R.string.edit),
                    onClick = {
                        customInputSetupLauncher.launch(InputSetupActivity.getRomCustomIntent(context, rom))
                    },
                )
            }
        }

        ConfigSection(title = stringResource(R.string.controller_layout)) {
            ConfigRow(
                title = stringResource(R.string.controller_layout),
                value = romConfig.layoutName ?: useGlobalWithValue(globalLayoutLabel),
                onClick = {
                    val intent = Intent(context, LayoutSelectorActivity::class.java).apply {
                        putExtra(LayoutSelectorActivity.KEY_SELECTED_LAYOUT_ID, romConfig.layoutId?.toString())
                    }
                    layoutSelectorLauncher.launch(intent)
                },
            )
        }

        ConfigSection(title = stringResource(R.string.label_rom_config_gba_slot)) {
            val isGbaRom = romConfig.gbaSlotConfig.type == RomGbaSlotConfigUiModel.Type.GbaRom
            ConfigRow(
                title = stringResource(R.string.label_rom_config_gba_slot),
                value = gbaSlotOptions[romConfig.gbaSlotConfig.type.ordinal],
                showDivider = isGbaRom,
                onClick = {
                    gbaSlotDialogState.show(
                        title = context.getString(R.string.label_rom_config_gba_slot),
                        items = RomGbaSlotConfigUiModel.Type.entries.toList(),
                        labelOf = { gbaSlotOptions[it.ordinal] },
                        selected = romConfig.gbaSlotConfig.type,
                        onSelect = { onConfigUpdate(RomConfigUpdateEvent.GbaSlotTypeUpdated(it)) },
                    )
                },
            )
            AnimatedVisibility(visible = isGbaRom) {
                Column {
                    ConfigRow(
                        title = stringResource(R.string.label_rom_config_gba_rom_path),
                        value = romConfig.gbaSlotConfig.gbaRomPath ?: stringResource(R.string.not_set),
                        showDivider = true,
                        onClick = { gbaRomSelectorLauncher.launch(Pair(null, null)) },
                    )
                    ConfigRow(
                        title = stringResource(R.string.label_rom_config_gba_save_path),
                        value = romConfig.gbaSlotConfig.gbaSavePath ?: stringResource(R.string.not_set),
                        onClick = { gbaSaveSelectorLauncher.launch(Pair(null, null)) },
                    )
                }
            }
        }

        Spacer(Modifier.height(contentPadding.calculateBottomPadding() + 16.dp))
    }

    TextInputDialog(
        title = stringResource(R.string.label_rom_config_custom_name),
        dialogState = renameDialogState,
        textValidator = { true },
        onDelete = { onConfigUpdate(RomConfigUpdateEvent.CustomNameUpdate(null)) },
    )
    SingleChoiceDialog(consoleDialogState)
    SingleChoiceDialog(micDialogState)
    SingleChoiceDialog(inputModeDialogState)
    SingleChoiceDialog(gbaSlotDialogState)
    SingleChoiceDialog(videoRendererDialogState)
    SingleChoiceDialog(threadedRenderingDialogState)
    SingleChoiceDialog(internalResolutionDialogState)
    SingleChoiceDialog(videoFilteringDialogState)
    TextInputDialog(
        title = stringResource(R.string.video_retroarch_shader_preset_title),
        dialogState = retroArchPresetPathDialogState,
        textValidator = { true },
        onDelete = { onConfigUpdate(RomConfigUpdateEvent.RetroArchShaderPresetPathUpdate(null)) },
    )
    TextInputDialog(
        title = stringResource(R.string.video_retroarch_shader_parameters_title),
        dialogState = retroArchParametersDialogState,
        textValidator = { true },
        onDelete = { onConfigUpdate(RomConfigUpdateEvent.RetroArchShaderParametersUpdate(null)) },
    )
}

@MelonPreviewSet
@Composable
private fun PreviewRomConfigUi() {
    MelonTheme {
        RomConfigUi(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(0.dp),
            rom = Rom(
                name = "Professor Layton and the Unwound Future",
                developerName = "Nontendo",
                fileName = "layton.nds",
                uri = Uri.EMPTY,
                parentTreeUri = Uri.EMPTY,
                config = RomConfig(),
                lastPlayed = Date(),
                isDsiWareTitle = false,
                retroAchievementsHash = "",
            ),
            romConfigUiState = RomConfigUiState.Ready(
                RomConfigUiModel(
                    layoutName = "Default",
                    gbaSlotConfig = RomGbaSlotConfigUiModel(type = RomGbaSlotConfigUiModel.Type.GbaRom)
                ),
            ),
            onConfigUpdate = { },
            onCustomInputConfigEdited = { },
        )
    }
}
