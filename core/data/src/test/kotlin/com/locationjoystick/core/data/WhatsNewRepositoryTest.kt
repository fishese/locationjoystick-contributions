package com.locationjoystick.core.data

import android.content.Context
import android.content.res.AssetManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.FileNotFoundException

class WhatsNewRepositoryTest {
    @Test
    fun `reads highlights from the version asset`() =
        runTest {
            val json = """{"version":"0.20.16","highlights":["Packed"]}"""
            val assets = mockk<AssetManager>()
            every { assets.open("0.20.16.json") } returns json.byteInputStream()
            val context = mockk<Context>()
            every { context.assets } returns assets
            val repository = WhatsNewRepository(context)
            assertEquals(listOf("Packed"), repository.fetchHighlights("0.20.16"))
        }

    @Test
    fun `missing asset is a failed load`() =
        runTest {
            val assets = mockk<AssetManager>()
            every { assets.open(any()) } throws FileNotFoundException("missing")
            val context = mockk<Context>()
            every { context.assets } returns assets
            val repository = WhatsNewRepository(context)
            assertNull(repository.fetchHighlights("0.20.16"))
        }
}
