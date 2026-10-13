package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.Tool

fun createCalendarTools(): List<Tool> = listOf(
    notConnectedConnectorTool("calendar_list_events", "Google Calendar"),
)
