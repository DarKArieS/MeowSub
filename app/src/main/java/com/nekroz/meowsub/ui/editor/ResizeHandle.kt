package com.nekroz.meowsub.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nekroz.meowsub.R

/**
 * Thin divider under the waveform: drag up/down to resize it, double-tap to restore the default
 * height. Compose's minimum touch target still lets a finger grab it a little outside the strip,
 * where no neighbouring control is hit.
 */
@Composable
fun ResizeHandle(onDrag: (Float) -> Unit, onDragStopped: () -> Unit, onReset: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val description = stringResource(R.string.waveform_resize)
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(cs.surfaceContainer)
            .draggable(
                state = rememberDraggableState(onDrag),
                orientation = Orientation.Vertical,
                onDragStopped = { onDragStopped() },
            )
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onReset() }) }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 32.dp, height = 3.dp)
                .background(cs.onSurfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(1.5.dp))
        )
    }
}
