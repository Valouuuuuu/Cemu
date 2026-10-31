package info.cemu.cemu.graphicpacks.skyportal

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import info.cemu.cemu.graphicpacks.GraphicPacksDownloadStatus
import info.cemu.cemu.graphicpacks.GraphicPacksDownloader
import info.cemu.cemu.nativeinterface.NativeGraphicPacks
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking

private const val GAME_FILTER = "skylanders"

/**
 * Lets the Skyportal launcher list, download and select the graphic packs for Skylanders.
 * Protected by a signature permission: only apps signed with the same key can call it.
 */
class SkyportalModsProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val context = context ?: return null
        return when (method) {
            "getMods" -> getMods()
            "setModActive" -> setModActive(extras!!.getLong("id"), extras.getBoolean("active"))
            "setModPreset" -> setModPreset(extras!!.getLong("id"), extras.getString("category"), arg!!)
            "downloadMods" -> downloadMods(context)
            else -> throw IllegalArgumentException("Unknown method $method")
        }
    }

    private fun result(success: Boolean, error: String? = null) = Bundle().apply {
        putBoolean("success", success)
        putString("error", error)
    }

    private fun getMods() = result(true).apply {
        val mods = NativeGraphicPacks.getGraphicPackBasicInfos()
            .filter { it.virtualPath.contains(GAME_FILTER, ignoreCase = true) }
            .mapNotNull { info -> NativeGraphicPacks.getGraphicPack(info.id)?.let { toBundle(info, it) } }
        putParcelableArrayList("mods", ArrayList(mods))
    }

    private fun toBundle(
        info: NativeGraphicPacks.GraphicPackBasicInfo,
        pack: NativeGraphicPacks.GraphicPack,
    ) = Bundle().apply {
        putLong("id", pack.id)
        putString("path", info.virtualPath)
        putString("name", pack.name)
        putString("description", pack.description)
        putBoolean("active", pack.isActive())
        putParcelableArrayList(
            "presets",
            ArrayList(pack.presets.map { preset ->
                Bundle().apply {
                    putString("category", preset.category)
                    putStringArray("choices", preset.presets)
                    putString("active", preset.activePreset)
                }
            }),
        )
    }

    private fun setModActive(id: Long, active: Boolean): Bundle {
        NativeGraphicPacks.setGraphicPackActive(id, active)
        return result(true)
    }

    private fun setModPreset(id: Long, category: String?, preset: String): Bundle {
        NativeGraphicPacks.setGraphicPackActivePreset(id, category, preset)
        return result(true)
    }

    private fun downloadMods(context: Context): Bundle {
        val folder = context.getExternalFilesDir(null) ?: return result(false, "storage")
        return try {
            val status = runBlocking { GraphicPacksDownloader().download(folder).last() }
            when (status) {
                GraphicPacksDownloadStatus.FINISHED_DOWNLOADING,
                GraphicPacksDownloadStatus.NO_UPDATES_AVAILABLE -> result(true)

                else -> result(false, status.name)
            }
        } catch (exception: Exception) {
            result(false, exception.message)
        }
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
