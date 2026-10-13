package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.Tool

fun createInstagramTools(): List<Tool> = listOf(
    notConnectedConnectorTool("instagram_get_profile", "Instagram"),
)
