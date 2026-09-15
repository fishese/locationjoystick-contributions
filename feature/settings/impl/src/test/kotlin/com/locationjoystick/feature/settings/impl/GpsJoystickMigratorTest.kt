package com.locationjoystick.feature.settings.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsJoystickMigratorTest {
    private fun gpx(body: String): ByteArray =
        """<?xml version="1.0" encoding="UTF-8"?><gpx version="1.1" creator="GPS JoyStick">$body</gpx>"""
            .toByteArray()

    @Test
    fun `returns failure on empty bytes`() {
        assertTrue(GpsJoystickMigrator.parse(ByteArray(0)).isFailure)
    }

    @Test
    fun `returns failure on realm db bytes with a gpx hint`() {
        val header = ByteArray(20)
        header[16] = 'T'.code.toByte()
        header[17] = '-'.code.toByte()
        header[18] = 'D'.code.toByte()
        header[19] = 'B'.code.toByte()
        val result = GpsJoystickMigrator.parse(header)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("GPX"))
    }

    @Test
    fun `named waypoints become favorites`() {
        val bytes =
            gpx(
                """
                <wpt lat="35.396786343408024" lon="132.7929809694346"><name>Izumo</name></wpt>
                <wpt lat="56.33725496274786" lon="-2.7973317589842477"><name>St Andrew</name></wpt>
                """.trimIndent(),
            )
        val m = GpsJoystickMigrator.parse(bytes).getOrThrow()
        assertEquals(2, m.favorites.size)
        assertEquals("Izumo", m.favorites[0].name)
        assertEquals(35.396786343408024, m.favorites[0].position.latitude, 1e-12)
        assertEquals(132.7929809694346, m.favorites[0].position.longitude, 1e-12)
        assertEquals("St Andrew", m.favorites[1].name)
        assertTrue(m.routes.isEmpty())
        assertEquals(0, m.skippedOversizedRouteCount)
    }

    @Test
    fun `unnamed waypoint falls back to Favorite N`() {
        val bytes = gpx("""<wpt lat="1.0" lon="2.0"/>""")
        val m = GpsJoystickMigrator.parse(bytes).getOrThrow()
        assertEquals("Favorite 1", m.favorites.single().name)
    }

    @Test
    fun `each rte is a named route`() {
        val bytes =
            gpx(
                """
                <rte>
                  <name>Walk</name>
                  <rtept lat="48.85" lon="2.35"/>
                  <rtept lat="48.86" lon="2.36"/>
                </rte>
                <rte>
                  <name>Paris Jardins</name>
                  <rtept lat="48.86" lon="2.33"/>
                  <rtept lat="48.87" lon="2.34"/>
                  <rtept lat="48.88" lon="2.35"/>
                </rte>
                """.trimIndent(),
            )
        val m = GpsJoystickMigrator.parse(bytes).getOrThrow()
        assertEquals(2, m.routes.size)
        assertEquals("Walk", m.routes[0].name)
        assertEquals(2, m.routes[0].waypoints.size)
        assertEquals("Paris Jardins", m.routes[1].name)
        assertEquals(3, m.routes[1].waypoints.size)
    }

    @Test
    fun `trk segments import as routes`() {
        val bytes =
            gpx(
                """
                <trk>
                  <name>Track Name</name>
                  <trkseg>
                    <trkpt lat="48.8566" lon="2.3522"/>
                    <trkpt lat="48.8580" lon="2.3535"/>
                  </trkseg>
                </trk>
                """.trimIndent(),
            )
        val m = GpsJoystickMigrator.parse(bytes).getOrThrow()
        assertEquals("Track Name", m.routes.single().name)
        assertEquals(
            2,
            m.routes
                .single()
                .waypoints.size,
        )
    }

    @Test
    fun `oversized route is skipped and counted`() {
        val points =
            (0..GpsJoystickMigrator.MAX_IMPORTABLE_WAYPOINTS)
                .joinToString("") { i ->
                    """<rtept lat="${48.0 + i * 0.0001}" lon="2.35"/>"""
                }
        val bytes =
            gpx(
                """
                <wpt lat="35.4" lon="132.8"><name>Izumo</name></wpt>
                <rte><name>Tiny</name><rtept lat="1.0" lon="2.0"/><rtept lat="1.1" lon="2.1"/></rte>
                <rte><name>Huge</name>$points</rte>
                """.trimIndent(),
            )
        val m = GpsJoystickMigrator.parse(bytes).getOrThrow()
        assertEquals(listOf("Izumo"), m.favorites.map { it.name })
        assertEquals(1, m.routes.size)
        assertEquals("Tiny", m.routes[0].name)
        assertEquals(1, m.skippedOversizedRouteCount)
        assertTrue(m.toImportMessage().contains("Skipped 1 route that was too large"))
    }

    @Test
    fun `import message omits skip clause when nothing was dropped`() {
        val bytes = gpx("""<wpt lat="1.0" lon="2.0"><name>Home</name></wpt>""")
        val m = GpsJoystickMigrator.parse(bytes).getOrThrow()
        assertEquals("Imported 1 favorites, 0 routes from GPS Joystick", m.toImportMessage())
    }

    @Test
    fun `self-closing rtept points parse`() {
        val bytes =
            gpx(
                """<rte><name>Self close</name><rtept lat="10.0" lon="20.0"/><rtept lat="11.0" lon="21.0"/></rte>""",
            )
        val m = GpsJoystickMigrator.parse(bytes).getOrThrow()
        assertEquals(
            2,
            m.routes
                .single()
                .waypoints.size,
        )
        assertEquals(
            10.0,
            m.routes
                .single()
                .waypoints[0]
                .position.latitude,
            0.0,
        )
    }

    @Test
    fun `local GPS Joystick GPX export matches screenshot names if present`() {
        val file =
            listOf(
                java.io.File("temp/gpsjoystick_all.gpx"),
                java.io.File("../../../../temp/gpsjoystick_all.gpx"),
            ).firstOrNull { it.isFile }
        org.junit.Assume.assumeTrue("local GPS Joystick GPX not present", file != null)
        val m = GpsJoystickMigrator.parse(file!!.readBytes()).getOrThrow()
        assertEquals("Izumo", m.favorites.first().name)
        assertEquals("St Andrew", m.favorites[10].name)
        assertEquals(138, m.favorites.size)
        assertEquals(166, m.routes.size)
        assertEquals(7, m.skippedOversizedRouteCount)
        assertEquals(35.396786343408024, m.favorites[0].position.latitude, 1e-12)
        assertEquals(132.7929809694346, m.favorites[0].position.longitude, 1e-12)
    }
}
