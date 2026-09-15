package com.locationjoystick.feature.settings.impl

import android.util.Log
import com.locationjoystick.core.common.constants.AppConstants
import com.locationjoystick.core.model.FavoriteLocation
import com.locationjoystick.core.model.LatLng
import com.locationjoystick.core.model.Route
import com.locationjoystick.core.model.RouteType
import com.locationjoystick.core.model.Waypoint
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parses a GPS Joystick **GPX** export. Favorites are `<wpt>` elements (name + lat/lon);
 * routes are `<rte>` / `<trk>` elements. Database (`.db`) backups are not supported — GPS
 * Joystick's Realm file does not keep names next to coordinates in a form this app can
 * recover reliably.
 *
 * Routes with more than [MAX_IMPORTABLE_WAYPOINTS] points are skipped and counted on
 * [MigrationResult.skippedOversizedRouteCount] so the user is told instead of silently
 * losing them.
 */
internal object GpsJoystickMigrator {
    private const val TAG = "GpsJoystickMigrator"

    /** Huge GPS Joystick tracks (thousands of points) are dropped rather than imported. */
    const val MAX_IMPORTABLE_WAYPOINTS = AppConstants.ExportConstants.MAX_GPX_ROUTE_WAYPOINTS

    fun parse(bytes: ByteArray): Result<MigrationResult> =
        runCatching {
            val text = bytes.toString(Charsets.UTF_8)
            if (!looksLikeGpx(text)) {
                return Result.failure(
                    IllegalArgumentException(
                        "Not a GPS Joystick GPX export. In GPS Joystick, export as GPX (not a .db backup).",
                    ),
                )
            }
            val doc =
                DocumentBuilderFactory
                    .newInstance()
                    .newDocumentBuilder()
                    .parse(bytes.inputStream())
            val root = doc.documentElement ?: error("Empty GPX file")
            val favorites = collectWaypoints(root)
            val importedRoutes = mutableListOf<Route>()
            var skipped = 0
            collectNamedTracks(root, "rte", "rtept").forEach { (name, points) ->
                when {
                    points.isEmpty() -> Unit
                    points.size > MAX_IMPORTABLE_WAYPOINTS -> skipped++
                    else -> importedRoutes.add(toRoute(name, points, importedRoutes.size))
                }
            }
            collectNamedTracks(root, "trk", "trkpt").forEach { (name, points) ->
                when {
                    points.isEmpty() -> Unit
                    points.size > MAX_IMPORTABLE_WAYPOINTS -> skipped++
                    else -> importedRoutes.add(toRoute(name, points, importedRoutes.size))
                }
            }
            MigrationResult(
                favorites = favorites,
                routes = importedRoutes,
                skippedOversizedRouteCount = skipped,
            )
        }.onFailure { e ->
            Log.e(TAG, "Failed to parse GPS Joystick GPX", e)
        }

    private fun looksLikeGpx(text: String): Boolean {
        val head = text.take(512)
        return head.contains("<gpx", ignoreCase = true)
    }

    private fun collectWaypoints(root: Element): List<FavoriteLocation> {
        val nodes = root.getElementsByTagName("wpt")
        val now = System.currentTimeMillis()
        val result = mutableListOf<FavoriteLocation>()
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            val lat = el.getAttribute("lat").toDoubleOrNull() ?: continue
            val lon = el.getAttribute("lon").toDoubleOrNull() ?: continue
            val name = directChildText(el, "name")?.takeIf { it.isNotBlank() } ?: "Favorite ${result.size + 1}"
            result.add(
                FavoriteLocation(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    position = LatLng(latitude = lat, longitude = lon),
                    createdAt = now,
                ),
            )
        }
        return result
    }

    private fun collectNamedTracks(
        root: Element,
        segmentTag: String,
        pointTag: String,
    ): List<Pair<String?, List<LatLng>>> {
        val nodes = root.getElementsByTagName(segmentTag)
        return (0 until nodes.length).map { i ->
            val el = nodes.item(i) as Element
            val name = directChildText(el, "name")
            name to collectPoints(el, pointTag)
        }
    }

    private fun collectPoints(
        element: Element,
        tagName: String,
    ): List<LatLng> {
        val nodes = element.getElementsByTagName(tagName)
        val points = mutableListOf<LatLng>()
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            val lat =
                node.attributes
                    ?.getNamedItem("lat")
                    ?.nodeValue
                    ?.toDoubleOrNull() ?: continue
            val lon =
                node.attributes
                    ?.getNamedItem("lon")
                    ?.nodeValue
                    ?.toDoubleOrNull() ?: continue
            points.add(LatLng(lat, lon))
        }
        return points
    }

    private fun directChildText(
        parent: Element,
        tag: String,
    ): String? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE && node.nodeName == tag) {
                return node.textContent
            }
        }
        return null
    }

    private fun toRoute(
        name: String?,
        points: List<LatLng>,
        index: Int,
    ): Route {
        val waypoints =
            points.mapIndexed { i, pos ->
                Waypoint(
                    id = UUID.randomUUID().toString(),
                    position = pos,
                    orderIndex = i,
                )
            }
        val resolved =
            name?.takeIf { it.isNotBlank() }
                ?: if (index == 0) "Route 1" else "Route ${index + 1}"
        val now = System.currentTimeMillis()
        return Route(
            id = UUID.randomUUID().toString(),
            name = resolved,
            waypoints = waypoints,
            isLooping = false,
            routeType = RouteType.STRAIGHT,
            createdAt = now,
            updatedAt = now,
        )
    }
}
