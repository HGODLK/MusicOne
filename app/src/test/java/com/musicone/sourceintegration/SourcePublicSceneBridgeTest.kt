package com.musicone.sourceintegration

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.musicone.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

class SourcePublicSceneBridgeTest {
    private class Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val settings = SettingsNavigation()
        fun motion(open: Boolean = false) = PageMotion(scope, open, 320, 280)
        val bridge = SourcePublicSceneBridge(settings, motion(), motion(), BottomBarMeasurements())
        fun scene(open: Boolean = false, root: SourceRootIdentity? = null) = SourcePublicScene(
            RecommendationNavigation(mutableStateOf(null), motion(open), PlaylistCardTransition(scope)),
            EntityNavigation(scope), MainSourceSearchMotion(), rootIdentity = root)
    }

    @Test fun offscreenSceneCannotReplaceActiveCallerAndTopPageTakesPriority() {
        val fixture = Fixture()
        try {
            val embedded = fixture.scene()
            val offscreen = fixture.scene()
            val overlay = fixture.scene()
            fixture.bridge.update(embedded, 1)
            fixture.bridge.update(offscreen, 0)
            assertSame(embedded, fixture.bridge.active)
            fixture.bridge.update(overlay, 2)
            assertSame(overlay, fixture.bridge.active)
            fixture.bridge.remove(offscreen)
            assertSame(overlay, fixture.bridge.active)
            fixture.bridge.remove(overlay)
            assertSame(embedded, fixture.bridge.active)
        } finally { fixture.scope.cancel() }
    }

    @Test fun sourceChangeDoesNotDismissRootSettingsAndOldOwnerCannotReopenIt() {
        val fixture = Fixture()
        try {
            val previous = fixture.scene()
            val next = fixture.scene()
            fixture.bridge.update(previous, 1)
            val origin = Rect(10f, 20f, 40f, 50f)
            fixture.bridge.openSettings(previous, origin, Offset(3f, 24f))
            assertEquals(origin.translate(Offset(3f, 24f)), fixture.settings.origin)
            assertEquals(SettingsDestination.SETTINGS, fixture.settings.destination)
            fixture.bridge.remove(previous)
            fixture.bridge.update(next, 1)
            assertEquals(SettingsDestination.SETTINGS, fixture.settings.destination)
            fixture.settings.back()
            assertEquals(SettingsDestination.CLOSED, fixture.settings.destination)
            fixture.bridge.openSettings(previous, origin, Offset.Zero)
            assertEquals(SettingsDestination.CLOSED, fixture.settings.destination)
            fixture.bridge.openSettings(next, origin, Offset.Zero)
            assertEquals(SettingsDestination.SETTINGS, fixture.settings.destination)
        } finally { fixture.scope.cancel() }
    }

    @Test fun newlyActivatedSurfaceWinsWhileOldRecompositionCannotTakeItBack() {
        val fixture = Fixture()
        try {
            val previous = fixture.scene()
            val next = fixture.scene()
            fixture.bridge.update(previous, 1)
            fixture.bridge.update(next, 1)
            fixture.bridge.update(previous, 1)
            assertSame(next, fixture.bridge.active)
            fixture.bridge.update(previous, 0)
            fixture.bridge.update(previous, 1)
            assertSame(previous, fixture.bridge.active)
        } finally { fixture.scope.cancel() }
    }

    @Test fun originalMountedMotionIsTheSceneDetailState() {
        val fixture = Fixture()
        try {
            assertFalse(fixture.scene().detailMounted)
            val detail = fixture.scene(open = true)
            fixture.bridge.update(detail, 1)
            assertTrue(fixture.bridge.active!!.detailMounted)
            assertSame(detail.playlists.motion, fixture.bridge.active!!.playlists.motion)
        } finally { fixture.scope.cancel() }
    }

    @Test fun rootHeaderSurvivesBothOrdersOfDelayedPageActivation() {
        val fixture = Fixture()
        try {
            val home = SourceRootIdentity(MusicSource.KUGOU.id, MusicOnePage.HOME)
            val my = home.copy(page = MusicOnePage.MY)
            val scene = fixture.scene(root = my).apply {
                rootHeader = SourcePublicRootHeader({}, {}, {})
            }
            fixture.bridge.selectedRoot = home
            fixture.bridge.update(scene, 0)
            fixture.bridge.update(scene, 1)
            assertNull(fixture.bridge.active)
            assertTrue(sourcePrimaryHeaderVisible(true, false, false, true, fixture.bridge.active))
            fixture.bridge.selectedRoot = my
            assertSame(scene, fixture.bridge.active)
            fixture.bridge.update(scene, 0)
            assertSame(scene, fixture.bridge.active)
            assertTrue(sourcePrimaryHeaderVisible(true, false, false, true, fixture.bridge.active))
            fixture.bridge.selectedRoot = home
            assertNull(fixture.bridge.active)
            assertTrue(sourcePrimaryHeaderVisible(true, false, false, true, fixture.bridge.active))
            // 非酷狗自定义页仍遵守原 atRoot 边界。
            assertFalse(sourcePrimaryHeaderVisible(true, false, false, false, null))
        } finally { fixture.scope.cancel() }
    }

    @Test fun rootSceneKeepsRootBackdropUntilAnActualDetailOpens() {
        val fixture = Fixture()
        try {
            val root = SourceRootIdentity(MusicSource.KUGOU.id, MusicOnePage.MY)
            val scene = fixture.scene(root = root).apply { rootHeader = SourcePublicRootHeader({}, {}, {}) }
            assertFalse(scene.overlayBackdrop)
            val detail = fixture.scene(open = true, root = root).apply { rootHeader = scene.rootHeader }
            assertTrue(detail.overlayBackdrop)
            assertTrue(fixture.scene(root = root).overlayBackdrop)
            assertTrue(fixture.scene().overlayBackdrop)
        } finally { fixture.scope.cancel() }
    }

    @Test fun overlayAndSourceChangeCannotReviveAnOldRootScene() {
        val fixture = Fixture()
        try {
            val root = SourceRootIdentity(MusicSource.KUGOU.id, MusicOnePage.MY)
            val embedded = fixture.scene(root = root)
            val overlay = fixture.scene()
            fixture.bridge.selectedRoot = root
            fixture.bridge.update(embedded, 0)
            fixture.bridge.update(overlay, 2)
            assertSame(overlay, fixture.bridge.active)
            fixture.bridge.remove(overlay)
            assertSame(embedded, fixture.bridge.active)
            fixture.bridge.selectedRoot = null
            fixture.bridge.update(embedded, 1)
            assertNull(fixture.bridge.active)
        } finally { fixture.scope.cancel() }
    }

    @Test fun delayedRootActivationStillUsesTheOriginalSettingsAnchor() {
        val fixture = Fixture()
        try {
            val root = SourceRootIdentity(MusicSource.KUGOU.id, MusicOnePage.MY)
            val scene = fixture.scene(root = root)
            fixture.bridge.update(scene, 0)
            fixture.bridge.selectedRoot = root
            val local = Rect(10f, 20f, 50f, 60f)
            val offset = Offset(-120f, 24f)
            fixture.bridge.openSettings(scene, local, offset)
            assertEquals(local.translate(offset), fixture.settings.origin)
            assertEquals(SettingsDestination.SETTINGS, fixture.settings.destination)
        } finally { fixture.scope.cancel() }
    }
}
