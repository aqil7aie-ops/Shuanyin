package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.Tool

fun createDriveTools(): List<Tool> = listOf(
    notConnectedConnectorTool("drive_list_files", "Google Drive"),
)
