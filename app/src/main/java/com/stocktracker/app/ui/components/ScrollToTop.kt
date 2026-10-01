package com.stocktracker.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * A small "back to top" button, shown only once the page has been scrolled about a screen down.
 * One look on every tab: the tab screens are long, and the way back up was a long swipe.
 */
@Composable
fun ScrollToTopButton(state: LazyListState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val show by remember(state) { derivedStateOf { state.firstVisibleItemIndex > 2 } }
    ScrollToTopFab(show, modifier) { scope.launch { state.animateScrollToItem(0) } }
}

@Composable
fun ScrollToTopButton(state: ScrollState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val threshold = with(LocalDensity.current) { 700.dp.toPx() }
    val show by remember(state, threshold) { derivedStateOf { state.value > threshold } }
    ScrollToTopFab(show, modifier) { scope.launch { state.animateScrollTo(0) } }
}

@Composable
private fun ScrollToTopFab(show: Boolean, modifier: Modifier, onClick: () -> Unit) {
    AnimatedVisibility(visible = show, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut(), modifier = modifier) {
        SmallFloatingActionButton(
            onClick = onClick,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Back to top")
        }
    }
}
