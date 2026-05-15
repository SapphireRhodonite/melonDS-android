package me.magnum.melonds.ui.inputsetup

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.magnum.melonds.domain.model.ControllerConfiguration
import me.magnum.melonds.domain.model.Input
import me.magnum.melonds.domain.model.InputConfig
import me.magnum.melonds.domain.model.Slot2AnalogMapping
import me.magnum.melonds.domain.model.rom.config.RomInputMode
import me.magnum.melonds.domain.repositories.RomsRepository
import me.magnum.melonds.domain.repositories.SettingsRepository
import me.magnum.melonds.parcelables.RomParcelable
import me.magnum.melonds.utils.EventSharedFlow
import javax.inject.Inject

@HiltViewModel
class InputSetupViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val romsRepository: RomsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    enum class Slot2AnalogAxisTarget {
        X,
        Y,
    }

    private var rom = savedStateHandle.get<RomParcelable>(InputSetupActivity.KEY_ROM)?.rom

    private val initialConfiguration = rom?.config?.customControllerConfiguration
        ?: settingsRepository.getControllerConfiguration()
    private val _inputConfig = MutableStateFlow(initialConfiguration.copy().inputMapper)
    val inputConfiguration = _inputConfig.asStateFlow()

    private val _slot2AnalogMapping = MutableStateFlow(initialConfiguration.slot2AnalogMapping)
    val slot2AnalogMapping = _slot2AnalogMapping.asStateFlow()

    private val _inputUnderAssignment = MutableStateFlow<Input?>(null)
    val inputUnderAssignment = _inputUnderAssignment.asStateFlow()

    private val _slot2AxisUnderAssignment = MutableStateFlow<Slot2AnalogAxisTarget?>(null)
    val slot2AxisUnderAssignment = _slot2AxisUnderAssignment.asStateFlow()

    private val _onInputAssignedEvent = EventSharedFlow<Input>()
    val onInputAssignedEvent = _onInputAssignedEvent.asSharedFlow()

    private var hasLocalChanges = false

    init {
        rom?.let { initialRom ->
            viewModelScope.launch {
                val refreshedRom = romsRepository.getRomAtUri(initialRom.uri) ?: return@launch
                if (hasLocalChanges) {
                    return@launch
                }
                val refreshedConfiguration = refreshedRom.config.customControllerConfiguration
                    ?: settingsRepository.getControllerConfiguration()
                rom = refreshedRom
                _inputConfig.value = refreshedConfiguration.copy().inputMapper
                _slot2AnalogMapping.value = refreshedConfiguration.slot2AnalogMapping
            }
        }
    }

    fun startInputAssignment(input: Input) {
        _slot2AxisUnderAssignment.value = null
        _inputUnderAssignment.value = input
    }

    fun stopInputAssignment() {
        _inputUnderAssignment.value = null
    }

    fun startSlot2AxisAssignment(target: Slot2AnalogAxisTarget) {
        _inputUnderAssignment.value = null
        _slot2AxisUnderAssignment.value = target
    }

    fun stopSlot2AxisAssignment() {
        _slot2AxisUnderAssignment.value = null
    }

    fun stopAnyAssignment() {
        _inputUnderAssignment.value = null
        _slot2AxisUnderAssignment.value = null
    }

    fun updateInputAssignedKey(key: Int) {
        val inputUnderAssignment = _inputUnderAssignment.value ?: return
        val inputType = InputConfig.Assignment.Key(null, key)
        setInputAssignment(inputUnderAssignment, inputType)
        focusOnNextInput(inputUnderAssignment)
    }

    fun updateInputAssignedAxis(axis: Int, direction: InputConfig.Assignment.Axis.Direction) {
        val inputUnderAssignment = _inputUnderAssignment.value ?: return
        val inputType = InputConfig.Assignment.Axis(null, axis, direction)
        setInputAssignment(inputUnderAssignment, inputType)
        focusOnNextInput(inputUnderAssignment)
    }

    fun clearInputAssignment(input: Input) {
        setInputAssignment(input, InputConfig.Assignment.None)
        _inputUnderAssignment.value = null
    }

    fun updateSlot2AxisAssignment(axisCode: Int, deviceId: Int) {
        val target = _slot2AxisUnderAssignment.value ?: return
        val currentMapping = _slot2AnalogMapping.value
        val nextMapping = when (target) {
            Slot2AnalogAxisTarget.X -> currentMapping.copy(axisXCode = axisCode, deviceId = deviceId)
            Slot2AnalogAxisTarget.Y -> currentMapping.copy(axisYCode = axisCode, deviceId = deviceId)
        }
        updateSlot2AnalogMapping(nextMapping)
        _slot2AxisUnderAssignment.value = null
    }

    fun setSlot2InvertX(invert: Boolean) {
        updateSlot2AnalogMapping(_slot2AnalogMapping.value.copy(invertX = invert))
    }

    fun setSlot2InvertY(invert: Boolean) {
        updateSlot2AnalogMapping(_slot2AnalogMapping.value.copy(invertY = invert))
    }

    fun setSlot2Deadzone(deadzone: Float) {
        updateSlot2AnalogMapping(_slot2AnalogMapping.value.copy(deadzone = deadzone.coerceIn(0f, 1f)))
    }

    fun setSlot2UseDeviceFilter(enabled: Boolean) {
        val currentMapping = _slot2AnalogMapping.value
        val shouldEnable = enabled && currentMapping.deviceId != null
        updateSlot2AnalogMapping(currentMapping.copy(useDeviceFilter = shouldEnable))
    }

    private fun setInputAssignment(input: Input, assignment: InputConfig.Assignment) {
        val inputIndex = _inputConfig.value.indexOfFirst { it.input == input }
        if (inputIndex >= 0) {
            _inputConfig.update { config ->
                config.toMutableList().apply {
                    val current = this[inputIndex]
                    var primary = current.assignment
                    var secondary = current.altAssignment
                    when (assignment) {
                        InputConfig.Assignment.None -> {
                            primary = InputConfig.Assignment.None
                            secondary = InputConfig.Assignment.None
                        }
                        is InputConfig.Assignment.Key -> {
                            if (primary == InputConfig.Assignment.None || primary == assignment) {
                                primary = assignment
                            } else if (secondary == InputConfig.Assignment.None || secondary == assignment) {
                                secondary = assignment
                            } else {
                                secondary = assignment
                            }
                        }
                        is InputConfig.Assignment.Axis -> {
                            if (primary == InputConfig.Assignment.None|| primary == assignment) {
                                primary = assignment
                            } else if (secondary == InputConfig.Assignment.None || secondary == assignment) {
                                secondary = assignment
                            } else {
                                secondary = assignment
                            }
                        }
                    }
                    this[inputIndex] = current.copy(assignment = primary, altAssignment = secondary)
                }.also {
                    onConfigsChanged(it)
                }
            }
        }
        _inputUnderAssignment.value = null
    }

    private fun onConfigsChanged(newConfig: List<InputConfig>) {
        persistConfiguration(
            newConfig = newConfig,
            newMapping = _slot2AnalogMapping.value,
        )
    }

    private fun updateSlot2AnalogMapping(newMapping: Slot2AnalogMapping) {
        _slot2AnalogMapping.value = newMapping
        persistConfiguration(
            newConfig = _inputConfig.value,
            newMapping = newMapping,
        )
    }

    private fun persistConfiguration(newConfig: List<InputConfig>, newMapping: Slot2AnalogMapping) {
        val currentConfiguration = ControllerConfiguration(
            configList = newConfig,
            slot2AnalogMapping = newMapping,
        )
        hasLocalChanges = true
        val currentRom = rom
        if (currentRom != null) {
            val updatedConfig = currentRom.config.copy(
                inputMode = RomInputMode.CUSTOM,
                customControllerConfiguration = currentConfiguration,
            )
            romsRepository.updateRomConfig(currentRom, updatedConfig)
            rom = currentRom.copy(config = updatedConfig)
        } else {
            settingsRepository.setControllerConfiguration(currentConfiguration)
        }
    }

    private fun focusOnNextInput(currentInput: Input) {
        val currentInputIndex = _inputConfig.value.indexOfFirst { it.input == currentInput }
        val nextInput = _inputConfig.value.getOrNull(currentInputIndex + 1)
        if (nextInput != null) {
            _onInputAssignedEvent.tryEmit(nextInput.input)
        }
    }
}
