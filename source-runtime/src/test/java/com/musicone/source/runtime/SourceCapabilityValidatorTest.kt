package com.musicone.source.runtime

import androidx.compose.runtime.Composable
import com.musicone.source.sdk.*
import org.junit.Assert.assertThrows
import org.junit.Test

class SourceCapabilityValidatorTest {
    private open class Entry(capability: SourceCapability, login: Boolean = false) : SourceEntry {
        override val descriptor = SourceDescriptor("org.example.validation", "能力测试", SourceKind.MAIN,
            1, "1", setOf(capability), useCustomPages = true, requiresLogin = login)
        override val implementedCapabilities = descriptor.capabilities
        override fun initialize(environment: SourceEnvironment) {}
        override fun close() {}
        @Composable override fun Content(route: SourceRoute) {}
    }
    @Test fun selfReportedSearchWithoutRealInterfaceIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { SourceCapabilityValidator.check(Entry(SourceCapability.SEARCH)) }
    }
    @Test fun loginRequirementWithoutAuthenticationInterfaceIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { SourceCapabilityValidator.check(Entry(SourceCapability.HOME, login = true)) }
    }
    @Test fun legacyCustomHomeEntryRemainsCompatible() {
        SourceCapabilityValidator.check(Entry(SourceCapability.HOME))
    }
    @Test fun optionalVisualAbiFailureIsFoundBeforeTheEntryReachesHostUi() {
        val entry = object : Entry(SourceCapability.HOME) {
            override val visuals: SourceVisuals? get() = throw NoClassDefFoundError("missing shared interface")
        }
        assertThrows(NoClassDefFoundError::class.java) { SourceCapabilityValidator.check(entry) }
    }
}
