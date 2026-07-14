package com.stocktracker.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Swipe a row left to reveal a trash button; deletion only happens when that button is tapped.
 * A partial swipe snaps back, so an accidental brush never deletes anything.
 */
@Composable
fun SwipeToDeleteRow(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val actionWidthPx = with(density) { 88.dp.toPx() }
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Box(modifier) {
        // Trash action, revealed on the trailing edge as the row slides left.
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            FilledIconButton(
                onClick = {
                    scope.launch { offsetX.animateTo(0f) }
                    onDelete()
                },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                modifier = Modifier.padding(end = 12.dp),
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Remove from watchlist")
            }
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch {
                            offsetX.snapTo((offsetX.value + delta).coerceIn(-actionWidthPx, 0f))
                        }
                    },
                    onDragStopped = {
                        // Snap fully open past the halfway point, otherwise snap closed.
                        val target = if (offsetX.value <= -actionWidthPx / 2f) -actionWidthPx else 0f
                        offsetX.animateTo(target)
                    },
                ),
        ) {
            content()
        }
    }
}
