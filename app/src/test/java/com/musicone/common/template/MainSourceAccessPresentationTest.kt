package com.musicone

import com.musicone.source.sdk.*
import androidx.compose.runtime.Composable
import org.junit.Assert.*
import org.junit.Test

class MainSourceAccessPresentationTest {
    @Test fun kugouPresentationReadsExplicitNonMemberWithoutChangingTheSdkContract() {
        val entry = object : SourceEntry, SourceMembershipProvider {
            override val descriptor = SourceDescriptor(MusicSource.KUGOU.id, "酷狗", SourceKind.MAIN, 1, "1", emptySet())
            override val implementedCapabilities = emptySet<SourceCapability>()
            override val membershipDetector = object : SourceMembershipDetector {
                override val currentMembership = false
                override suspend fun detectMembership(): Boolean? = error("同步角标读取不能联网")
            }
            override fun initialize(environment: SourceEnvironment) {}
            override fun close() {}
            @Composable override fun Content(route: SourceRoute) {}
        }
        assertNull(entry.currentMembership())
        assertEquals(false, sourceAccessMembership(entry, MusicSource.KUGOU))
        assertNull(sourceAccessMembership(entry, MusicSource.QQ))
    }

    @Test fun onlyConfirmedNonMemberGetsVipLabelOnFirstFrame() {
        val access = TrackAccessInfo(TrackAccessBadge.MEMBERSHIP, trialAvailable = true)
        assertNull(mainSourceAccessPresentation(MusicSource.KUGOU, access, null))
        assertNull(mainSourceAccessPresentation(MusicSource.KUGOU, access, true))
        assertEquals("vip", mainSourceAccessPresentation(MusicSource.KUGOU, access, false)!!.text)
    }

    @Test fun purchaseRestrictionsAndOtherSourcesKeepTheirExistingPresentation() {
        val purchase = TrackAccessInfo(TrackAccessBadge.PURCHASE)
        assertEquals("付费", mainSourceAccessPresentation(MusicSource.KUGOU, purchase, true)!!.text)
        val vip = TrackAccessInfo(TrackAccessBadge.MEMBERSHIP)
        assertEquals("vip", mainSourceAccessPresentation(MusicSource.QQ, vip, null)!!.text)
    }
}
