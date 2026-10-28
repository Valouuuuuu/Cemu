package info.cemu.cemu.emulation.emulatedusbdevices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import info.cemu.cemu.common.coroutines.RefreshableStateFlow
import info.cemu.cemu.nativeinterface.NativeEmulatedUSBDevices
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

sealed class UsbDeviceEvent {
    object CreateFailed : UsbDeviceEvent()
    object LoadFailed : UsbDeviceEvent()
    object PortalFull : UsbDeviceEvent()
}

class EmulatedUSBDevicesViewModel : ViewModel() {
    val skylanderFigures = NativeEmulatedUSBDevices.getSkylanderFigures()
    val dimensionsMiniFigures = NativeEmulatedUSBDevices.getDimensionsMiniFigures()
    val infinityFigures = NativeEmulatedUSBDevices.getInfinityFigures()

    private val _events = MutableSharedFlow<UsbDeviceEvent>()
    val events = _events.asSharedFlow()

    private fun emitEvent(usbDeviceEvent: UsbDeviceEvent) {
        viewModelScope.launch { _events.emit(usbDeviceEvent) }
    }

    private fun getFigureSlots(maxSlots: Int, slotGetter: (Int) -> String?) =
        Array(maxSlots) { slotGetter(it) }

    val skylanderSlots = RefreshableStateFlow {
        getFigureSlots(
            NativeEmulatedUSBDevices.MAX_SKYLANDERS,
            NativeEmulatedUSBDevices::getSkylandersFigureSlot
        )
    }

    val infinitySlots = RefreshableStateFlow {
        getFigureSlots(
            NativeEmulatedUSBDevices.MAX_INFINITY_SLOTS,
            NativeEmulatedUSBDevices::getInfinityFigureSlot
        )
    }

    val dimensionsSlots = RefreshableStateFlow {
        getFigureSlots(
            NativeEmulatedUSBDevices.MAX_DIMENSIONS_SLOTS,
            NativeEmulatedUSBDevices::getDimensionsFigureSlot
        )
    }

    fun clearDimensionsFigure(pad: Int, index: Int) {
        NativeEmulatedUSBDevices.clearDimensionsFigure(pad, index)
        dimensionsSlots.refresh()
    }

    fun clearInfinityFigure(slot: Int) {
        NativeEmulatedUSBDevices.clearInfinityFigure(slot)
        infinitySlots.refresh()
    }

    fun clearSkylandersFigure(slot: Int) {
        NativeEmulatedUSBDevices.clearSkylandersFigure(slot)
        skylanderSlots.refresh()
    }

    val installedSkylanderFigures =
        RefreshableStateFlow(NativeEmulatedUSBDevices::getInstalledSkylanderFigures)

    val installedSkylanderFiguresByKey = RefreshableStateFlow {
        findInstalledSkylanderFigures(installedSkylanderFigures.state.value)
    }

    private fun refreshInstalledSkylanderFigures() {
        installedSkylanderFigures.refresh()
        installedSkylanderFiguresByKey.refresh()
    }

    val installedDimensionMiniFigures =
        RefreshableStateFlow(NativeEmulatedUSBDevices::getInstalledDimensionsMiniFigures)

    val installedInfinityFigures =
        RefreshableStateFlow(NativeEmulatedUSBDevices::getInstalledInfinityFigures)

    private fun deleteFigure(
        installedFigure: NativeEmulatedUSBDevices.InstalledFigure, afterDelete: () -> Unit
    ) {
        NativeEmulatedUSBDevices.deleteFigure(installedFigure)
        afterDelete()
    }

    fun deleteInstalledSkylanderFigure(installedFigure: NativeEmulatedUSBDevices.InstalledFigure) =
        deleteFigure(installedFigure, ::refreshInstalledSkylanderFigures)

    fun deleteInstalledDimensionsFigure(installedFigure: NativeEmulatedUSBDevices.InstalledFigure) =
        deleteFigure(installedFigure, installedDimensionMiniFigures::refresh)

    fun deleteInstalledInfinityFigure(installedFigure: NativeEmulatedUSBDevices.InstalledFigure) =
        deleteFigure(installedFigure, installedInfinityFigures::refresh)

    fun createSkylanderFigure(id: Int, variant: Int) {
        if (!NativeEmulatedUSBDevices.createSkylanderFigure(id, variant)) {
            emitEvent(UsbDeviceEvent.CreateFailed)
            return
        }
        refreshInstalledSkylanderFigures()
    }

    /**
     * Removes the figure from the portal if it is on it, otherwise places it in the first free slot.
     * The figure file is reused when one already exists for the same (id, variant) so that the
     * progress is kept, a new one is only created the first time.
     */
    fun toggleSkylanderFigure(figure: NativeEmulatedUSBDevices.SkylanderFigure) {
        val slots = skylanderSlots.state.value

        // Slot names come from SkylanderUSB::FindSkylander, which uses the same list as getSkylanderFigures
        val loadedSlot = slots.indexOf(figure.name)
        if (loadedSlot != -1) {
            clearSkylandersFigure(loadedSlot)
            return
        }

        val freeSlot = slots.indexOf(null)
        if (freeSlot == -1) {
            emitEvent(UsbDeviceEvent.PortalFull)
            return
        }

        val installedFigure = installedSkylanderFiguresByKey.state.value[figure.key]
            ?: createSkylanderFigureFile(figure)
            ?: return

        loadSkylanderFigure(installedFigure, freeSlot)
    }

    private fun createSkylanderFigureFile(
        figure: NativeEmulatedUSBDevices.SkylanderFigure,
    ): NativeEmulatedUSBDevices.InstalledFigure? {
        if (!NativeEmulatedUSBDevices.createSkylanderFigure(figure.id.toInt(), figure.variant.toInt())) {
            emitEvent(UsbDeviceEvent.CreateFailed)
            return null
        }
        refreshInstalledSkylanderFigures()

        val installedFigure = installedSkylanderFiguresByKey.state.value[figure.key]
        if (installedFigure == null) {
            emitEvent(UsbDeviceEvent.CreateFailed)
        }
        return installedFigure
    }

    fun createDimensionsMiniFigure(number: Long) {
        if (!NativeEmulatedUSBDevices.createDimensionsFigure(number)) {
            emitEvent(UsbDeviceEvent.CreateFailed)
            return
        }
        installedDimensionMiniFigures.refresh()
    }

    fun createInfinityFigure(number: Long) {
        if (!NativeEmulatedUSBDevices.createInfinityFigure(number)) {
            emitEvent(UsbDeviceEvent.CreateFailed)
            return
        }
        installedInfinityFigures.refresh()
    }

    fun loadSkylanderFigure(figure: NativeEmulatedUSBDevices.InstalledFigure, slot: Int) {
        if (!NativeEmulatedUSBDevices.loadSkylandersFigure(figure.path, slot)) {
            emitEvent(UsbDeviceEvent.LoadFailed)
            return
        }
        skylanderSlots.refresh()
    }

    fun loadDimensionsFigure(
        figure: NativeEmulatedUSBDevices.InstalledFigure, pad: Int, index: Int
    ) {
        if (!NativeEmulatedUSBDevices.loadDimensionsFigure(figure.path, pad, index)) {
            emitEvent(UsbDeviceEvent.LoadFailed)
            return
        }
        dimensionsSlots.refresh()
    }

    fun loadInfinityFigure(figure: NativeEmulatedUSBDevices.InstalledFigure, slot: Int) {
        if (!NativeEmulatedUSBDevices.loadInfinityFigure(figure.path, slot)) {
            emitEvent(UsbDeviceEvent.LoadFailed)
            return
        }
        infinitySlots.refresh()
    }

    fun tempRemoveDimensionsFigure(index: Int) =
        NativeEmulatedUSBDevices.tempRemoveDimensionsFigure(index)

    fun cancelRemoveDimensionsFigure(index: Int) =
        NativeEmulatedUSBDevices.cancelRemoveDimensionsFigure(index)

    fun moveDimensionsFigure(pad: Int, index: Int, oldPad: Int, oldIndex: Int) {
        NativeEmulatedUSBDevices.moveDimensionsFigure(pad, index, oldPad, oldIndex)
        dimensionsSlots.refresh()
    }
}