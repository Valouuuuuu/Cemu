package info.cemu.cemu.graphicpacks.skyportal

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import info.cemu.cemu.common.android.context.AppInitialization
import info.cemu.cemu.graphicpacks.GraphicPacksDownloadStatus
import info.cemu.cemu.graphicpacks.GraphicPacksDownloader
import info.cemu.cemu.nativeinterface.NativeGraphicPacks
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking

private const val GAME_FILTER = "skylanders"

private class ExtraPreset(val pack: String, val name: String, val rules: String, val before: Regex)

/** Choices that the community packs of Trap Team do not have, added to their downloaded rules. */
private val EXTRA_PRESETS = listOf(
    // It has 40 and 60 FPS, and its vsync frequency follows its target
    ExtraPreset(
        pack = "Skylanders Trap Team/Mods/FPS",
        name = "45 FPS",
        rules = "[Preset]\nname = 45 FPS\ncategory = FPS Limit\n\$targetFPS:int = 45\n\n",
        before = Regex("""\[Preset\]\s+name\s*=\s*40 FPS"""),
    ),
    // It has 20 and 30 FPS, and its vsync frequency follows its target
    ExtraPreset(
        pack = "Skylanders Trap Team/Mods/FPS",
        name = "25 FPS",
        rules = "[Preset]\nname = 25 FPS\ncategory = FPS Limit\n\$targetFPS:int = 25\n\n",
        before = Regex("""\[Preset\]\s+name\s*=\s*20 FPS"""),
    ),
    ExtraPreset(
        pack = "Skylanders Trap Team/Mods/FPS",
        name = "24 FPS",
        rules = "[Preset]\nname = 24 FPS\ncategory = FPS Limit\n\$targetFPS:int = 24\n\n",
        before = Regex("""\[Preset\]\s+name\s*=\s*20 FPS"""),
    ),
    // Each is put before 25 FPS, so that they stay in order whichever were added before
    ExtraPreset(
        pack = "Skylanders Trap Team/Mods/FPS",
        name = "28 FPS",
        rules = "[Preset]\nname = 28 FPS\ncategory = FPS Limit\n\$targetFPS:int = 28\n\n",
        before = Regex("""\[Preset\]\s+name\s*=\s*25 FPS"""),
    ),
    ExtraPreset(
        pack = "Skylanders Trap Team/Mods/FPS",
        name = "27 FPS",
        rules = "[Preset]\nname = 27 FPS\ncategory = FPS Limit\n\$targetFPS:int = 27\n\n",
        before = Regex("""\[Preset\]\s+name\s*=\s*25 FPS"""),
    ),
    ExtraPreset(
        pack = "Skylanders Trap Team/Mods/FPS",
        name = "26 FPS",
        rules = "[Preset]\nname = 26 FPS\ncategory = FPS Limit\n\$targetFPS:int = 26\n\n",
        before = Regex("""\[Preset\]\s+name\s*=\s*25 FPS"""),
    ),
    // It has 50% and 100%, and this value only multiplies the size of the shadows' texture
    ExtraPreset(
        pack = "Skylanders Trap Team/Graphics",
        name = "Low-Medium (75%)",
        rules = "[Preset]\ncategory = Shadow Quality\nname = Low-Medium (75%)\n\$shadowRes = 0.75\n\n",
        before = Regex("""\[Preset\]\s+category\s*=\s*Shadow Quality\s+name\s*=\s*Medium"""),
    ),
)

/**
 * Lets the Skyportal launcher list, download and select the graphic packs for Skylanders.
 * Protected by a signature permission: only apps signed with the same key can call it.
 */
class SkyportalModsProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val context = context ?: return null
        AppInitialization.await()
        return when (method) {
            "getMods" -> getMods(context)
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

    /** @return whether a pack was changed */
    private fun addExtraPresets(context: Context): Boolean {
        val packs = context.getExternalFilesDir(null)?.resolve("graphicPacks/downloadedGraphicPacks")
            ?: return false
        val changed = packs.walkTopDown()
            .filter { it.name == "rules.txt" }
            .filter { file ->
                val rules = file.readText()
                val updated = EXTRA_PRESETS.fold(rules) { current, preset ->
                    val anchor = preset.before.find(current)
                    if (preset.pack in current && preset.name !in current && anchor != null) {
                        current.replaceRange(anchor.range.first, anchor.range.first, preset.rules)
                    } else {
                        current
                    }
                }
                val isChanged = updated != rules
                if (isChanged) {
                    file.writeText(updated)
                }
                isChanged
            }
        return changed.toList().isNotEmpty()
    }

    private fun getMods(context: Context) = result(true).apply {
        if (addExtraPresets(context)) {
            NativeGraphicPacks.refreshGraphicPacks()
        }
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
