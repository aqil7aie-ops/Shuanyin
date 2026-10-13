package me.rerere.rikkahub.ui.pages.setting

import org.junit.Assert.assertEquals
import org.junit.Test

class TermuxExternalAppsStatusTest {
    @Test
    fun `external access is ready only after a successful command probe`() {
        assertEquals(
            TermuxExternalAppsStatus.READY,
            resolveTermuxExternalAppsStatus(
                termuxInstalled = true,
                runCommandPermissionGranted = true,
                commandExitCode = 0,
            ),
        )
    }

    @Test
    fun `absence of Termux or Android permission cannot be reported as ready`() {
        assertEquals(
            TermuxExternalAppsStatus.NOT_INSTALLED,
            resolveTermuxExternalAppsStatus(
                termuxInstalled = false,
                runCommandPermissionGranted = true,
                commandExitCode = 0,
            ),
        )
        assertEquals(
            TermuxExternalAppsStatus.RUN_COMMAND_PERMISSION_REQUIRED,
            resolveTermuxExternalAppsStatus(
                termuxInstalled = true,
                runCommandPermissionGranted = false,
                commandExitCode = 0,
            ),
        )
    }

    @Test
    fun `a failed shell probe is distinct from a missing property`() {
        assertEquals(
            TermuxExternalAppsStatus.ALLOW_EXTERNAL_APPS_MISSING,
            resolveTermuxExternalAppsStatus(
                termuxInstalled = true,
                runCommandPermissionGranted = true,
                commandExitCode = 1,
            ),
        )
        assertEquals(
            TermuxExternalAppsStatus.CHECK_FAILED,
            resolveTermuxExternalAppsStatus(
                termuxInstalled = true,
                runCommandPermissionGranted = true,
                commandExitCode = null,
            ),
        )
    }
}
