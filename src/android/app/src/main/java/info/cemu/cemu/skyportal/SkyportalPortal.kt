package info.cemu.cemu.skyportal

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import info.cemu.cemu.BuildConfig

object SkyportalPortal {
    private val PORTAL_ACTIVITY = ComponentName("app.skyportal", "app.skyportal.portal.PortalActivity")

    /**
     * Opens the launcher's portal screen on top of the game. It is started in this task and is
     * translucent so that this activity stays visible and keeps its emulation surface.
     */
    fun open(activity: Activity): Boolean {
        try {
            activity.startActivity(
                Intent().setComponent(PORTAL_ACTIVITY)
                    .putExtra("authority", "${BuildConfig.APPLICATION_ID}.skyportal")
            )
            return true
        } catch (_: ActivityNotFoundException) {
            return false
        } catch (_: SecurityException) {
            return false
        }
    }
}

/** Detects Select + R1. */
class PortalChord {
    private var isSelectDown = false
    private var isR1Down = false

    fun onKeyEvent(event: KeyEvent): Boolean {
        val isDown = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_SELECT -> isSelectDown = isDown
            KeyEvent.KEYCODE_BUTTON_R1 -> isR1Down = isDown
            else -> return false
        }
        return isDown && event.repeatCount == 0 && isSelectDown && isR1Down
    }

    /** Key releases go to the portal screen, so the buttons have to be released by hand. */
    fun releaseEvents(event: KeyEvent): List<KeyEvent> {
        isSelectDown = false
        isR1Down = false
        val time = SystemClock.uptimeMillis()
        return listOf(KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_R1).map {
            KeyEvent(
                time, time, KeyEvent.ACTION_UP, it, 0, 0, event.deviceId, 0, 0, event.source
            )
        }
    }
}
