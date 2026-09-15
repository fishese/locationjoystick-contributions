package com.locationjoystick.core.designsystem.component

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.locationjoystick.core.designsystem.LjError
import com.locationjoystick.core.designsystem.LjIcons
import com.locationjoystick.core.designsystem.LjSpacing
import com.locationjoystick.core.designsystem.LjSuccess

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LjTopBar(
    title: String,
    isSpoofing: Boolean,
    onToggleSpoofing: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigationClick: (() -> Unit)? = null,
    navigationIcon: ImageVector = LjIcons.Menu,
    actions: @Composable () -> Unit = {},
    showSpoofToggle: Boolean = true,
    locationLabel: String? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(TopAppBarDefaults.windowInsets)
                    .padding(horizontal = LjSpacing.xs, vertical = LjSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onNavigationClick != null) {
                    IconButton(onClick = onNavigationClick) {
                        Icon(
                            imageVector = navigationIcon,
                            contentDescription = "Open navigation menu",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                if (title.isNotBlank()) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                if (showSpoofToggle) {
                    val interactionSource = remember { MutableInteractionSource() }
                    val tint = if (isSpoofing) LjError else LjSuccess
                    // Immediate click: combinedClickable delays onClick until the long-press
                    // timeout, so a firm first press toasts the place name instead of starting.
                    Row(
                        modifier =
                            Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .semantics {
                                    contentDescription =
                                        if (isSpoofing) {
                                            "Stop location simulation"
                                        } else {
                                            "Start location simulation"
                                        }
                                }.clickable(
                                    interactionSource = interactionSource,
                                    indication = LocalIndication.current,
                                    onClick = onToggleSpoofing,
                                ).padding(horizontal = LjSpacing.sm, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Crossfade(
                            targetState = isSpoofing,
                            animationSpec = tween(150),
                            label = "spoofToggleIcon",
                        ) { spoofing ->
                            Icon(
                                imageVector = if (spoofing) LjIcons.Stop else LjIcons.PlayArrow,
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(12.dp).padding(end = 3.dp),
                            )
                        }
                        Text(
                            text = startToggleLabel(isSpoofing, locationLabel),
                            style = MaterialTheme.typography.labelSmall,
                            color = tint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions()
            }
        }
    }
}
