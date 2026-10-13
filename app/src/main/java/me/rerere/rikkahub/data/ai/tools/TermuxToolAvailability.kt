package me.rerere.rikkahub.data.ai.tools

internal fun shouldExposeTermuxTools(
    assistantConnectorEnabled: Boolean,
    integrationEnabled: Boolean,
): Boolean = assistantConnectorEnabled && integrationEnabled

internal fun shouldRunTermuxWithoutApproval(
    integrationEnabled: Boolean,
    assistantConnectorEnabled: Boolean,
    fullAuth: Boolean,
): Boolean = integrationEnabled && assistantConnectorEnabled && fullAuth
