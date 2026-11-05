package com.musicone

import com.musicone.source.sdk.SourceChildItem
import com.musicone.source.sdk.TrackIdentity
import org.junit.Assert.*
import org.junit.Test

class AiSupplementalToolsTest {
    private val children = listOf(
        SourceChildItem(TrackIdentity("bilibili", "account", "11"), 1, "第一部分", initiallySelected = true),
        SourceChildItem(TrackIdentity("bilibili", "account", "12"), 2, "第二部分"),
    )
    @Test fun ordinaryContentDoesNotRequireChildSelection() {
        assertTrue(aiSelectedSupplementalChildren(emptyList(), null).isEmpty())
        assertTrue(aiSelectedSupplementalChildren(emptyList(), emptyList()).isEmpty())
    }
    @Test fun multipartSelectionNeverSilentlyExpandsToAllParts() {
        assertTrue(runCatching { aiSelectedSupplementalChildren(children, null) }.isFailure)
        assertTrue(runCatching { aiSelectedSupplementalChildren(children, emptyList()) }.isFailure)
        assertEquals(listOf(children[1]), aiSelectedSupplementalChildren(children, listOf("12")))
        assertEquals(children, aiSelectedSupplementalChildren(children, listOf("11", "12")))
        assertTrue(runCatching { aiSelectedSupplementalChildren(children, listOf("foreign")) }.isFailure)
    }
    @Test fun supplementalToolsAreReadOnlyInRegeneration() {
        listOf("supplemental_collections", "supplemental_contents", "supplemental_content").forEach {
            assertTrue(aiResponseOnlyToolAllowed(it))
            assertTrue(aiToolSchema(AiConfiguration()).objects().any { tool -> tool.getJSONObject("function").getString("name") == it })
        }
    }
}
