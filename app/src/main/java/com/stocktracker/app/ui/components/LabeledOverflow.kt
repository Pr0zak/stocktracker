package com.stocktracker.app.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** One entry in an app bar's overflow menu. */
data class OverflowAction(
    val label: String,
    val icon: ImageVector? = null,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * The app bar's overflow, with the labels showing.
 *
 * This app's screens carried up to five unlabelled icon actions each, and those glyphs were the
 * only entrance to whole features. An `AutoAwesome` sparkle opened an AI market brief; a
 * `Leaderboard` opened a three-thousand-name nightly scan; a `MenuBook` on Portfolio was the single
 * way into the verdict journal. Nothing on screen said so. Discovering half the product meant
 * tapping glyphs experimentally and then memorising a per-screen vocabulary.
 *
 * A menu is not a clever solution — it is the conventional one, and that is the point. Two icons is
 * as many as a person will learn by shape; everything past that earns a word.
 */
@Composable
fun LabeledOverflow(
    actions: List<OverflowAction>,
    contentDescription: String = "More options",
) {
    if (actions.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = contentDescription)
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        actions.forEach { action ->
            DropdownMenuItem(
                text = { Text(action.label) },
                enabled = action.enabled,
                leadingIcon = action.icon?.let {
                    { Icon(it, contentDescription = null, modifier = Modifier.size(20.dp)) }
                },
                onClick = {
                    open = false
                    action.onClick()
                },
            )
        }
    }
}
