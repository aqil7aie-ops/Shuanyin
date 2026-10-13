package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.Tool

fun createGmailTools(): List<Tool> = listOf(
    notConnectedConnectorTool("gmail_search_messages", "Gmail"),
)
