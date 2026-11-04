package com.musicone.sourceintegration

import androidx.core.view.accessibility.AccessibilityNodeProviderCompat
import org.junit.Assert.*
import org.junit.Test

class SourcePlayerInputBoundaryTest {
    @Test fun successfulInputKeepsItsResultAndFailureKeepsItsOriginalCause() {
        val failures = mutableListOf<Throwable>()
        val boundary = SourcePlayerInputBoundary(failures::add)
        assertTrue(boundary.call(false) { true })
        val error = IllegalStateException("旧歌曲动作已失效")
        assertFalse(boundary.call(false) { throw error })
        assertSame(error, failures.single())
    }

    @Test fun virtualAccessibilityClickForwardsActionAndContainsPackageClickFailure() {
        val failures = mutableListOf<Throwable>()
        var interactive = true
        var calls = 0
        var throwFromClick = false
        val error = IllegalStateException("包内点击失败")
        val original = object : AccessibilityNodeProviderCompat() {
            override fun performAction(virtualViewId: Int, action: Int, arguments: android.os.Bundle?): Boolean {
                assertEquals(17, virtualViewId)
                assertEquals(16, action)
                calls++
                if (throwFromClick) throw error
                return true
            }
        }
        val guarded = SourcePlayerAccessibilityBoundary(SourcePlayerInputBoundary(failures::add)) { interactive }
            .guardProvider(original)
        assertTrue(guarded.performAction(17, 16, null))
        throwFromClick = true
        assertFalse(guarded.performAction(17, 16, null))
        assertSame(error, failures.single())
        interactive = false
        assertFalse(guarded.performAction(17, 16, null))
        assertEquals(2, calls)
    }
}
