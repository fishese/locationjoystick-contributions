package com.locationjoystick.core.location

import android.util.Log
import com.locationjoystick.core.common.constants.AppConstants
import com.locationjoystick.core.common.util.BearingTracker
import com.locationjoystick.core.common.util.buildPlantingReplayPath
import com.locationjoystick.core.common.util.plantingRings
import com.locationjoystick.core.common.util.stitchRingsWithConnectorsAndBoundaries
import com.locationjoystick.core.common.util.stitchRingsWithoutConnectors
import com.locationjoystick.core.data.LocationRepository
import com.locationjoystick.core.data.RoamingRepository
import com.locationjoystick.core.data.RouteRepository
import com.locationjoystick.core.data.WalkToEngine
import com.locationjoystick.core.model.LatLng
import com.locationjoystick.core.model.MockLocationState
import com.locationjoystick.core.model.MockMode
import com.locationjoystick.core.routing.OsrmClient
import com.locationjoystick.core.routing.OsrmFailureReason
import com.locationjoystick.core.routing.RouteReplayEngine
import com.locationjoystick.core.routing.RoutingErrorReporter
import com.locationjoystick.core.routing.osrmFailureMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "ReplayOrchestrator"

/**
 * Owns all route-replay and walk-to orchestration logic extracted from [MockLocationService].
 *
 * `followRoadsToStart` governs every leg between the route's own saved waypoints during
 * replay (see [expandWaypointsForFollowRoads]). When planting is on, those legs are
 * replaced by geometric circles around each saved stop ([expandWaypointsForPlanting]);
 * Follow roads then only applies to the connectors between rings. When teleport-between-waypoints
 * is also on, those connectors are skipped: each circle is walked fully, then replay hops to
 * the start of the next circle (the same snap as skip-to-next-stop). Planting always
 * loops (last ring back to the first ring's start) until the user stops replay.
 * The pre-replay
 * approach to the first waypoint is separate: [teleportToStart] (default true) snaps
 * there instantly; when false, the same flag also chooses a road-following walk vs a
 * straight walk.
 *
 * Communicates back to the service via lambdas:
 * - [onStateChange]: writes into the service's `_state` MutableStateFlow
 * - [onPositionChange]: updates `currentLat` / `currentLon` @Volatile fields
 * - [onSpeedChange]: updates `currentSpeedMs` @Volatile field
 * - [pushLocationUpdate]: pushes a GPS tick via the test provider
 * - [startUpdateLoop]: restarts the idle update loop after replay ends
 */
internal class ReplayOrchestrator(
    private val locationRepository: LocationRepository,
    private val routeRepository: RouteRepository,
    private val roamingRepository: RoamingRepository,
    private val routeReplayEngine: RouteReplayEngine,
    private val walkToEngine: WalkToEngine,
    private val osrmClient: OsrmClient,
    private val routingErrorReporter: RoutingErrorReporter,
    private val scope: CoroutineScope,
    private val onStateChange: (MockLocationState) -> Unit,
    private val onPositionChange: (lat: Double, lon: Double) -> Unit,
    private val onSpeedChange: (speedMs: Float) -> Unit,
    private val pushLocationUpdate: () -> Unit,
    private val startUpdateLoop: () -> Unit,
) {
    /** Tracks the active scope.launch job so new starts can cancel it before launching. */
    private var activeReplayJob: Job? = null

    /**
     * Bumped by [abortInFlightStart] so a Follow-roads (or Room) suspend that outlives
     * [Job.cancel] cannot call [startReplayWithWaypoints] and overwrite a user teleport.
     */
    private val startGeneration = AtomicInteger(0)

    private val bearingTracker = BearingTracker()

    /**
     * Cancels an in-flight [handleStart] / [handleEphemeralStart] immediately.
     *
     * Must run on the service's `onStartCommand` thread *before* `ACTION_UPDATE_POSITION`.
     * Follow-roads planning does not set [MockMode.ROUTE_REPLAY] until OSRM returns; a late
     * start would snap GPS back to the route after the user already teleported.
     */
    fun abortInFlightStart() {
        startGeneration.incrementAndGet()
        activeReplayJob?.cancel()
    }

    fun handleStart(
        routeId: String,
        isBackward: Boolean,
        speedMs: Double,
        isLoopingOverride: Boolean? = null,
        returnPosition: LatLng? = null,
        followRoadsToStart: Boolean = false,
        teleportToStart: Boolean = true,
        isPlanting: Boolean = false,
        teleportBetweenWaypoints: Boolean = false,
        teleportBetweenDelaySeconds: Int = AppConstants.RouteConstants.TELEPORT_BETWEEN_DEFAULT_DELAY_SECONDS,
    ) {
        val previous = activeReplayJob
        val generation = startGeneration.get()
        activeReplayJob =
            scope.launch {
                previous?.cancelAndJoin()
                if (!isCurrentStart(generation)) return@launch
                routeReplayEngine.stop()
                val route = routeRepository.getRouteWithWaypoints(routeId).first() ?: return@launch
                if (!isCurrentStart(generation)) return@launch
                if (route.waypoints.isEmpty()) return@launch
                if (!isPlanting && route.waypoints.size < 2) return@launch
                val latLngs = (if (isBackward) route.waypoints.reversed() else route.waypoints).map { it.position }
                val isLooping = isPlanting || (isLoopingOverride ?: route.isLooping)
                val (replayWaypoints, boundaryIndices) =
                    when {
                        isPlanting ->
                            expandWaypointsForPlanting(
                                latLngs,
                                followRoadsToStart,
                                teleportBetweenWaypoints,
                            )
                        teleportBetweenWaypoints -> latLngs to null
                        followRoadsToStart -> expandWaypointsForFollowRoads(latLngs)
                        else -> latLngs to null
                    }
                if (!isCurrentStart(generation)) return@launch
                if (replayWaypoints.size < 2) return@launch

                startReplayWithWaypoints(
                    generation = generation,
                    waypoints = replayWaypoints,
                    speedMs = speedMs,
                    isLooping = isLooping,
                    followRoadsToStart = followRoadsToStart,
                    teleportToStart = teleportToStart,
                    boundaryIndices = boundaryIndices,
                    teleportBetweenWaypoints = teleportBetweenWaypoints,
                    teleportBetweenDelaySeconds = teleportBetweenDelaySeconds,
                    persistMetadata = {
                        locationRepository.setActiveRouteId(routeId)
                        locationRepository.setIsReplayBackward(isBackward)
                        locationRepository.setRouteWaypoints(replayWaypoints)
                    },
                    onComplete = {
                        locationRepository.setRouteWaypoints(null)
                        locationRepository.setActiveRouteId(null)
                        if (returnPosition != null) {
                            walkToPosition(returnPosition, speedMs)
                        }
                        finishReplay()
                        locationRepository.emitCompletion("Route complete")
                    },
                )
            }
    }

    fun handleEphemeralStart(
        waypoints: List<LatLng>,
        speedMs: Double,
    ) {
        val previous = activeReplayJob
        val generation = startGeneration.get()
        activeReplayJob =
            scope.launch {
                previous?.cancelAndJoin()
                if (!isCurrentStart(generation)) return@launch
                routeReplayEngine.stop()
                startReplayWithWaypoints(
                    generation = generation,
                    waypoints = waypoints,
                    speedMs = speedMs,
                    isLooping = false,
                    persistMetadata = null,
                )
            }
    }

    private fun CoroutineScope.isCurrentStart(generation: Int): Boolean {
        ensureActive()
        return generation == startGeneration.get()
    }

    private suspend fun isStartStillCurrent(generation: Int): Boolean {
        kotlin.coroutines.coroutineContext.ensureActive()
        return generation == startGeneration.get()
    }

    /**
     * Propagates a live speed-profile change into the active replay. Caller must only invoke
     * this while a replay is actually running (mode == ROUTE_REPLAY), since it also overwrites
     * the service's reported `currentSpeedMs`.
     */
    fun updateSpeed(speedMs: Double) {
        routeReplayEngine.updateSpeed(speedMs)
        onSpeedChange(speedMs.toFloat())
    }

    fun handlePause() {
        routeReplayEngine.pause()
        onStateChange(MockLocationState.PAUSED)
        locationRepository.pauseSpoofing()
        publishProgress()
        Log.i(TAG, "Replay paused")
    }

    fun handleResume(speedMs: Double) {
        onStateChange(MockLocationState.RUNNING)
        locationRepository.startSpoofing()
        locationRepository.setMockMode(MockMode.ROUTE_REPLAY)
        val onComplete: () -> Unit = {
            // Matches startReplayWithWaypoints' default onComplete (used when a replay finishes
            // without ever being paused) — a natural completion must not force IDLE/stopSpoofing.
            // Forcing IDLE here previously killed a group-sync leader's broadcast on completion.
            finishReplay()
            locationRepository.emitCompletion("Route complete")
        }
        // Discard joystick wandering during pause: snap to the next named stop, then continue.
        val jumped = routeReplayEngine.jumpToNextWaypoint(::tickPosition, onComplete)
        jumped?.let(::tickPosition)
        routeReplayEngine.resume(
            onPositionUpdate = ::tickPosition,
            onComplete = onComplete,
        )
        publishProgress()
        Log.i(TAG, "Replay resumed at ${speedMs}m/s")
    }

    fun handleJumpToNextWaypoint() = handleJumpToWaypoint(forward = true)

    fun handleJumpToPreviousWaypoint() = handleJumpToWaypoint(forward = false)

    private fun handleJumpToWaypoint(forward: Boolean) {
        if (locationRepository.currentMode.value != MockMode.ROUTE_REPLAY) return
        val onReplayComplete: () -> Unit = {
            finishReplay()
            locationRepository.emitCompletion("Route complete")
        }
        val target =
            if (forward) {
                routeReplayEngine.jumpToNextWaypoint(::tickPosition, onReplayComplete)
            } else {
                routeReplayEngine.jumpToPreviousWaypoint(::tickPosition, onReplayComplete)
            }
        target?.let(::tickPosition)
        Log.i(TAG, "Jumped to ${if (forward) "next" else "previous"} waypoint")
    }

    /**
     * Pushes one position tick: updates the service's reported lat/lon, the shared
     * repository state, and the test-provider location.
     */
    private fun tickPosition(pos: LatLng) {
        // Mirrors updatePositionWithVector's gate: rejects a stale tick from a replay whose async cancel hasn't landed yet.
        if (locationRepository.currentMode.value != MockMode.ROUTE_REPLAY) return
        onPositionChange(pos.latitude, pos.longitude)
        bearingTracker.advance(pos)?.let { locationRepository.setBearingInternal(it) }
        try {
            locationRepository.setPositionInternal(pos)
            pushLocationUpdate()
            publishProgress()
        } catch (e: Exception) {
            Log.e(TAG, "Position update failed", e)
        }
    }

    suspend fun handleStop() {
        abortInFlightStart()
        activeReplayJob?.cancelAndJoin()
        activeReplayJob = null
        locationRepository.setRouteWaypoints(null)
        locationRepository.setRouteProgress(null)
        routeReplayEngine.stop()
        resetModeIfStillReplaying()
        locationRepository.setActiveRouteId(null)
        if (locationRepository.mockLocationState.value == MockLocationState.RUNNING) {
            startUpdateLoop()
        }
        Log.i(TAG, "Replay stopped; service remains active in TELEPORT mode")
    }

    suspend fun handleCancel() {
        abortInFlightStart()
        activeReplayJob?.cancelAndJoin()
        activeReplayJob = null
        locationRepository.setRouteWaypoints(null)
        locationRepository.setRouteProgress(null)
        routeReplayEngine.stop()
        resetModeIfStillReplaying()
        locationRepository.setActiveRouteId(null)
        if (locationRepository.mockLocationState.value == MockLocationState.RUNNING) {
            startUpdateLoop()
        }
        Log.i(TAG, "Replay cancelled; service remains active in TELEPORT mode")
    }

    /**
     * Only resets mode to TELEPORT if we are still in ROUTE_REPLAY, to avoid clobbering a mode
     * set by another subsystem while `cancelAndJoin` was suspended (e.g. a new walk-to started
     * immediately after a cancel was requested — see MapController.walkTo/walkViaRoads).
     */
    private fun resetModeIfStillReplaying() {
        if (locationRepository.currentMode.value == MockMode.ROUTE_REPLAY) {
            finishReplay()
        }
    }

    /** Shared teardown for a completed/reset replay: clear route waypoints, zero speed, go idle. */
    private fun finishReplay() {
        locationRepository.setRouteWaypoints(null)
        locationRepository.setRouteProgress(null)
        onSpeedChange(0f)
        locationRepository.setMockMode(MockMode.TELEPORT)
    }

    private fun publishProgress() {
        locationRepository.setRouteProgress(routeReplayEngine.currentProgress())
    }

    /**
     * Shared engine for both named-route and ephemeral replay.
     *
     * @param waypoints Ordered list of positions to replay (≥2).
     * @param speedMs Playback speed in m/s.
     * @param isLooping Whether to loop at the end.
     * @param followRoadsToStart When true, between-waypoint legs are already expanded;
     *   if [teleportToStart] is false, the walk to the first waypoint also follows roads.
     * @param teleportToStart When true, snap to the first waypoint instead of walking there.
     * @param persistMetadata If non-null, invoked before replay starts to persist route metadata.
     * @param onComplete Invoked on the service scope when the replay engine signals completion.
     */
    private suspend fun startReplayWithWaypoints(
        generation: Int,
        waypoints: List<LatLng>,
        speedMs: Double,
        isLooping: Boolean,
        followRoadsToStart: Boolean = false,
        teleportToStart: Boolean = false,
        boundaryIndices: List<Int>? = null,
        teleportBetweenWaypoints: Boolean = false,
        teleportBetweenDelaySeconds: Int = AppConstants.RouteConstants.TELEPORT_BETWEEN_DEFAULT_DELAY_SECONDS,
        persistMetadata: (suspend () -> Unit)? = null,
        onComplete: suspend () -> Unit = {
            finishReplay()
            locationRepository.emitCompletion("Route complete")
        },
    ) {
        if (!isStartStillCurrent(generation)) return
        if (locationRepository.currentMode.value == MockMode.ROAMING) roamingRepository.stopRoaming()
        if (waypoints.size < 2) return

        onSpeedChange(speedMs.toFloat())

        // Set mode BEFORE state so the state observer sees ROUTE_REPLAY and
        // correctly skips starting the background update loop.
        locationRepository.setMockMode(MockMode.ROUTE_REPLAY)
        persistMetadata?.invoke()
        if (!isStartStillCurrent(generation)) return
        // Trigger RUNNING after mode is set.
        onStateChange(MockLocationState.RUNNING)
        locationRepository.startSpoofing()

        if (!isStartStillCurrent(generation)) return
        if (teleportToStart) {
            tickPosition(waypoints.first())
        } else {
            walkToPosition(waypoints.first(), speedMs, followRoadsToStart)
        }

        if (!isStartStillCurrent(generation)) return
        routeReplayEngine.start(
            waypoints = waypoints,
            speedMs = speedMs,
            isLooping = isLooping,
            onPositionUpdate = ::tickPosition,
            onComplete = { scope.launch { onComplete() } },
            boundaryIndices = boundaryIndices,
            teleportBetweenWaypoints = teleportBetweenWaypoints,
            teleportBetweenDelaySeconds = teleportBetweenDelaySeconds,
        )
        publishProgress()

        // If pause was requested during walk-to-start (before engine launched),
        // ensure the engine is paused now that it has been initialized.
        if (locationRepository.mockLocationState.value == MockLocationState.PAUSED) routeReplayEngine.pause()
    }

    /**
     * Pre-plans a road-following path between the route's own saved waypoints. The
     * fallback-count/summary-report step is shared with
     * [com.locationjoystick.core.routing.RoamingEngine.planRoadFollowingRoute] via
     * [com.locationjoystick.core.routing.RoutingErrorReporter.reportRoadFollowingFallbacks];
     * the leg-resolution loop itself is intentionally separate — this one resolves a fixed
     * waypoint list known upfront, while `RoamingEngine`'s generates waypoints dynamically
     * within a distance budget (see that method's own doc comment).
     *
     * @return the expanded waypoint list plus the indices within it that are the route's
     *   real, named stops ("boundary indices"), so jump-to-waypoint keeps targeting real stops.
     */
    private suspend fun expandWaypointsForFollowRoads(waypoints: List<LatLng>): Pair<List<LatLng>, List<Int>> {
        val expanded = mutableListOf(waypoints.first())
        val boundaryIndices = mutableListOf(0)
        var fallbackCount = 0
        for (i in 0 until waypoints.size - 1) {
            val leg =
                osrmClient.resolveRoute(
                    OsrmClient.PROFILE_FOOT,
                    waypoints[i],
                    waypoints[i + 1],
                    followRoads = true,
                    onFallback = { fallbackCount++ },
                )
            expanded.addAll(leg.drop(1))
            boundaryIndices.add(expanded.size - 1)
        }
        routingErrorReporter.reportRoadFollowingFallbacks(fallbackCount, waypoints.size - 1)
        return expanded to boundaryIndices
    }

    /**
     * Builds a replay path of closed circles around each saved stop, without writing those
     * vertices back to the route. Circles stay geometric; when [followRoads] is true, only
     * the connectors between rings are resolved via OSRM (foot profile), matching paste
     * planting's "via roads" travel. When [teleportBetweenWaypoints] is true, connectors
     * are omitted: each ring is walked, then replay hops to the next ring start.
     */
    private suspend fun expandWaypointsForPlanting(
        centers: List<LatLng>,
        followRoads: Boolean,
        teleportBetweenWaypoints: Boolean = false,
    ): Pair<List<LatLng>, List<Int>> {
        val radius = AppConstants.RouteConstants.PLANTING_DEFAULT_RADIUS_METERS
        if (teleportBetweenWaypoints) {
            return stitchRingsWithoutConnectors(plantingRings(centers, radius))
        }
        if (!followRoads) return buildPlantingReplayPath(centers, radius)
        val rings = plantingRings(centers, radius)
        if (rings.isEmpty()) return emptyList<LatLng>() to emptyList()
        if (rings.size == 1) return rings[0] to listOf(0)
        var fallbackCount = 0
        val connectors =
            rings.zipWithNext { from, to ->
                osrmClient.resolveRoute(
                    OsrmClient.PROFILE_FOOT,
                    from.last(),
                    to.first(),
                    followRoads = true,
                    onFallback = { fallbackCount++ },
                )
            }
        routingErrorReporter.reportRoadFollowingFallbacks(fallbackCount, connectors.size)
        return stitchRingsWithConnectorsAndBoundaries(rings, connectors)
    }

    private suspend fun walkToPosition(
        target: LatLng,
        speedMs: Double,
        followRoads: Boolean = false,
    ) {
        val startPos = locationRepository.currentPosition.value ?: return
        if (!followRoads) {
            walkToEngine.walkToOnce(startPos, target, speedMs, ::tickPosition)
            return
        }
        val legs =
            osrmClient.resolveRoute(
                OsrmClient.PROFILE_FOOT,
                startPos,
                target,
                followRoads = true,
                onFallback = ::reportWalkToStartFallback,
            )
        for (i in 0 until legs.size - 1) {
            walkToEngine.walkToOnce(legs[i], legs[i + 1], speedMs, ::tickPosition)
        }
    }

    private fun reportWalkToStartFallback(reason: OsrmFailureReason) {
        routingErrorReporter.report("${osrmFailureMessage(reason)} — using straight walk to the route start")
    }
}
