package com.locationjoystick.core.data

import android.content.Context
import com.locationjoystick.core.common.constants.AppConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the current version's "what's new" highlights from the APK assets, which
 * are the same `docs/wiki/changelog/<version>.json` files (see docs/features/whats-new.md).
 */
@Singleton
class WhatsNewRepository
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        suspend fun fetchHighlights(version: String): List<String>? =
            withContext(Dispatchers.IO) {
                runCatching {
                    val name = AppConstants.WhatsNewConstants.assetFileName(version)
                    val body =
                        context.assets
                            .open(name)
                            .bufferedReader()
                            .use { it.readText() }
                    parseWhatsNewHighlights(body)
                }.getOrNull()
            }
    }

internal fun parseWhatsNewHighlights(body: String): List<String>? =
    runCatching {
        val highlights = JSONObject(body).getJSONArray("highlights")
        List(highlights.length()) { i -> highlights.getString(i) }.takeIf { it.isNotEmpty() }
    }.getOrNull()
