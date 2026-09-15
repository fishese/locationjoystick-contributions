package com.locationjoystick.feature.map.impl

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.locationjoystick.core.data.CooldownState
import com.locationjoystick.core.data.toBadgeText
import com.locationjoystick.core.designsystem.LjIcons
import com.locationjoystick.core.designsystem.component.CaptureCoordinatesForm
import com.locationjoystick.core.designsystem.component.CooldownAdvisoryBadge
import com.locationjoystick.core.designsystem.component.FavoriteTargetDetail
import com.locationjoystick.core.designsystem.component.FavoritesList
import com.locationjoystick.core.designsystem.component.PasteCoordinatesForm
import com.locationjoystick.core.designsystem.component.RouteStartSheetContent
import com.locationjoystick.core.designsystem.component.RoutesPickerList
import com.locationjoystick.core.designsystem.component.rememberLjSheetState
import com.locationjoystick.core.model.LatLng
import com.locationjoystick.core.model.startWaypoint

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RoutesPickerSheet(
    uiState: MapUiState,
    onAction: (MapAction) -> Unit,
) {
    var selectedRouteId by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = {
            selectedRouteId = null
            onAction(MapAction.CloseRoutesSheet)
        },
        sheetState = rememberLjSheetState(),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        val routeId = selectedRouteId
        if (routeId != null) {
            val route = uiState.routes.find { it.id == routeId }
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selectedRouteId = null }) {
                        Icon(LjIcons.ArrowBack, contentDescription = "Back")
                    }
                    Text(
                        text = route?.name ?: "Start route",
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                Spacer(Modifier.height(8.dp))
                RouteStartSheetContent(
                    key = routeId,
                    onTeleport = { reverse ->
                        route?.startWaypoint(reverse)?.let { onAction(MapAction.ConfirmTeleport(it.position)) }
                    },
                    onStart = {
                        loop,
                        reverse,
                        returnToLocation,
                        followRoads,
                        planting,
                        teleportBetweenWaypoints,
                        delaySeconds,
                        ->
                        onAction(
                            MapAction.StartRouteReplay(
                                routeId,
                                loop,
                                reverse,
                                returnToLocation,
                                followRoads,
                                planting,
                                teleportBetweenWaypoints,
                                delaySeconds,
                            ),
                        )
                        selectedRouteId = null
                    },
                    onCancel = {
                        selectedRouteId = null
                        onAction(MapAction.CloseRoutesSheet)
                    },
                    hideTeleport = uiState.hideTeleportFeatures,
                )
            }
        } else {
            RoutesPickerList(
                routes = uiState.routes,
                onSelect = { selectedRouteId = it.id },
                title = "Routes",
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FavoritesPickerSheet(
    uiState: MapUiState,
    onAction: (MapAction) -> Unit,
) {
    var showSaveDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = { onAction(MapAction.CloseFavoritesPicker) },
        sheetState = rememberLjSheetState(),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        val target = uiState.favoriteTarget
        if (target == null) {
            FavoritesList(
                title = "Favorites",
                favorites = uiState.favorites,
                onSelect = { onAction(MapAction.SelectFavorite(it)) },
                onSaveCurrentLocation =
                    if (uiState.currentPosition != null) {
                        { showSaveDialog = true }
                    } else {
                        null
                    },
                cooldownBadgeText = { fav ->
                    (uiState.favoriteCooldownStates[fav.id] ?: CooldownState.Ready)
                        .toBadgeText(uiState.currentPosition, fav.position)
                },
            )
        } else {
            FavoriteTargetDetail(
                favorite = target,
                onSetLocation = { onAction(MapAction.SetLocationTo(target.position)) },
                onGoToLocation = { onAction(MapAction.WalkStraightTo(target.position)) },
                onGoToLocationViaRoads = { onAction(MapAction.WalkViaRoadsTo(target.position)) },
                onDismiss = { onAction(MapAction.CloseFavoritesPicker) },
                hideTeleportFeatures = uiState.hideTeleportFeatures,
            )
        }
    }

    if (showSaveDialog) {
        SaveCurrentLocationDialog(
            onDismiss = { showSaveDialog = false },
            onSave = { name ->
                onAction(MapAction.SaveCurrentLocation(name))
                showSaveDialog = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PasteCoordinatesSheet(
    onDismiss: () -> Unit,
    onTeleport: (LatLng) -> Unit,
    onWalk: (LatLng) -> Unit,
    onWalkViaRoads: (LatLng) -> Unit,
    onSaveFavorite: (name: String, position: LatLng) -> Unit,
    onSaveRoute: (name: String, points: List<LatLng>) -> Unit,
    onStartRoute: (
        points: List<LatLng>,
        loop: Boolean,
        reverse: Boolean,
        returnToLocation: Boolean,
        followRoads: Boolean,
        planting: Boolean,
        teleportBetweenWaypoints: Boolean,
        teleportBetweenDelaySeconds: Int,
    ) -> Unit,
    hideTeleportFeatures: Boolean = false,
    title: String = "Paste coordinates",
    initialText: String = "",
    initialRouteName: String = "",
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    ModalBottomSheet(
        onDismissRequest = {
            if (shouldHonorPasteSheetDismiss(lifecycleOwner.lifecycle.currentState)) {
                onDismiss()
            }
        },
        sheetState = rememberLjSheetState(),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        PasteCoordinatesForm(
            onDismiss = onDismiss,
            onTeleport = onTeleport,
            onWalk = onWalk,
            onWalkViaRoads = onWalkViaRoads,
            onSaveFavorite = onSaveFavorite,
            onSaveRoute = onSaveRoute,
            onStartRoute = onStartRoute,
            hideTeleportFeatures = hideTeleportFeatures,
            title = title,
            initialText = initialText,
            initialRouteName = initialRouteName,
        )
    }
}

@Composable
internal fun CaptureCoordinatesSheet(
    uiState: CaptureCoordinatesUiState,
    onCaptureModeEnabledChange: (Boolean) -> Unit,
    onCaptureEnabledChange: (Boolean) -> Unit,
    onJumpEnabledChange: (Boolean) -> Unit,
    onRouteNameChange: (String) -> Unit,
    onPointOrderChange: (CapturePointOrder) -> Unit,
    onSaveRoute: () -> Unit,
    onClearPoints: () -> Unit,
    onRemoveLast: () -> Unit,
    onRequestDefaultBrowser: () -> Unit,
    onOpenThisAppLinks: () -> Unit,
    onOpenMapsLinks: () -> Unit,
    onRestoreDefaultApps: () -> Unit,
    passThroughBrowserName: String,
    onChoosePassThroughBrowser: () -> Unit,
    onDismiss: () -> Unit,
    isDefaultBrowser: Boolean = false,
) {
    BackHandler(onBack = onDismiss)
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        CaptureCoordinatesForm(
            captureModeEnabled = uiState.captureModeEnabled,
            captureEnabled = uiState.captureEnabled,
            jumpEnabled = uiState.jumpEnabled,
            points = uiState.points,
            routeName = uiState.routeName,
            saved = uiState.saved,
            saveError = uiState.saveError,
            canSave = uiState.canSave,
            passThroughBrowserName = passThroughBrowserName,
            isDefaultBrowser = isDefaultBrowser,
            optimizeProximity = uiState.pointOrder == CapturePointOrder.PROXIMITY,
            onOptimizeProximityChange = { optimize ->
                onPointOrderChange(if (optimize) CapturePointOrder.PROXIMITY else CapturePointOrder.ORIGINAL)
            },
            orderedPoints = uiState.orderedPoints,
            onCaptureModeEnabledChange = onCaptureModeEnabledChange,
            onCaptureEnabledChange = onCaptureEnabledChange,
            onJumpEnabledChange = onJumpEnabledChange,
            onRouteNameChange = onRouteNameChange,
            onSaveRoute = onSaveRoute,
            onClearPoints = onClearPoints,
            onRemoveLast = onRemoveLast,
            onRequestDefaultBrowser = onRequestDefaultBrowser,
            onOpenThisAppLinks = onOpenThisAppLinks,
            onOpenMapsLinks = onOpenMapsLinks,
            onRestoreDefaultApps = onRestoreDefaultApps,
            onChoosePassThroughBrowser = onChoosePassThroughBrowser,
            onDismiss = onDismiss,
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PendingTapSheet(
    position: com.locationjoystick.core.model.LatLng,
    isRouteReplay: Boolean,
    isWalkActive: Boolean,
    cooldownState: CooldownState,
    onAction: (MapAction) -> Unit,
    isEphemeralReplay: Boolean = false,
    onShare: (() -> Unit)? = null,
    hideTeleportFeatures: Boolean = false,
) {
    ModalBottomSheet(
        onDismissRequest = { onAction(MapAction.ClearPendingTap) },
        sheetState = rememberLjSheetState(),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (isRouteReplay && !isEphemeralReplay) {
                Text("Route in progress", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                if (!hideTeleportFeatures) {
                    Button(
                        onClick = { onAction(MapAction.StopRouteAndTeleport(position)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Stop route and teleport")
                    }
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedButton(
                    onClick = { onAction(MapAction.StopRouteAndWalkTo(position)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Stop route and walk here")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { onAction(MapAction.FinishRouteAndWalkTo(position)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Finish route and walk here")
                }
            } else {
                Text("Move to this location?", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                CooldownAdvisoryBadge(
                    (cooldownState as? CooldownState.Cooling)?.toAdvisoryLabel() ?: "No wait needed",
                )
                Spacer(Modifier.height(8.dp))
                if (!hideTeleportFeatures) {
                    Button(
                        onClick = { onAction(MapAction.ConfirmTeleport(position)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Teleport here")
                    }
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedButton(
                    onClick = {
                        onAction(MapAction.LongPressTapToWalk(position))
                        onAction(MapAction.ClearPendingTap)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Walk here")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        onAction(MapAction.WalkViaRoadsTo(position))
                        onAction(MapAction.ClearPendingTap)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Walk here via roads")
                }
                if (isWalkActive) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { onAction(MapAction.AddEphemeralWaypoint(position, followRoads = false)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Add next point")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { onAction(MapAction.AddEphemeralWaypoint(position, followRoads = true)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Add next point via roads")
                    }
                }
            }
            if (onShare != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onShare,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Share this location")
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = { onAction(MapAction.ClearPendingTap) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Close")
            }
        }
    }
}

@Composable
internal fun SaveCurrentLocationDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save current location") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim()) },
                enabled = name.isNotBlank(),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
