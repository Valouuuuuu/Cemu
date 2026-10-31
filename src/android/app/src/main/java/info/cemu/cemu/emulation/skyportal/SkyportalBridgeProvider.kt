package info.cemu.cemu.emulation.skyportal

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.core.net.toUri
import info.cemu.cemu.common.android.context.internalFolder
import info.cemu.cemu.common.android.inputdevice.listGameControllers
import info.cemu.cemu.emulation.emulatedusbdevices.SkylanderKey
import info.cemu.cemu.emulation.emulatedusbdevices.findInstalledSkylanderFigures
import info.cemu.cemu.emulation.emulatedusbdevices.readSkylanderFigureFile
import info.cemu.cemu.emulation.emulatedusbdevices.readSkylanderKey
import info.cemu.cemu.nativeinterface.NativeEmulatedUSBDevices
import info.cemu.cemu.nativeinterface.NativeGameTitles
import info.cemu.cemu.nativeinterface.NativeInput
import info.cemu.cemu.nativeinterface.NativeSettings
import java.io.File
import java.io.IOException

private const val SKYLANDERS_FOLDER = "emulatedUSBDevices/skylanders"
private const val KEYS_FILE = "keys.txt"
private const val CONTROLLER_MARKER = "skyportal-controller"
private const val SKY_FILE_SIZE = 1024

private class BridgeSetting(val get: () -> Int, val set: (Int) -> Unit)

private fun toggle(get: () -> Boolean, set: (Boolean) -> Unit) =
    BridgeSetting({ if (get()) 1 else 0 }, { set(it != 0) })

/** What the launcher's settings screen can change. It holds the labels: these are only the values. */
private val SETTINGS = mapOf(
    "asyncShaderCompile" to toggle(NativeSettings::getAsyncShaderCompile, NativeSettings::setAsyncShaderCompile),
    "vsync" to BridgeSetting(NativeSettings::getVsyncMode, NativeSettings::setVsyncMode),
    "accurateBarriers" to toggle(NativeSettings::getAccurateBarriers, NativeSettings::setAccurateBarriers),
    "fullscreenScaling" to BridgeSetting(NativeSettings::getFullscreenScaling, NativeSettings::setFullscreenScaling),
    "upscalingFilter" to BridgeSetting(NativeSettings::getUpscalingFilter, NativeSettings::setUpscalingFilter),
    "downscalingFilter" to BridgeSetting(NativeSettings::getDownscalingFilter, NativeSettings::setDownscalingFilter),
    "audioLatency" to BridgeSetting(NativeSettings::getAudioLatency, NativeSettings::setAudioLatency),
    "audioTv" to toggle({ NativeSettings.getAudioDeviceEnabled(true) }, { NativeSettings.setAudioDeviceEnabled(it, true) }),
    "audioTvChannels" to BridgeSetting({ NativeSettings.getAudioDeviceChannels(true) }, { NativeSettings.setAudioDeviceChannels(it, true) }),
    "audioTvVolume" to BridgeSetting({ NativeSettings.getAudioDeviceVolume(true) }, { NativeSettings.setAudioDeviceVolume(it, true) }),
    "audioPad" to toggle({ NativeSettings.getAudioDeviceEnabled(false) }, { NativeSettings.setAudioDeviceEnabled(it, false) }),
    "audioPadVolume" to BridgeSetting({ NativeSettings.getAudioDeviceVolume(false) }, { NativeSettings.setAudioDeviceVolume(it, false) }),
    "overlayPosition" to BridgeSetting(NativeSettings::getOverlayPosition, NativeSettings::setOverlayPosition),
    "overlayScale" to BridgeSetting(NativeSettings::getOverlayTextScalePercentage, NativeSettings::setOverlayTextScalePercentage),
    "overlayFps" to toggle(NativeSettings::isOverlayFPSEnabled, NativeSettings::setOverlayFPSEnabled),
    "overlayDrawCalls" to toggle(NativeSettings::isOverlayDrawCallsPerFrameEnabled, NativeSettings::setOverlayDrawCallsPerFrameEnabled),
    "overlayCpu" to toggle(NativeSettings::isOverlayCPUUsageEnabled, NativeSettings::setOverlayCPUUsageEnabled),
    "overlayRam" to toggle(NativeSettings::isOverlayRAMUsageEnabled, NativeSettings::setOverlayRAMUsageEnabled),
    "overlayDebug" to toggle(NativeSettings::isOverlayDebugEnabled, NativeSettings::setOverlayDebugEnabled),
    "notificationsPosition" to BridgeSetting(NativeSettings::getNotificationsPosition, NativeSettings::setNotificationsPosition),
    "notificationsScale" to BridgeSetting(NativeSettings::getNotificationsTextScalePercentage, NativeSettings::setNotificationsTextScalePercentage),
    "notifyControllerProfiles" to toggle(NativeSettings::isNotificationControllerProfilesEnabled, NativeSettings::setNotificationControllerProfilesEnabled),
    "notifyShaderCompiler" to toggle(NativeSettings::isNotificationShaderCompilerEnabled, NativeSettings::setNotificationShaderCompilerEnabled),
    "notifyFriendList" to toggle(NativeSettings::isNotificationFriendListEnabled, NativeSettings::setNotificationFriendListEnabled),
    "consoleLanguage" to BridgeSetting(NativeSettings::getConsoleLanguage, NativeSettings::setConsoleLanguage),
)

/**
 * Lets the Skyportal launcher set up this emulator and control its Skylanders portal.
 * Protected by a signature permission: only apps signed with the same key can call it.
 */
class SkyportalBridgeProvider : ContentProvider() {
    private val portal = arrayOfNulls<SkylanderKey>(NativeEmulatedUSBDevices.MAX_SKYLANDERS)

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = synchronized(portal) {
        val context = context ?: return null
        when (method) {
            "prepare" -> prepare(context)
            "importKeys" -> importKeys(context, arg!!.toUri())
            "importFigure" -> importFigure(context, arg!!.toUri(), extras!!.getString("name")!!)
            "getSettings" -> getSettings()
            "setSetting" -> setSetting(arg!!, extras!!.getInt("value"))
            "getSlots" -> getSlots()
            "getSavedFigures" -> getSavedFigures()
            "toggle" -> toggle(SkylanderKey(extras!!.getInt("id"), extras.getInt("variant")))
            else -> throw IllegalArgumentException("Unknown method $method")
        }
    }

    private fun result(success: Boolean, error: String? = null) = Bundle().apply {
        putBoolean("success", success)
        putString("error", error)
    }

    private fun prepare(context: Context): Bundle {
        NativeSettings.setEmulateSkylanderPortalEnabled(true)
        NativeSettings.saveSettings()
        mapFirstController(context)
        return result(true)
    }

    /** Maps the first gamepad once, so that the game can be played without going through the settings. */
    private fun mapFirstController(context: Context) {
        val controller = listGameControllers().firstOrNull() ?: return
        val marker = context.internalFolder().resolve(CONTROLLER_MARKER)
        if (marker.isFile && marker.readText() == controller.descriptor) {
            return
        }

        if (NativeInput.isControllerDisabled(0)) {
            NativeInput.setControllerType(0, NativeInput.EmulatedControllerType.VPAD)
            NativeInput.setDeviceControllerIndex(0)
        }
        for ((mappingId, buttonId) in VPAD_MAPPING) {
            NativeInput.setControllerMapping(controller.descriptor, controller.name, 0, mappingId, buttonId)
        }
        NativeInput.saveInputs()
        marker.writeText(controller.descriptor)
    }

    private fun importKeys(context: Context, uri: Uri): Bundle {
        val target = context.internalFolder().resolve(KEYS_FILE)
        val temporary = File(target.path + ".tmp")
        return copyFrom(context, uri, temporary) {
            temporary.renameTo(target).also { NativeGameTitles.reloadDiscKeys() }
        }
    }

    private fun getSettings() = result(true).apply {
        SETTINGS.forEach { (id, setting) -> putInt(id, setting.get()) }
    }

    private fun setSetting(id: String, value: Int): Bundle {
        val setting = SETTINGS[id] ?: return result(false, "Unknown setting $id")
        setting.set(value)
        NativeSettings.saveSettings()
        return result(true)
    }

    /** Never replaces a figure that already exists here: it may hold game progress. */
    private fun importFigure(context: Context, uri: Uri, name: String): Bundle {
        val folder = context.internalFolder().resolve(SKYLANDERS_FOLDER)
        if (!folder.isDirectory && !folder.mkdirs()) {
            return result(false, "Cannot create $folder")
        }
        val temporary = folder.resolve("import.tmp")
        return copyFrom(context, uri, temporary) {
            val data = temporary.readBytes()
            val isValid = data.size == SKY_FILE_SIZE
            if (!isValid || readSkylanderKey(data) in installedFigures()) {
                temporary.delete()
                isValid
            } else {
                temporary.renameTo(uniqueFile(folder, name))
            }
        }
    }

    private fun uniqueFile(folder: File, name: String): File {
        val cleanName = name.replace(Regex("[^\\p{L}\\p{N} ()'._-]"), "")
        return generateSequence(0) { it + 1 }
            .map { folder.resolve(if (it == 0) "$cleanName.sky" else "$cleanName ($it).sky") }
            .first { !it.exists() }
    }

    private fun copyFrom(context: Context, uri: Uri, target: File, onCopied: () -> Boolean): Bundle {
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return result(false, "Cannot open $uri")
            input.use { source -> target.outputStream().use { source.copyTo(it) } }
            return result(onCopied())
        } catch (exception: IOException) {
            target.delete()
            return result(false, exception.message)
        } catch (exception: SecurityException) {
            target.delete()
            return result(false, exception.message)
        }
    }

    private fun installedFigures() =
        findInstalledSkylanderFigures(NativeEmulatedUSBDevices.getInstalledSkylanderFigures())

    private fun getSavedFigures() = result(true).apply {
        val saved = NativeEmulatedUSBDevices.getInstalledSkylanderFigures()
            .mapNotNull(::readSkylanderFigureFile)
            .filter { it.hasProgress }
            .map { it.key }
            .distinct()
        putIntArray("ids", saved.map { it.id }.toIntArray())
        putIntArray("variants", saved.map { it.variant }.toIntArray())
    }

    private fun isSlotUsed(slot: Int) = NativeEmulatedUSBDevices.getSkylandersFigureSlot(slot) != null

    private fun getSlots(): Bundle {
        for (slot in portal.indices) {
            if (!isSlotUsed(slot)) {
                portal[slot] = null
            }
        }
        return result(true).apply {
            putIntArray("ids", portal.map { it?.id ?: -1 }.toIntArray())
            putIntArray("variants", portal.map { it?.variant ?: -1 }.toIntArray())
        }
    }

    private fun toggle(key: SkylanderKey): Bundle {
        getSlots()

        val loadedSlot = portal.indexOf(key)
        if (loadedSlot != -1) {
            NativeEmulatedUSBDevices.clearSkylandersFigure(loadedSlot)
            portal[loadedSlot] = null
            return getSlots()
        }

        val freeSlot = portal.indices.firstOrNull { !isSlotUsed(it) }
            ?: return result(false, "portal_full")

        val figure = installedFigures()[key] ?: run {
            NativeEmulatedUSBDevices.createSkylanderFigure(key.id, key.variant)
            installedFigures()[key]
        } ?: return result(false, "create_failed")

        if (!NativeEmulatedUSBDevices.loadSkylandersFigure(figure.path, freeSlot)) {
            return result(false, "load_failed")
        }
        portal[freeSlot] = key
        return getSlots()
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0
}

private val VPAD_MAPPING = with(NativeInput.VPADButton) {
    mapOf(
        A to KeyEvent.KEYCODE_BUTTON_A,
        B to KeyEvent.KEYCODE_BUTTON_B,
        X to KeyEvent.KEYCODE_BUTTON_X,
        Y to KeyEvent.KEYCODE_BUTTON_Y,
        L to KeyEvent.KEYCODE_BUTTON_L1,
        R to KeyEvent.KEYCODE_BUTTON_R1,
        ZL to NativeInput.Axis.TRIGGER_X_POS,
        ZR to NativeInput.Axis.TRIGGER_Y_POS,
        PLUS to KeyEvent.KEYCODE_BUTTON_START,
        MINUS to KeyEvent.KEYCODE_BUTTON_SELECT,
        UP to NativeInput.Axis.DPAD_UP,
        DOWN to NativeInput.Axis.DPAD_DOWN,
        LEFT to NativeInput.Axis.DPAD_LEFT,
        RIGHT to NativeInput.Axis.DPAD_RIGHT,
        STICKL to KeyEvent.KEYCODE_BUTTON_THUMBL,
        STICKR to KeyEvent.KEYCODE_BUTTON_THUMBR,
        STICKL_UP to NativeInput.Axis.Y_NEG,
        STICKL_DOWN to NativeInput.Axis.Y_POS,
        STICKL_LEFT to NativeInput.Axis.X_NEG,
        STICKL_RIGHT to NativeInput.Axis.X_POS,
        STICKR_UP to NativeInput.Axis.ROTATION_Y_NEG,
        STICKR_DOWN to NativeInput.Axis.ROTATION_Y_POS,
        STICKR_LEFT to NativeInput.Axis.ROTATION_X_NEG,
        STICKR_RIGHT to NativeInput.Axis.ROTATION_X_POS,
    )
}
