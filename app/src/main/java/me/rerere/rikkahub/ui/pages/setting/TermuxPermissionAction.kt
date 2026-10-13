package me.rerere.rikkahub.ui.pages.setting

internal enum class TermuxPermissionAction {
    HIDDEN,
    REQUEST,
    OPEN_APP_SETTINGS,
}

internal fun resolveTermuxPermissionAction(
    termuxInstalled: Boolean,
    permissionGranted: Boolean,
    permissionRequestDenied: Boolean,
): TermuxPermissionAction = when {
    !termuxInstalled || permissionGranted -> TermuxPermissionAction.HIDDEN
    permissionRequestDenied -> TermuxPermissionAction.OPEN_APP_SETTINGS
    else -> TermuxPermissionAction.REQUEST
}
