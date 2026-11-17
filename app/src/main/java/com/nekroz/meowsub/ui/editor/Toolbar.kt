package com.nekroz.meowsub.ui.editor

import androidx.annotation.StringRes
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.nekroz.meowsub.R
import com.nekroz.meowsub.ui.editor.ToolbarConfig.moved

/** A toolbar button bound to its current state. [busy] shows a spinner (e.g. while saving). */
class BarAction(
    val icon: ImageVector,
    @param:StringRes val label: Int,
    val enabled: Boolean = true,
    val busy: Boolean = false,
    val onClick: () -> Unit,
)

fun ToolbarAction.icon(): ImageVector = when (this) {
    ToolbarAction.UNDO -> Icons.AutoMirrored.Filled.Undo
    ToolbarAction.REDO -> Icons.AutoMirrored.Filled.Redo
    ToolbarAction.ADD -> Icons.Filled.Add
    ToolbarAction.INSERT_BEFORE -> Icons.Filled.VerticalAlignTop
    ToolbarAction.DELETE -> Icons.Outlined.Delete
    ToolbarAction.COPY -> Icons.Filled.ContentCopy
    ToolbarAction.COPY_TEXT -> Icons.AutoMirrored.Filled.Notes
    ToolbarAction.CUT -> Icons.Filled.ContentCut
    ToolbarAction.PASTE -> Icons.Filled.ContentPaste
    ToolbarAction.SPLIT -> Icons.AutoMirrored.Filled.CallSplit
    ToolbarAction.MERGE -> Icons.AutoMirrored.Filled.CallMerge
    ToolbarAction.SORT -> Icons.AutoMirrored.Filled.Sort
    ToolbarAction.SAVE -> Icons.Filled.Save
}

@StringRes
fun ToolbarAction.label(): Int = when (this) {
    ToolbarAction.UNDO -> R.string.action_undo
    ToolbarAction.REDO -> R.string.action_redo
    ToolbarAction.ADD -> R.string.action_add_after
    ToolbarAction.INSERT_BEFORE -> R.string.action_insert_before
    ToolbarAction.DELETE -> R.string.action_delete
    ToolbarAction.COPY -> R.string.action_copy
    ToolbarAction.COPY_TEXT -> R.string.action_copy_text
    ToolbarAction.CUT -> R.string.action_cut
    ToolbarAction.PASTE -> R.string.action_paste
    ToolbarAction.SPLIT -> R.string.action_split
    ToolbarAction.MERGE -> R.string.action_merge
    ToolbarAction.SORT -> R.string.action_sort
    ToolbarAction.SAVE -> R.string.action_save
}

private val ActionSlot = 48.dp
private val FadeWidth = 24.dp

/**
 * App bar actions: [bar] in a row that scrolls sideways when wider than [maxBarWidth],
 * followed by a ⋮ menu with [menu] and an "edit toolbar" entry.
 */
@Composable
fun RowScope.ScrollableActions(
    bar: List<BarAction>,
    menu: List<BarAction>,
    maxBarWidth: Dp,
    onEditToolbar: () -> Unit,
) {
    val scroll = rememberScrollState()
    Row(
        Modifier
            .widthIn(max = maxBarWidth.coerceAtLeast(0.dp))
            .fadingEdges(scroll, FadeWidth)
            .horizontalScroll(scroll),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        bar.forEach { action ->
            if (action.busy) {
                Box(Modifier.size(ActionSlot), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            } else {
                IconButton(onClick = action.onClick, enabled = action.enabled) {
                    Icon(action.icon, contentDescription = stringResource(action.label))
                }
            }
        }
    }

    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            menu.forEach { action ->
                DropdownMenuItem(
                    text = { Text(stringResource(action.label)) },
                    leadingIcon = { Icon(action.icon, null) },
                    enabled = action.enabled && !action.busy,
                    onClick = {
                        open = false
                        action.onClick()
                    },
                )
            }
            if (menu.isNotEmpty()) HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.toolbar_edit)) },
                leadingIcon = { Icon(Icons.Filled.Tune, null) },
                onClick = {
                    open = false
                    onEditToolbar()
                },
            )
        }
    }
}

/** Fades out the edges that have more content scrolled past them, as a hint that the row scrolls. */
private fun Modifier.fadingEdges(state: ScrollState, width: Dp) = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val w = width.toPx().coerceAtMost(size.width / 2)
        if (state.value > 0) {
            drawRect(
                brush = Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black, startX = 0f, endX = w),
                size = Size(w, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
        if (state.value < state.maxValue) {
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Black, 1f to Color.Transparent,
                    startX = size.width - w, endX = size.width,
                ),
                topLeft = Offset(size.width - w, 0f),
                size = Size(w, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
    }

private val DialogRowHeight = 52.dp

/**
 * Lets the user reorder the toolbar actions (long-press and drag a row) and choose for each
 * whether it shows on the toolbar or in the ⋮ menu.
 */
@Composable
fun ToolbarEditorDialog(
    initial: List<ToolbarItem>,
    onConfirm: (List<ToolbarItem>) -> Unit,
    onDismiss: () -> Unit,
) {
    var items by remember { mutableStateOf(initial) }
    var dragging by remember { mutableStateOf<ToolbarAction?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { DialogRowHeight.toPx() }
    val haptics = LocalHapticFeedback.current
    val cs = MaterialTheme.colorScheme

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Tune, null) },
        title = { Text(stringResource(R.string.toolbar_edit)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.toolbar_edit_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    // The gesture lives on this (untransformed) column so moving rows doesn't disturb it.
                    Column(
                        Modifier.pointerInput(Unit) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { pos ->
                                    val index = (pos.y / rowPx).toInt()
                                    if (index in items.indices) {
                                        dragging = items[index].action
                                        dragOffset = 0f
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                },
                                onDragEnd = { dragging = null; dragOffset = 0f },
                                onDragCancel = { dragging = null; dragOffset = 0f },
                                onDrag = { change, amount ->
                                    val moving = dragging ?: return@detectDragGesturesAfterLongPress
                                    change.consume()
                                    dragOffset += amount.y
                                    val from = items.indexOfFirst { it.action == moving }
                                    if (dragOffset > rowPx / 2 && from < items.lastIndex) {
                                        items = items.moved(from, from + 1)
                                        dragOffset -= rowPx
                                    } else if (dragOffset < -rowPx / 2 && from > 0) {
                                        items = items.moved(from, from - 1)
                                        dragOffset += rowPx
                                    }
                                },
                            )
                        }
                    ) {
                        items.forEach { item ->
                            key(item.action) {
                                val lifted = dragging == item.action
                                ToolbarEditorRow(
                                    item = item,
                                    modifier = Modifier
                                        .zIndex(if (lifted) 1f else 0f)
                                        .graphicsLayer {
                                            translationY = if (lifted) dragOffset else 0f
                                            shadowElevation = if (lifted) 6.dp.toPx() else 0f
                                            shape = RoundedCornerShape(8.dp)
                                            clip = lifted
                                        }
                                        .background(if (lifted) cs.surfaceContainerHighest else Color.Transparent),
                                    onToggle = { onBar ->
                                        items = items.map { if (it.action == item.action) it.copy(onBar = onBar) else it }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(items) }) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { items = ToolbarConfig.DEFAULT }) { Text(stringResource(R.string.toolbar_reset)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

@Composable
private fun ToolbarEditorRow(item: ToolbarItem, modifier: Modifier, onToggle: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val label = stringResource(item.action.label())
    val onBarDescription = stringResource(R.string.toolbar_on_bar)
    Row(
        modifier
            .fillMaxWidth()
            .height(DialogRowHeight)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.DragIndicator, null, tint = cs.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Icon(item.action.icon(), null, tint = if (item.onBar) cs.onSurface else cs.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = if (item.onBar) cs.onSurface else cs.onSurfaceVariant,
            maxLines = 1,
        )
        Switch(
            checked = item.onBar,
            onCheckedChange = onToggle,
            modifier = Modifier.semantics { contentDescription = "$label：$onBarDescription" },
        )
    }
}
