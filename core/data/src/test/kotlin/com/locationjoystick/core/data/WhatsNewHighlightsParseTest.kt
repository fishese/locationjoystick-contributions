package com.locationjoystick.core.data

import com.locationjoystick.core.common.constants.AppConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WhatsNewHighlightsParseTest {
    @Test
    fun `parses a highlights array`() {
        val body = """{"version":"0.20.16","highlights":["First","Second"]}"""
        assertEquals(listOf("First", "Second"), parseWhatsNewHighlights(body))
    }

    @Test
    fun `empty highlights is missing`() {
        assertNull(parseWhatsNewHighlights("""{"version":"0.20.16","highlights":[]}"""))
    }

    @Test
    fun `invalid json is missing`() {
        assertNull(parseWhatsNewHighlights("not-json"))
    }

    @Test
    fun `wiki file for the current version has highlights`() {
        val version = AppConstants.AppInfo.VERSION_NAME.substringBefore("-")
        val file =
            listOf(
                File("docs/wiki/changelog/$version.json"),
                File("../../docs/wiki/changelog/$version.json"),
            ).firstOrNull { it.isFile }
        assertTrue("missing docs/wiki/changelog/$version.json", file != null)
        val highlights = parseWhatsNewHighlights(file!!.readText())
        assertTrue(!highlights.isNullOrEmpty())
    }
}
