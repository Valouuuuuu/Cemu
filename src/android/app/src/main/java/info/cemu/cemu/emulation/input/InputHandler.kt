package info.cemu.cemu.emulation.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import info.cemu.cemu.common.android.inputevent.isFromPhysicalController
import info.cemu.cemu.nativeinterface.NativeInput
import kotlin.math.max

private const val TRIGGER_PRESSED = 1f
private const val TRIGGER_RELEASED = 0f

object InputHandler {
    private var isL2Down = false
    private var isR2Down = false

    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!event.isFromPhysicalController()) {
            return false
        }

        val device = event.device
        val isDown = event.action == KeyEvent.ACTION_DOWN

        NativeInput.onControllerKey(
            device.descriptor,
            event.keyCode,
            isDown
        )

        // Some gamepads only report their triggers as buttons, and ZL and ZR are mapped to the axes
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2 -> {
                isL2Down = isDown
                onTriggerKey(device, MotionEvent.AXIS_LTRIGGER, isDown)
            }

            KeyEvent.KEYCODE_BUTTON_R2 -> {
                isR2Down = isDown
                onTriggerKey(device, MotionEvent.AXIS_RTRIGGER, isDown)
            }
        }

        return true
    }

    private fun onTriggerKey(device: InputDevice, axis: Int, isDown: Boolean) {
        NativeInput.onControllerAxis(device.descriptor, axis, if (isDown) TRIGGER_PRESSED else TRIGGER_RELEASED)
    }

    /** Other gamepads report their triggers as gas and brake, which Cemu does not read. */
    private fun triggerAxis(device: InputDevice, axis: Int) = when {
        axis == MotionEvent.AXIS_BRAKE && device.getMotionRange(MotionEvent.AXIS_LTRIGGER) == null -> MotionEvent.AXIS_LTRIGGER
        axis == MotionEvent.AXIS_GAS && device.getMotionRange(MotionEvent.AXIS_RTRIGGER) == null -> MotionEvent.AXIS_RTRIGGER
        else -> axis
    }

    fun onMotionEvent(event: MotionEvent): Boolean {
        if (!event.isFromPhysicalController()) {
            return false
        }

        val device = event.device
        val actionPointerIndex = event.actionIndex
        for (motionRange in device.motionRanges) {
            val axisValue = event.getAxisValue(motionRange.axis, actionPointerIndex)
            val axis = triggerAxis(device, motionRange.axis)
            val value = when {
                axis == MotionEvent.AXIS_LTRIGGER && isL2Down -> max(axisValue, TRIGGER_PRESSED)
                axis == MotionEvent.AXIS_RTRIGGER && isR2Down -> max(axisValue, TRIGGER_PRESSED)
                else -> axisValue
            }
            NativeInput.onControllerAxis(device.descriptor, axis, value)
        }

        return true
    }
}
