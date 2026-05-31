package me.magnum.melonds.ui.inputsetup

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import me.magnum.melonds.domain.model.InputConfig
import me.magnum.melonds.domain.model.rom.Rom
import me.magnum.melonds.parcelables.RomParcelable
import me.magnum.melonds.ui.inputsetup.ui.InputSetupScreen
import me.magnum.melonds.ui.theme.MelonTheme
import kotlin.math.absoluteValue

@AndroidEntryPoint
class InputSetupActivity : AppCompatActivity() {

    companion object {
        const val KEY_ROM = "rom"

        fun getGlobalIntent(context: Context): Intent {
            return Intent(context, InputSetupActivity::class.java)
        }

        fun getRomCustomIntent(context: Context, rom: Rom): Intent {
            return Intent(context, InputSetupActivity::class.java).apply {
                putExtra(KEY_ROM, RomParcelable(rom))
            }
        }
    }

    private val viewModel: InputSetupViewModel by viewModels()

    private val referenceAxisValues = mutableMapOf<Pair<Int, Int>, Float>()

    private fun MotionEvent.isControllerMotionEvent(): Boolean {
        return isFromSource(InputDevice.SOURCE_CLASS_JOYSTICK)
            || isFromSource(InputDevice.SOURCE_JOYSTICK)
            || isFromSource(InputDevice.SOURCE_GAMEPAD)
    }

    private fun InputDevice.MotionRange.isJoystickOrGamepadRange(): Boolean {
        return isFromSource(InputDevice.SOURCE_CLASS_JOYSTICK)
            || isFromSource(InputDevice.SOURCE_JOYSTICK)
            || isFromSource(InputDevice.SOURCE_GAMEPAD)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)

        setContent {
            MelonTheme {
                InputSetupScreen(
                    viewModel = viewModel,
                    onBackClick = ::onNavigateUp,
                )
            }
        }

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.inputUnderAssignment, viewModel.slot2AxisUnderAssignment) { inputAssignment, slot2Assignment ->
                    inputAssignment != null || slot2Assignment != null
                }.collect { hasPendingAssignment ->
                    if (hasPendingAssignment) {
                        // A new assignment has started. Reset reference values
                        referenceAxisValues.clear()
                        InputDevice.getDeviceIds().forEach { deviceId ->
                            InputDevice.getDevice(deviceId)?.motionRanges?.forEach { range ->
                                if (range.isJoystickOrGamepadRange()) {
                                    referenceAxisValues[deviceId to range.axis] = 0f
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val inputAssignment = viewModel.inputUnderAssignment.value
        val slot2Assignment = viewModel.slot2AxisUnderAssignment.value
        if ((inputAssignment != null || slot2Assignment != null) && event.isControllerMotionEvent()) {
            if (event.action == MotionEvent.ACTION_MOVE) {
                val joystickAxes = event.device?.motionRanges
                    ?.asSequence()
                    ?.filter { it.isJoystickOrGamepadRange() }
                    ?.map { it.axis }
                    ?.distinct()
                    ?.toList()
                    ?: emptyList()
                val detectedAxis = joystickAxes.firstOrNull { axis ->
                    val key = event.deviceId to axis
                    val baseline = referenceAxisValues[key] ?: 0f
                    val currentValue = event.getAxisValue(axis)
                    (currentValue - baseline).absoluteValue >= 0.5f
                }

                if (detectedAxis != null) {
                    val initialValue = referenceAxisValues[event.deviceId to detectedAxis] ?: 0f
                    val currentValue = event.getAxisValue(detectedAxis)
                    val delta = currentValue - initialValue
                    if (inputAssignment != null) {
                        val direction = if (delta > 0f) {
                            InputConfig.Assignment.Axis.Direction.POSITIVE
                        } else {
                            InputConfig.Assignment.Axis.Direction.NEGATIVE
                        }
                        viewModel.updateInputAssignedAxis(detectedAxis, direction)
                    } else {
                        viewModel.updateSlot2AxisAssignment(
                            axisCode = detectedAxis,
                            deviceId = event.deviceId,
                        )
                    }
                }
                return true
            }
        }

        return super.onGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && viewModel.inputUnderAssignment.value != null) {
            @SuppressLint("GestureBackNavigation")
            if (event.keyCode != KeyEvent.KEYCODE_BACK) {
                viewModel.updateInputAssignedKey(event.keyCode)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
