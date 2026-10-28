package info.cemu.cemu.emulation.emulatedusbdevices

import info.cemu.cemu.nativeinterface.NativeEmulatedUSBDevices
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

data class SkylanderKey(val id: Int, val variant: Int)

val NativeEmulatedUSBDevices.SkylanderFigure.key
    get() = SkylanderKey(id.toInt(), variant.toInt())

enum class SkylanderCategory {
    SPYROS_ADVENTURE,
    GIANTS,
    SWAP_FORCE,
    TRAP_TEAM,
    SUPERCHARGERS,
    IMAGINATORS,
    ITEMS_AND_ADVENTURES,
    OTHER,
}

// Based on the id ranges of the figure list in src/Cafe/OS/libs/nsyshid/Skylander.cpp
fun skylanderCategory(id: Int): SkylanderCategory = when (id) {
    // 400-449 are the Legendary re-releases of Spyro's Adventure characters (id + 400)
    in 0..99, in 400..449 -> SkylanderCategory.SPYROS_ADVENTURE
    in 100..199 -> SkylanderCategory.GIANTS
    // TODO: SWAP Force: let the user combine a top half (2000-2999) with a bottom half (1000-1999)
    //  in one step. For now each half is a separate figure that has to be placed on its own.
    in 1000..2999, in 3000..3099 -> SkylanderCategory.SWAP_FORCE
    // Traps, characters and minis
    in 210..229, in 450..599 -> SkylanderCategory.TRAP_TEAM
    // Vehicles, characters and racing trophies
    in 3220..3299, in 3400..3599 -> SkylanderCategory.SUPERCHARGERS
    in 600..699 -> SkylanderCategory.IMAGINATORS
    // Magic items and adventure packs
    in 200..209, in 230..399, in 3200..3219, in 3300..3399 -> SkylanderCategory.ITEMS_AND_ADVENTURES
    else -> SkylanderCategory.OTHER
}

fun groupSkylanderFigures(
    figures: Array<NativeEmulatedUSBDevices.SkylanderFigure>,
): Map<SkylanderCategory, List<NativeEmulatedUSBDevices.SkylanderFigure>> =
    figures.sortedBy { it.name.lowercase() }
        .groupBy { skylanderCategory(it.id.toInt()) }
        .toSortedMap()

// Layout of a .sky file, see SkylanderUSB::CreateSkylander and loadSkylandersFigure in NativeEmulatedUSBDevices.cpp
private const val SKY_BLOCK_SIZE = 0x10
private const val SKY_SECTOR_SIZE = 4 * SKY_BLOCK_SIZE
private const val SKY_FIGURE_SIZE = 0x40 * SKY_BLOCK_SIZE
private const val SKY_ID_OFFSET = 0x10
private const val SKY_VARIANT_OFFSET = 0x1C
private const val SKY_SECTOR_TRAILER_OFFSET = 3 * SKY_BLOCK_SIZE

data class SkylanderFigureFile(
    val figure: NativeEmulatedUSBDevices.InstalledFigure,
    val key: SkylanderKey,
    val hasProgress: Boolean,
    val lastModified: Long,
)

private fun ByteArray.readUShortLE(offset: Int) =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

fun readSkylanderKey(data: ByteArray) = SkylanderKey(
    id = data.readUShortLE(SKY_ID_OFFSET),
    variant = data.readUShortLE(SKY_VARIANT_OFFSET),
)

/**
 * A figure created by the emulator only has its two header blocks and the sector trailers set,
 * any other non-zero byte was written by a game (or comes from a real figure dump).
 */
fun hasSkylanderProgress(data: ByteArray) =
    (2 * SKY_BLOCK_SIZE until SKY_FIGURE_SIZE).any { offset ->
        offset % SKY_SECTOR_SIZE < SKY_SECTOR_TRAILER_OFFSET && data[offset] != 0.toByte()
    }

fun readSkylanderFigureFile(figure: NativeEmulatedUSBDevices.InstalledFigure): SkylanderFigureFile? {
    val file = File(figure.path)
    val data = ByteArray(SKY_FIGURE_SIZE)

    try {
        RandomAccessFile(file, "r").use { it.readFully(data) }
    } catch (_: IOException) {
        return null
    }

    return SkylanderFigureFile(
        figure = figure,
        key = readSkylanderKey(data),
        hasProgress = hasSkylanderProgress(data),
        lastModified = file.lastModified(),
    )
}

/**
 * Picks one installed figure file per (id, variant).
 * Older builds created a new file each time a figure was created, so there can be several files
 * for the same figure: prefer the ones with game progress, then the most recently written one.
 */
fun pickSkylanderFigureFiles(
    files: List<SkylanderFigureFile>,
): Map<SkylanderKey, NativeEmulatedUSBDevices.InstalledFigure> =
    files.groupBy { it.key }.mapValues { (_, candidates) ->
        candidates.maxWith(
            compareBy<SkylanderFigureFile> { it.hasProgress }.thenBy { it.lastModified }
        ).figure
    }

fun findInstalledSkylanderFigures(
    installedFigures: Array<NativeEmulatedUSBDevices.InstalledFigure>,
): Map<SkylanderKey, NativeEmulatedUSBDevices.InstalledFigure> =
    pickSkylanderFigureFiles(installedFigures.mapNotNull(::readSkylanderFigureFile))
