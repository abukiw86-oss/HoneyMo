package com.example.HoneyMo.agent

import org.json.JSONObject

data class AgentCommand(
    val action: String,
    val thought: String,
    val parameters: JSONObject
) {
    companion object {
        fun fromJson(json: JSONObject): AgentCommand {
            return AgentCommand(
                action = json.optString("action"),
                thought = json.optString("thought"),
                parameters = json.optJSONObject("parameters") ?: JSONObject()
            )
        }
    }
}
