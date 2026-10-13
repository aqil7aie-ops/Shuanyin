package me.rerere.rikkahub.ui.pages.setting

internal enum class TermuxExternalAppsStatus {
    NOT_CHECKED,
    CHECKING,
    NOT_INSTALLED,
    RUN_COMMAND_PERMISSION_REQUIRED,
    READY,
    ALLOW_EXTERNAL_APPS_MISSING,
    CHECK_FAILED,
}

internal fun resolveTermuxExternalAppsStatus(
    termuxInstalled: Boolean,
    runCommandPermissionGranted: Boolean,
    commandExitCode: Int?,
): TermuxExternalAppsStatus = when {
    !termuxInstalled -> TermuxExternalAppsStatus.NOT_INSTALLED
    !runCommandPermissionGranted -> TermuxExternalAppsStatus.RUN_COMMAND_PERMISSION_REQUIRED
    commandExitCode == 0 -> TermuxExternalAppsStatus.READY
    commandExitCode == 1 -> TermuxExternalAppsStatus.ALLOW_EXTERNAL_APPS_MISSING
    else -> TermuxExternalAppsStatus.CHECK_FAILED
}
