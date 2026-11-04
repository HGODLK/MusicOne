package com.musicone.source.sdk

import org.junit.Assert.*
import org.junit.Test

class TrackAccessInfoCompatibilityTest {
    @Test fun originalConstructorsAndCopyEntryPointsRemainAvailable() {
        val type = TrackAccessInfo::class.java
        val original = arrayOf(TrackAccessBadge::class.java, Boolean::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!, String::class.java)
        assertNotNull(type.getConstructor(*original))
        assertNotNull(type.declaredConstructors.firstOrNull { constructor ->
            constructor.parameterTypes.map { it.name } == original.map { it.name } +
                listOf("int", "kotlin.jvm.internal.DefaultConstructorMarker")
        })
        assertNotNull(type.getMethod("copy", *original))
        assertNotNull(type.declaredMethods.firstOrNull { it.name == "copy\$default" && it.parameterCount == 7 })
        assertEquals(TrackAccessInfo(), TrackAccessInfo(null, false, true, null))
    }

    @Test fun originalCopyAndDestructuringPreserveNewDeclaration() {
        val badge = SourceTrackBadge("限免", lightTextArgb = 0xFF123456L, darkBackgroundArgb = 0L)
        val original = TrackAccessInfo(TrackAccessBadge.MEMBERSHIP, true, true, null, badge)
        val copied = original.copy(playable = false)
        val (semantic, trial, playable, reason) = copied
        assertEquals(TrackAccessBadge.MEMBERSHIP, semantic)
        assertTrue(trial)
        assertFalse(playable)
        assertNull(reason)
        assertEquals(badge, copied.displayBadge)
        assertNotEquals(original, copied)
    }
}
