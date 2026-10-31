package info.cemu.cemu.emulation.skyportal

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import androidx.core.net.toUri
import info.cemu.cemu.common.android.context.internalFolder
import info.cemu.cemu.common.android.inputdevice.listGameControllers
import info.cemu.cemu.common.customdrivers.DriverMetadata
import info.cemu.cemu.common.customdrivers.META_FILE_NAME
import info.cemu.cemu.common.customdrivers.SUPPORTED_SCHEMA_VERSION
import info.cemu.cemu.common.customdrivers.getCustomDriversDir
import info.cemu.cemu.common.io.decodeJsonFromFile
import info.cemu.cemu.common.io.unzip
import info.cemu.cemu.emulation.emulatedusbdevices.SkylanderKey
import info.cemu.cemu.emulation.emulatedusbdevices.findInstalledSkylanderFigures
import info.cemu.cemu.emulation.emulatedusbdevices.readSkylanderFigureFile
import info.cemu.cemu.emulation.emulatedusbdevices.readSkylanderKey
import info.cemu.cemu.nativeinterface.NativeEmulation
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
private const val DRIVER_MARKER = "skyportal-driver"
private const val DRIVER_FOLDER_PREFIX = "skyportal-"

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
            "getMappings" -> getMappings(context)
            "setMapping" -> setMapping(context, arg!!.toInt(), extras!!.getString("value")!!.toInt())
            "resetMappings" -> resetMappings(context)
            "getDriver" -> getDriver(context)
            "installDriver" -> installDriver(context, arg!!.toUri())
            "setDriverEnabled" -> setDriverEnabled(context, extras!!.getBoolean("enabled"))
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

        applyDefaultMapping(controller)
        marker.writeText(controller.descriptor)
    }

    private fun applyDefaultMapping(controller: InputDevice) {
        if (NativeInput.isControllerDisabled(0)) {
            NativeInput.setControllerType(0, NativeInput.EmulatedControllerType.VPAD)
            NativeInput.setDeviceControllerIndex(0)
        }
        NativeInput.clearControllerMapping(0, NativeInput.VPADButton.HOME)
        for ((mappingId, buttonId) in VPAD_MAPPING) {
            NativeInput.setControllerMapping(controller.descriptor, controller.name, 0, mappingId, buttonId)
        }
        NativeInput.saveInputs()
    }

    private fun getMappings(context: Context): Bundle {
        mapFirstController(context)
        return result(true).apply {
            NativeInput.getControllerMappings(0).forEach { (mappingId, input) -> putString(mappingId.toString(), input) }
        }
    }

    private fun setMapping(context: Context, mappingId: Int, buttonId: Int): Bundle {
        mapFirstController(context)
        val controller = listGameControllers().firstOrNull() ?: return result(false, "no_controller")
        NativeInput.setControllerMapping(controller.descriptor, controller.name, 0, mappingId, buttonId)
        NativeInput.saveInputs()
        return result(true)
    }

    private fun resetMappings(context: Context): Bundle {
        mapFirstController(context)
        val controller = listGameControllers().firstOrNull() ?: return result(false, "no_controller")
        applyDefaultMapping(controller)
        return result(true)
    }

    /** The driver installed by the launcher, which stays installed while it is disabled. */
    private fun installedDriver(context: Context): Pair<File, DriverMetadata>? {
        val marker = context.internalFolder().resolve(DRIVER_MARKER)
        if (!marker.isFile) {
            return null
        }
        val folder = File(marker.readText())
        val metadata = decodeJsonFromFile<DriverMetadata>(folder.resolve(META_FILE_NAME)) ?: return null
        return folder to metadata
    }

    private fun getDriver(context: Context) = result(true).apply {
        val driver = installedDriver(context)
        putBoolean("supported", NativeEmulation.supportsLoadingCustomDriver())
        putString("name", driver?.second?.name)
        putBoolean("enabled", driver != null && NativeSettings.getCustomDriverPath() == driver.first.path)
    }

    private fun installDriver(context: Context, uri: Uri): Bundle {
        val folder = getCustomDriversDir().toFile().resolve("$DRIVER_FOLDER_PREFIX${System.currentTimeMillis()}")
        try {
            folder.mkdirs()
            val input = context.contentResolver.openInputStream(uri) ?: return result(false, "Cannot open $uri")
            input.use { unzip(it, folder.toPath()) }

            val metadata = decodeJsonFromFile<DriverMetadata>(folder.resolve(META_FILE_NAME))
            if (metadata == null
                || metadata.schemaVersion != SUPPORTED_SCHEMA_VERSION
                || metadata.minApi > Build.VERSION.SDK_INT
                || !folder.resolve(metadata.libraryName).isFile
            ) {
                folder.deleteRecursively()
                return result(false, "invalid_driver")
            }

            val previous = installedDriver(context)
            context.internalFolder().resolve(DRIVER_MARKER).writeText(folder.path)
            NativeSettings.setCustomDriverPath(folder.path)
            NativeSettings.saveSettings()
            previous?.first?.deleteRecursively()
            return result(true)
        } catch (exception: Exception) {
            folder.deleteRecursively()
            return result(false, exception.message)
        }
    }

    /** Disabling keeps the driver installed, so that it can be enabled again without a download. */
    private fun setDriverEnabled(context: Context, enabled: Boolean): Bundle {
        val driver = installedDriver(context)
        if (enabled && driver == null) {
            return result(false, "no_driver")
        }
        NativeSettings.setCustomDriverPath(if (enabled) driver!!.first.path else null)
        NativeSettings.saveSettings()
        return result(true)
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
