package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.Tool

fun createFacebookTools(): List<Tool> = listOf(
    notConnectedConnectorTool("facebook_list_pages", "Facebook"),
)
