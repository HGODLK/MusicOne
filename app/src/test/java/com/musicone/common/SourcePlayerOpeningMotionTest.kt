package com.musicone

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.musicone.source.sdk.SourcePlayerOpening
import com.musicone.source.sdk.SourcePlayerSharedElement
import com.musicone.source.sdk.SourcePlayerSharedScale
import com.musicone.sourceintegration.scaleOf
import com.musicone.sourceintegration.sourcePlayerLerpRect
import com.musicone.sourceintegration.sourcePlayerSharedPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePlayerOpeningMotionTest {
    private val source = Rect(20f, 700f, 120f, 720f)
    private val target = Size(300f, 40f)

    @Test fun legacyConstructorKeepsWholePageFade() {
        val opening = SourcePlayerOpening(300, true, setOf(SourcePlayerSharedElement.TITLE))
        assertTrue(opening.pageFade)
        assertFalse(opening.sharedOnClose)
        assertEquals(0f, opening.artworkCornerDp)
    }

    @Test fun textDefaultsToUniformScaleAndOthersStretch() {
        val opening = SourcePlayerOpening(300, true, emptySet())
        assertEquals(SourcePlayerSharedScale.UNIFORM, opening.scaleOf(SourcePlayerSharedElement.TITLE))
        assertEquals(SourcePlayerSharedScale.UNIFORM, opening.scaleOf(SourcePlayerSharedElement.ARTIST))
        assertEquals(SourcePlayerSharedScale.STRETCH, opening.scaleOf(SourcePlayerSharedElement.ARTWORK))
        val declared = opening.copy(sharedScale = mapOf(SourcePlayerSharedElement.TITLE to SourcePlayerSharedScale.STRETCH))
        assertEquals(SourcePlayerSharedScale.STRETCH, declared.scaleOf(SourcePlayerSharedElement.TITLE))
    }

    @Test fun uniformTextStartsAtBarHeightAndWidthWithoutStretching() {
        val pose = sourcePlayerSharedPose(source, 0f, Offset(40f, 100f), target, 0, 0f,
            SourcePlayerSharedScale.UNIFORM, null)
        assertEquals(.5f, pose.scaleX, 1e-4f)
        assertEquals(pose.scaleX, pose.scaleY, 1e-4f)
        // 起点本地裁剪宽度 × 缩放 = 迷你条文字宽度。
        assertEquals(100f, pose.clipWidth!! * pose.scaleX, 1e-3f)
        assertEquals(Offset(-20f, 600f), pose.translation)
    }

    @Test fun uniformTextSettlesWithoutClipOrTransform() {
        val pose = sourcePlayerSharedPose(source, 0f, Offset(40f, 100f), target, 0, 1f,
            SourcePlayerSharedScale.UNIFORM, null)
        assertEquals(1f, pose.scaleX, 1e-4f)
        assertNull(pose.clipWidth)
        assertEquals(0f, pose.translation.x, 1e-4f)
        assertEquals(0f, pose.translation.y, 1e-4f)
    }

    @Test fun stretchKeepsSeparateAxes() {
        val pose = sourcePlayerSharedPose(source, 0f, Offset.Zero, target, 0, 0f,
            SourcePlayerSharedScale.STRETCH, null)
        assertEquals(100f / 300f, pose.scaleX, 1e-4f)
        assertEquals(.5f, pose.scaleY, 1e-4f)
        assertNull(pose.clipWidth)
    }

    @Test fun artworkCornerInterpolatesInScreenSpace() {
        val square = Rect(0f, 0f, 48f, 48f)
        val start = sourcePlayerSharedPose(square, 16f, Offset.Zero, Size(480f, 480f), 0, 0f,
            SourcePlayerSharedScale.STRETCH, 0f)
        // 屏幕上 16px 的源端圆角，换算到 0.1 倍缩放的本地坐标是 160。
        assertEquals(160f, start.cornerPx, 1e-3f)
        val end = sourcePlayerSharedPose(square, 16f, Offset.Zero, Size(480f, 480f), 0, 1f,
            SourcePlayerSharedScale.STRETCH, 24f)
        assertEquals(24f, end.cornerPx, 1e-3f)
    }

    @Test fun landscapeRotatesBackToPhysicalAtStart() {
        val pose = sourcePlayerSharedPose(source, 0f, Offset.Zero, target, 90, 0f,
            SourcePlayerSharedScale.STRETCH, null)
        assertEquals(-90f, pose.rotation)
        assertEquals(Offset(700f, -20f), pose.translation)
    }

    @Test fun revealRectInterpolatesLinearlySoSharedElementsStayInside() {
        val bar = Rect(10f, 700f, 390f, 760f)
        val page = Rect(0f, 0f, 400f, 800f)
        assertEquals(bar, sourcePlayerLerpRect(bar, page, 0f))
        assertEquals(Rect(5f, 350f, 395f, 780f), sourcePlayerLerpRect(bar, page, .5f))
    }

    @Test fun barHidesDeclaredElementsWhileOpeningOnly() {
        val handoff = SourcePlayerBarHandoff(setOf("cover"), onClose = false)
        val motion = PageMotion(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), true, 300, 300)
        // 静止时不隐藏。
        assertFalse(handoff.hides("cover", motion))
        assertFalse(handoff.hides("title", motion))
    }
}
