package me.rerere.rikkahub.data.ai.tools

internal fun shouldExposeTermuxTools(
    assistantConnectorEnabled: Boolean,
    integrationEnabled: Boolean,
): Boolean = assistantConnectorEnabled && integrationEnabled
