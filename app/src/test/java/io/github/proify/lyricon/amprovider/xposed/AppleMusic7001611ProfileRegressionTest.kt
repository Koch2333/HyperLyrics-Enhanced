/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Original 1611 DEX descriptors, not JADX display names; APK checks run in AppleMusicProfileBinaryTest. */
class AppleMusic7001611ProfileRegressionTest {
    private val version = AppleMusicVersion("7.0.0-beta", 1611L)
    private val previous = AppleMusicVersion("7.0.0-beta", 1609L)
    private fun target(point: AppleMusicHookPoint, version: AppleMusicVersion = this.version) =
        AppleMusicHookProfiles.exactTargets(version, point).single()

    @Test
    fun `1611 has an isolated profile based on the verified 1609 map`() {
        assertEquals("am-7.0.0-beta-1611", AppleMusicHookProfiles.profileFor(version)?.id)
        assertEquals("am-7.0.0-beta-1609", AppleMusicHookProfiles.profileFor(previous)?.id)
        assertNull(AppleMusicHookProfiles.profileFor(AppleMusicVersion("7.0.0-beta", 1610L)))
        AppleMusicHookPoint.entries.forEach { point ->
            assertEquals(
                "Lost targets: $point",
                AppleMusicHookProfiles.exactTargets(previous, point).size,
                AppleMusicHookProfiles.exactTargets(version, point).size,
            )
        }
    }

    @Test
    fun `1611 class names never leak into known older builds`() {
        val renamed = mapOf(
            AppleMusicHookPoint.IN_APP_ACTION_SHEET_BINDING to "q8.q7",
            AppleMusicHookPoint.COMPOSE_NEVER_EQUAL_POLICY to "z0.v0",
            AppleMusicHookPoint.THEME_MODE_EMIT to "com.apple.android.music.E\$a",
        )
        listOf(
            previous,
            AppleMusicVersion("7.0.0-beta", 1607L),
            AppleMusicVersion("7.0.0-beta", 1606L),
            AppleMusicVersion("6.5.3", 1599L),
            AppleMusicVersion("6.5.2", 1586L),
        ).forEach { older ->
            renamed.forEach { (point, name) ->
                assertFalse("${older.displayName} $point", AppleMusicHookProfiles.exactTargets(older, point)
                    .any { it.className == name })
            }
        }
    }

    @Test
    fun `lyrics owners and binding fields use the 1611 binary names`() {
        val menu = target(AppleMusicHookPoint.LYRICS_SOURCE_MENU_CLICK_LISTENER)
        assertEquals("com.apple.android.music.player.fragment.b0", menu.className)
        listOf(
            AppleMusicHookPoint.LYRICS_TRANSLATION_PREFERENCE to "n",
            AppleMusicHookPoint.LYRICS_PRONUNCIATION_PREFERENCE to "m",
        ).forEach { (point, method) ->
            val preference = target(point)
            assertEquals("ja.i0", preference.className)
            assertEquals(method, preference.methodName)
            assertEquals(listOf("boolean"), preference.parameterTypeNames)
            assertEquals(true, preference.isStatic)
        }
        val ui = target(AppleMusicHookPoint.LYRICS_UI_ON_CREATE_VIEW)
        assertEquals("m0", ui.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_UI_BINDING_FIELD))
        assertEquals("e0", ui.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_UI_BINDING_RECYCLER_FIELD))
        assertEquals("o0", ui.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_UI_ADAPTER_FIELD))
        assertEquals("n1", ui.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_UI_VIEW_MODEL_FIELD))
        val song = target(AppleMusicHookPoint.PLAYER_SONG_BINDING_EXECUTE)
        assertEquals("m0", song.runtimeMemberName(AppleMusicRuntimeMember.PLAYER_SONG_BINDING_PLAYBACK_ITEM_FIELD))
        assertEquals("e0", song.runtimeMemberName(AppleMusicRuntimeMember.PLAYER_SONG_BINDING_LYRICS_BUTTON_FIELD))
    }

    @Test
    fun `catalogue executors and compose page hooks follow the 1611 signatures`() {
        val executors = AppleMusicHookProfiles.exactTargets(
            version,
            AppleMusicHookPoint.MEDIA_API_CATALOG_REQUEST_EXECUTOR,
        ).map { it.className to it.methodName }
        assertTrue(executors.containsAll(listOf("G5.p" to "a", "G5.p" to "b", "E1.T" to "e", "E1.T" to "f")))
        listOf(
            AppleMusicHookPoint.ALBUM_COMPOSE_CONTENT,
            AppleMusicHookPoint.ARTIST_COMPOSE_CONTENT,
            AppleMusicHookPoint.LIBRARY_COMPOSE_CONTENT,
        ).forEach { point ->
            assertEquals(listOf("z0.n", "int"), target(point).parameterTypeNames)
        }
        assertEquals("PageContent", target(AppleMusicHookPoint.ALBUM_COMPOSE_CONTENT).methodName)
        assertEquals(
            listOf("kotlin.Pair", "kotlin.Pair"),
            target(AppleMusicHookPoint.ALBUM_COMPOSE_TRACK_COMPARATOR).parameterTypeNames,
        )
        assertEquals("I0", target(AppleMusicHookPoint.ACTIVITY_THEME_CREATE)
            .runtimeMemberName(AppleMusicRuntimeMember.ACTIVITY_THEME_MODE_FIELD))
    }
}
