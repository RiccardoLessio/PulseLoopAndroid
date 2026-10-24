package com.pulseloop.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdaptiveControllerTest {
    @Test
    fun `episode decisions only use readings taken after the episode began`() {
        // WATCH: strictly after the reading that opened it, so it can't confirm itself.
        assertEquals(1_001L, AdaptiveController.evidenceSince(AdaptiveMemory(state = AdaptiveState.WATCH, watchStartedAt = 1_000L)))
        // ESCALATED: from the confirmation on, so a pre-episode stress value can't block recovery.
        assertEquals(2_000L, AdaptiveController.evidenceSince(AdaptiveMemory(state = AdaptiveState.ESCALATED, escalatedAt = 2_000L)))
        assertNull(AdaptiveController.evidenceSince(AdaptiveMemory(state = AdaptiveState.BASELINE)))
        assertNull(AdaptiveController.evidenceSince(AdaptiveMemory(state = AdaptiveState.COOLDOWN)))
    }
}
