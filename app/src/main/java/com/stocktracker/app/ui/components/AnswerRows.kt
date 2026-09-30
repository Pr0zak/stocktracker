package com.stocktracker.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * ABOUT-2 — "answer rows", the redesign's one pattern for a screen of cards.
 *
 * A screen of eleven stacked cards made the reader open each one to learn anything. An answer row is
 * one line per card: an icon square, a plain title, a short plain subtitle, and the card's RESULT on
 * the right (a pill, a number, or a chevron). Tapping it opens the card that was there before, in
 * place, so nothing is lost; it is only folded.
 *
 * [AnswerGroup] is the card the rows sit in. Rows inside one group are separated by hairlines.
 */
@Composable
fun AnswerGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    trailingTitle: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .animateContentSize(),
    ) {
        if (title != null) {
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = MaterialTheme.typography.labelMedium.letterSpacing * 1.5f,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                trailingTitle?.invoke()
            }
        }
        content()
    }
}

/** A hairline between rows of one [AnswerGroup]. */
@Composable
fun AnswerDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
}

/** The 36dp tinted square a row leads with. */
@Composable
fun IconSquare(icon: ImageVector, tint: Color) {
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/**
 * One row. [trailing] is the answer (usually a [Pill]). With [onClick] and no [content], the row is a
 * link and shows a chevron. With [content], tapping folds the content open and shut in place, and
 * the open state survives rotation under [key].
 */
@Composable
fun AnswerRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String? = null,
    key: String = title,
    startOpen: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    var open by rememberSaveable(key) { mutableStateOf(startOpen) }
    val clickable = content != null || onClick != null
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(
                    if (!clickable) Modifier
                    else Modifier.clickable(onClickLabel = if (content != null) (if (open) "Fold" else "Open") else null) {
                        if (content != null) open = !open else onClick?.invoke()
                    },
                )
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconSquare(icon, tint)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing?.invoke()
            when {
                content != null -> Icon(
                    if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                onClick != null -> Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (open && content != null) {
            Column(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}
