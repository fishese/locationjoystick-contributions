package com.locationjoystick.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.locationjoystick.core.common.constants.AppConstants
import com.locationjoystick.core.common.util.parseTeleportBetweenDelaySeconds

/**
 * Bundles the loop/planting/reverse/returnToLocation/followRoads/teleport-between state a
 * "start route" sheet needs plus its wiring to [LjRouteStartOptions] — duplicated identically
 * across every surface that offers this sheet before this extraction (map long-press sheet,
 * Routes screen). Loop starts checked.
 */
@Composable
fun RouteStartSheetContent(
    key: Any?,
    onTeleport: (reverse: Boolean) -> Unit,
    onStart: (
        loop: Boolean,
        reverse: Boolean,
        returnToLocation: Boolean,
        followRoads: Boolean,
        planting: Boolean,
        teleportBetweenWaypoints: Boolean,
        teleportBetweenDelaySeconds: Int,
    ) -> Unit,
    onCancel: () -> Unit,
    hideTeleport: Boolean = false,
) {
    var loop by remember(key) { mutableStateOf(true) }
    var reverse by remember(key) { mutableStateOf(false) }
    var returnToLocation by remember(key) { mutableStateOf(false) }
    var followRoads by remember(key) { mutableStateOf(false) }
    var planting by remember(key) { mutableStateOf(false) }
    var teleportBetweenWaypoints by remember(key) { mutableStateOf(false) }
    var teleportBetweenDelaySecondsText by remember(key) {
        mutableStateOf(AppConstants.RouteConstants.TELEPORT_BETWEEN_DEFAULT_DELAY_SECONDS.toString())
    }

    LjRouteStartOptions(
        loop = loop,
        onLoopChange = { loop = it },
        reverse = reverse,
        onReverseChange = { reverse = it },
        returnToLocation = returnToLocation,
        onReturnToLocationChange = { returnToLocation = it },
        followRoads = followRoads,
        onFollowRoadsChange = { followRoads = it },
        planting = planting,
        onPlantingChange = {
            planting = it
            if (it) returnToLocation = false
        },
        teleportBetweenWaypoints = teleportBetweenWaypoints && !hideTeleport,
        onTeleportBetweenWaypointsChange = { teleportBetweenWaypoints = it },
        teleportBetweenDelaySecondsText = teleportBetweenDelaySecondsText,
        onTeleportBetweenDelaySecondsTextChange = { teleportBetweenDelaySecondsText = it },
        onTeleport = { onTeleport(reverse) },
        onCancel = onCancel,
        onStart = {
            onStart(
                loop || planting,
                reverse,
                returnToLocation && !loop && !planting,
                followRoads,
                planting,
                teleportBetweenWaypoints && !hideTeleport,
                parseTeleportBetweenDelaySeconds(teleportBetweenDelaySecondsText),
            )
        },
        hideTeleport = hideTeleport,
    )
}
