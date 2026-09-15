package com.locationjoystick.core.designsystem

import androidx.compose.ui.unit.dp

object UiConstants {
    val FAB_CONTAINER_SIZE = 42.dp
    val FAB_ICON_SIZE = 24.dp
    val FAB_SPACING = FAB_CONTAINER_SIZE / 4

    /** Clears the menu icon and up to two trailing action icons so Start cannot overlap them. */
    val TOP_BAR_START_HORIZONTAL_PADDING = 112.dp

    /** Max content width on tablets / foldables so list screens don't stretch edge-to-edge. */
    val WIDE_CONTENT_MAX_WIDTH = 600.dp
}
