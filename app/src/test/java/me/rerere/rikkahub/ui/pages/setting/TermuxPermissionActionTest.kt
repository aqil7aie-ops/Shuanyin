package me.rerere.rikkahub.ui.pages.setting

import org.junit.Assert.assertEquals
import org.junit.Test

class TermuxPermissionActionTest {
    @Test
    fun `missing permission starts with runtime request`() {
        assertEquals(
            TermuxPermissionAction.REQUEST,
            resolveTermuxPermissionAction(
                termuxInstalled = true,
                permissionGranted = false,
                permissionRequestDenied = false,
            ),
        )
    }

    @Test
    fun `denied request routes user to app permission settings`() {
        assertEquals(
            TermuxPermissionAction.OPEN_APP_SETTINGS,
            resolveTermuxPermissionAction(
                termuxInstalled = true,
                permissionGranted = false,
                permissionRequestDenied = true,
            ),
        )
    }

    @Test
    fun `permission action is hidden when Termux is absent or permission is granted`() {
        assertEquals(
            TermuxPermissionAction.HIDDEN,
            resolveTermuxPermissionAction(
                termuxInstalled = false,
                permissionGranted = false,
                permissionRequestDenied = false,
            ),
        )
        assertEquals(
            TermuxPermissionAction.HIDDEN,
            resolveTermuxPermissionAction(
                termuxInstalled = true,
                permissionGranted = true,
                permissionRequestDenied = true,
            ),
        )
    }
}
