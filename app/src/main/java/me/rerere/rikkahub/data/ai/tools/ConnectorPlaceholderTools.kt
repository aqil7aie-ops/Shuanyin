package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool

internal fun notConnectedConnectorTool(name: String, connectorName: String): Tool = Tool(
    name = name,
    description = "Report whether the $connectorName connector is linked. OAuth is intentionally not implemented; do not claim that external data was accessed.",
    execute = {
        buildJsonObject {
            put("connector", connectorName)
            put("connected", false)
            put("status", "not_connected")
            put("message", "OAuth linking is not configured. Ask the user to connect this service in a later version.")
        }
    },
)
