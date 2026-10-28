package info.cemu.cemu.tests

import info.cemu.cemu.emulation.emulatedusbdevices.SkylanderCategory
import info.cemu.cemu.emulation.emulatedusbdevices.SkylanderFigureFile
import info.cemu.cemu.emulation.emulatedusbdevices.SkylanderKey
import info.cemu.cemu.emulation.emulatedusbdevices.hasSkylanderProgress
import info.cemu.cemu.emulation.emulatedusbdevices.pickSkylanderFigureFiles
import info.cemu.cemu.emulation.emulatedusbdevices.readSkylanderFigureFile
import info.cemu.cemu.emulation.emulatedusbdevices.readSkylanderKey
import info.cemu.cemu.emulation.emulatedusbdevices.skylanderCategory
import info.cemu.cemu.nativeinterface.NativeEmulatedUSBDevices.InstalledFigure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkylanderCatalogTests {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // Same layout as SkylanderUSB::CreateSkylander
    private fun createdFigure(id: Int, variant: Int) = ByteArray(1024).apply {
        this[0] = 0x12
        this[1] = 0x34
        this[2] = 0x56
        this[3] = 0x78
        this[4] = (0x12 xor 0x34 xor 0x56 xor 0x78).toByte()
        this[5] = 0x81.toByte()
        this[6] = 0x01
        this[7] = 0x0F
        this[0x10] = id.toByte()
        this[0x11] = (id shr 8).toByte()
        this[0x1C] = variant.toByte()
        this[0x1D] = (variant shr 8).toByte()
        this[0x1E] = 0x55
        this[0x1F] = 0x66
        for (sector in 0 until 0x10) {
            this[sector * 0x40 + 0x36] = 0x0F
            this[sector * 0x40 + 0x37] = 0x0F
            this[sector * 0x40 + 0x38] = 0x0F
            this[sector * 0x40 + 0x39] = 0x69
        }
    }

    private fun figureFile(name: String, hasProgress: Boolean, lastModified: Long) =
        SkylanderFigureFile(
            figure = InstalledFigure(name, "/figures/$name.sky"),
            key = SkylanderKey(16, 0x1801),
            hasProgress = hasProgress,
            lastModified = lastModified,
        )

    @Test
    fun `reads id and variant from the figure header`() {
        assertEquals(SkylanderKey(3203, 0x1801), readSkylanderKey(createdFigure(3203, 0x1801)))
    }

    @Test
    fun `newly created figure has no progress`() {
        assertFalse(hasSkylanderProgress(createdFigure(16, 0)))
    }

    @Test
    fun `figure with game data has progress`() {
        val data = createdFigure(16, 0).apply { this[8 * 0x10 + 3] = 0x42 }

        assertTrue(hasSkylanderProgress(data))
    }

    @Test
    fun `sector trailer changes are not progress`() {
        val data = createdFigure(16, 0).apply { this[5 * 0x40 + 0x30] = 0x42 }

        assertFalse(hasSkylanderProgress(data))
    }

    @Test
    fun `prefers the figure file with progress over a newer empty one`() {
        val played = figureFile("Spyro", hasProgress = true, lastModified = 100)
        val duplicate = figureFile("Spyro (1)", hasProgress = false, lastModified = 200)

        assertEquals(
            played.figure,
            pickSkylanderFigureFiles(listOf(duplicate, played))[SkylanderKey(16, 0x1801)],
        )
    }

    @Test
    fun `prefers the most recently written figure file`() {
        val older = figureFile("Spyro", hasProgress = true, lastModified = 100)
        val newer = figureFile("Spyro (1)", hasProgress = true, lastModified = 200)

        assertEquals(
            newer.figure,
            pickSkylanderFigureFiles(listOf(older, newer))[SkylanderKey(16, 0x1801)],
        )
    }

    @Test
    fun `reads an installed figure file`() {
        val file = temporaryFolder.newFile("Gill Grunt.sky").apply {
            writeBytes(createdFigure(14, 0))
        }

        val figureFile = readSkylanderFigureFile(InstalledFigure("Gill Grunt", file.path))

        assertEquals(SkylanderKey(14, 0), figureFile?.key)
        assertEquals(false, figureFile?.hasProgress)
    }

    @Test
    fun `ignores files that are too small to be figures`() {
        val file = temporaryFolder.newFile("broken.sky").apply { writeBytes(ByteArray(10)) }

        assertNull(readSkylanderFigureFile(InstalledFigure("broken", file.path)))
    }

    @Test
    fun `categorizes figures by id range`() {
        assertEquals(SkylanderCategory.SPYROS_ADVENTURE, skylanderCategory(16))
        assertEquals(SkylanderCategory.SPYROS_ADVENTURE, skylanderCategory(416))
        assertEquals(SkylanderCategory.GIANTS, skylanderCategory(112))
        assertEquals(SkylanderCategory.SWAP_FORCE, skylanderCategory(1004))
        assertEquals(SkylanderCategory.SWAP_FORCE, skylanderCategory(2004))
        assertEquals(SkylanderCategory.SWAP_FORCE, skylanderCategory(3000))
        assertEquals(SkylanderCategory.TRAP_TEAM, skylanderCategory(213))
        assertEquals(SkylanderCategory.TRAP_TEAM, skylanderCategory(450))
        assertEquals(SkylanderCategory.TRAP_TEAM, skylanderCategory(502))
        assertEquals(SkylanderCategory.SUPERCHARGERS, skylanderCategory(3220))
        assertEquals(SkylanderCategory.SUPERCHARGERS, skylanderCategory(3400))
        assertEquals(SkylanderCategory.SUPERCHARGERS, skylanderCategory(3503))
        assertEquals(SkylanderCategory.IMAGINATORS, skylanderCategory(630))
        assertEquals(SkylanderCategory.ITEMS_AND_ADVENTURES, skylanderCategory(208))
        assertEquals(SkylanderCategory.ITEMS_AND_ADVENTURES, skylanderCategory(230))
        assertEquals(SkylanderCategory.ITEMS_AND_ADVENTURES, skylanderCategory(305))
        assertEquals(SkylanderCategory.ITEMS_AND_ADVENTURES, skylanderCategory(3203))
        assertEquals(SkylanderCategory.ITEMS_AND_ADVENTURES, skylanderCategory(3300))
        assertEquals(SkylanderCategory.OTHER, skylanderCategory(9999))
    }
}
