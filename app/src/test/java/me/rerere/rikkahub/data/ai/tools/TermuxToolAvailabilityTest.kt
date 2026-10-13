package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxToolAvailabilityTest {
    @Test
    fun `Termux tools require both assistant connector and global integration`() {
        assertFalse(shouldExposeTermuxTools(assistantConnectorEnabled = false, integrationEnabled = false))
        assertFalse(shouldExposeTermuxTools(assistantConnectorEnabled = false, integrationEnabled = true))
        assertFalse(shouldExposeTermuxTools(assistantConnectorEnabled = true, integrationEnabled = false))
        assertTrue(shouldExposeTermuxTools(assistantConnectorEnabled = true, integrationEnabled = true))
    }

    @Test
    fun `direct execution requires global integration assistant connector and full authorization`() {
        assertTrue(shouldRunTermuxWithoutApproval(integrationEnabled = true, assistantConnectorEnabled = true, fullAuth = true))
        assertFalse(shouldRunTermuxWithoutApproval(integrationEnabled = false, assistantConnectorEnabled = true, fullAuth = true))
        assertFalse(shouldRunTermuxWithoutApproval(integrationEnabled = true, assistantConnectorEnabled = false, fullAuth = true))
        assertFalse(shouldRunTermuxWithoutApproval(integrationEnabled = true, assistantConnectorEnabled = true, fullAuth = false))
    }
}
