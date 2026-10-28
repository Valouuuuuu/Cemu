package info.cemu.cemu.emulation.emulatedusbdevices

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PlatformImeOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import info.cemu.cemu.R
import info.cemu.cemu.common.ui.localization.tr
import info.cemu.cemu.nativeinterface.NativeEmulatedUSBDevices

private val FocusBorderWidth = 3.dp

/**
 * Lists every Skylander, tapping one places it on the portal (creating its figure file the first
 * time) and tapping it again removes it. Usable with a gamepad: D-pad to move, A to place/remove,
 * L1/R1 to change category, B to close.
 */
@Composable
fun SkylandersPortalGridTab(
    skylanderFigures: Array<NativeEmulatedUSBDevices.SkylanderFigure>,
    skylanderSlots: Array<String?>,
    installedFigureKeys: Set<SkylanderKey>,
    onToggleFigure: (NativeEmulatedUSBDevices.SkylanderFigure) -> Unit,
    onClear: (Int) -> Unit,
    onShowAdvanced: () -> Unit,
    // TODO: V2: show portraits read on demand from the user's own copy of the game.
    //  They belong to Activision and must never be bundled in this repository.
    portraitFor: (NativeEmulatedUSBDevices.SkylanderFigure) -> ImageBitmap? = { null },
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf<SkylanderCategory?>(null) }

    val figuresByCategory = remember(skylanderFigures) { groupSkylanderFigures(skylanderFigures) }
    val categories = remember(figuresByCategory) { listOf(null) + figuresByCategory.keys }
    val visibleFigures = remember(figuresByCategory, query, selectedCategory) {
        figuresByCategory
            .filterKeys { selectedCategory == null || it == selectedCategory }
            .mapValues { (_, figures) ->
                figures.filter { it.name.contains(query.trim(), ignoreCase = true) }
            }
            .filterValues { it.isNotEmpty() }
    }
    val figuresOnPortal = remember(skylanderSlots) { skylanderSlots.filterNotNull().toSet() }
    val initialFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        // Wait for the grid to lay out its first items so that a gamepad can be used right away
        withFrameNanos { }
        try {
            initialFocusRequester.requestFocus()
        } catch (_: IllegalStateException) {
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp)
            .onPreviewKeyEvent {
                val offset = when (it.key) {
                    Key.ButtonL1 -> -1
                    Key.ButtonR1 -> 1
                    else -> return@onPreviewKeyEvent false
                }
                if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.repeatCount == 0) {
                    val index = categories.indexOf(selectedCategory) + offset
                    selectedCategory = categories[index.mod(categories.size)]
                }
                true
            }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SkylanderSearchField(
                query = query,
                onQueryChange = {
                    query = it
                    if (it.isNotEmpty()) {
                        selectedCategory = null
                    }
                },
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onShowAdvanced) { Text(tr("Advanced")) }
        }

        PortalFigures(skylanderSlots = skylanderSlots, onClear = onClear)

        CategoryChips(
            categories = categories,
            selectedCategory = selectedCategory,
            onCategorySelected = { selectedCategory = it },
        )

        if (visibleFigures.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(tr("No Skylanders found"))
            }
            return@Column
        }

        val firstFigure = visibleFigures.values.first().first()

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            visibleFigures.forEach { (category, figures) ->
                if (selectedCategory == null) {
                    item(key = category.name, span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = skylanderCategoryToString(category),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }

                items(figures, key = { "${it.id}-${it.variant}" }) { figure ->
                    SkylanderTile(
                        figure = figure,
                        isOnPortal = figure.name in figuresOnPortal,
                        isSaved = figure.key in installedFigureKeys,
                        portrait = portraitFor(figure),
                        onClick = { onToggleFigure(figure) },
                        modifier = if (figure == firstFigure) {
                            Modifier.focusRequester(initialFocusRequester)
                        } else {
                            Modifier
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SkylanderSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboardController = LocalSoftwareKeyboardController.current

    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        singleLine = true,
        placeholder = { Text(tr("Search a Skylander")) },
        leadingIcon = {
            Icon(painter = painterResource(R.drawable.ic_search), contentDescription = null)
        },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close_small),
                        contentDescription = tr("Clear"),
                    )
                }
            }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Search,
            platformImeOptions = PlatformImeOptions("flagNoFullscreen|flagNoExtractUi"),
        ),
        keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
    )
}

@Composable
private fun PortalFigures(
    skylanderSlots: Array<String?>,
    onClear: (Int) -> Unit,
) {
    val loadedSlots = skylanderSlots.withIndex().filter { it.value != null }

    Row(
        modifier = Modifier.heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = tr("On the portal:"),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(end = 8.dp),
        )

        if (loadedSlots.isEmpty()) {
            Text(text = tr("Empty"), style = MaterialTheme.typography.bodyMedium)
            return@Row
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(loadedSlots, key = { it.index }) { (slot, name) ->
                val interactionSource = remember { MutableInteractionSource() }
                val isFocused by interactionSource.collectIsFocusedAsState()

                InputChip(
                    selected = true,
                    onClick = { onClear(slot) },
                    label = { Text(name.orEmpty()) },
                    trailingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_close_small),
                            contentDescription = tr("Remove"),
                        )
                    },
                    border = if (isFocused) {
                        BorderStroke(FocusBorderWidth, MaterialTheme.colorScheme.primary)
                    } else {
                        InputChipDefaults.inputChipBorder(enabled = true, selected = true)
                    },
                    interactionSource = interactionSource,
                    modifier = Modifier.confirmWithGamepadA { onClear(slot) },
                )
            }
        }
    }
}

@Composable
private fun CategoryChips(
    categories: List<SkylanderCategory?>,
    selectedCategory: SkylanderCategory?,
    onCategorySelected: (SkylanderCategory?) -> Unit,
) {
    val listState = rememberLazyListState()

    // Keep the category selected with L1/R1 visible
    LaunchedEffect(selectedCategory) {
        listState.animateScrollToItem((categories.indexOf(selectedCategory) - 1).coerceAtLeast(0))
    }

    LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(categories) { category ->
            val interactionSource = remember { MutableInteractionSource() }
            val isFocused by interactionSource.collectIsFocusedAsState()
            val isSelected = category == selectedCategory

            FilterChip(
                selected = isSelected,
                onClick = { onCategorySelected(category) },
                label = { Text(skylanderCategoryToString(category)) },
                border = if (isFocused) {
                    BorderStroke(FocusBorderWidth, MaterialTheme.colorScheme.primary)
                } else {
                    FilterChipDefaults.filterChipBorder(enabled = true, selected = isSelected)
                },
                interactionSource = interactionSource,
                modifier = Modifier.confirmWithGamepadA { onCategorySelected(category) },
            )
        }
    }
}

@Composable
private fun SkylanderTile(
    figure: NativeEmulatedUSBDevices.SkylanderFigure,
    isOnPortal: Boolean,
    isSaved: Boolean,
    portrait: ImageBitmap?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Card(
        onClick = onClick,
        modifier = modifier.confirmWithGamepadA(onClick),
        colors = if (isOnPortal) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
        border = if (isFocused) BorderStroke(FocusBorderWidth, MaterialTheme.colorScheme.primary) else null,
        interactionSource = interactionSource,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 56.dp)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (portrait != null) {
                Image(
                    bitmap = portrait,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = figure.name,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                )

                val status = when {
                    isOnPortal -> tr("On the portal")
                    isSaved -> tr("Saved")
                    else -> null
                }
                if (status != null) {
                    Text(text = status, style = MaterialTheme.typography.labelSmall)
                }
            }

            if (isOnPortal) {
                Icon(
                    painter = painterResource(R.drawable.ic_check_circle),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * Gamepads usually send BUTTON_A, which only reaches clickable elements through the device's
 * key fallback to DPAD_CENTER. Handle it explicitly so that A always confirms.
 */
private fun Modifier.confirmWithGamepadA(onConfirm: () -> Unit) = onKeyEvent {
    if (it.key != Key.ButtonA) {
        return@onKeyEvent false
    }
    if (it.type == KeyEventType.KeyUp) {
        onConfirm()
    }
    true
}

private fun skylanderCategoryToString(category: SkylanderCategory?) = when (category) {
    null -> tr("All")
    SkylanderCategory.SPYROS_ADVENTURE -> "Spyro's Adventure"
    SkylanderCategory.GIANTS -> "Giants"
    SkylanderCategory.SWAP_FORCE -> "SWAP Force"
    SkylanderCategory.TRAP_TEAM -> "Trap Team"
    SkylanderCategory.SUPERCHARGERS -> "SuperChargers"
    SkylanderCategory.IMAGINATORS -> "Imaginators"
    SkylanderCategory.ITEMS_AND_ADVENTURES -> tr("Items & adventure packs")
    SkylanderCategory.OTHER -> tr("Other")
}
