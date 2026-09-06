package com.ngi.agentjin.core.planning

import com.ngi.agentjin.core.inference.TextModelEngine
import com.ngi.agentjin.core.plugin.PluginManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class Planner(
    private val text: TextModelEngine,
    private val plugins: PluginManager,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun systemPrompt(screenDump: String?): String {
        return buildString {
            appendLine("You are Agent JiN, an on-device assistant.")
            appendLine("You never invent personal data, passwords, amounts, or recipients. If you need information the user has not given, ask.")
            appendLine("Available tools:")
            appendLine(plugins.toolSchemasForModel().ifBlank { "(no plugins enabled)" })
            appendLine()
            appendLine("Respond with ONE JSON object only, no markdown, no extra text. One of:")
            appendLine("""{"type":"chat","message":"..."}""")
            appendLine("""{"type":"ask_user","question":"..."}""")
            appendLine("""{"type":"plan","steps":[{"id":1,"plugin":"app_manager","params":{"action":"open","app_name":"Settings"},"description":"Open Settings"}]}""")
            appendLine("For anything that needs more than one tool call, use type=plan.")
            appendLine("For small talk or questions you can answer without tools, use type=chat.")
            if (!screenDump.isNullOrBlank()) {
                appendLine()
                appendLine("Current screen:")
                appendLine(screenDump.take(6000))
            }
        }
    }

    suspend fun decide(
        userMessage: String,
        history: List<Pair<String, String>>,
        screenDump: String?,
        extra: String? = null,
        onToken: ((String) -> Unit)? = null,
    ): ModelDecision {
        val prompt = buildPrompt(userMessage, history, screenDump, extra)
        var raw = text.complete(
            prompt = prompt,
            maxTokens = 192,
            temperature = 0.2f,
            grammar = ORCHESTRATOR_GBNF,
            onToken = onToken,
        )
        if (raw.isBlank() || raw.startsWith("ERROR:")) {
            raw = text.complete(
                prompt = prompt,
                maxTokens = 192,
                temperature = 0.2f,
                grammar = null,
                onToken = onToken,
            )
        }
        return parse(raw)
    }

    suspend fun replan(
        goal: String,
        failed: PlanStep,
        screenDump: String?,
        remainingBudget: Int,
        onToken: ((String) -> Unit)? = null,
    ): ModelDecision {
        val extra = buildString {
            appendLine("The previous plan failed at step ${failed.id}: ${failed.description}")
            appendLine("Error: ${failed.resultMessage}")
            appendLine("Produce a revised plan for the original goal. Max $remainingBudget steps.")
            appendLine("Goal: $goal")
        }
        return decide(goal, emptyList(), screenDump, extra, onToken)
    }

    private fun buildPrompt(
        userMessage: String,
        history: List<Pair<String, String>>,
        screenDump: String?,
        extra: String?,
    ): String {
        return buildString {
            append("<|im_start|>system\n")
            append(systemPrompt(screenDump))
            if (!extra.isNullOrBlank()) {
                append("\n")
                append(extra)
            }
            append("<|im_end|>\n")
            for ((role, content) in history.takeLast(8)) {
                append("<|im_start|>$role\n")
                append(content)
                append("<|im_end|>\n")
            }
            append("<|im_start|>user\n")
            append(userMessage)
            append("<|im_end|>\n")
            append("<|im_start|>assistant\n")
        }
    }

    fun parse(raw: String): ModelDecision {
        val obj = extractJsonObject(raw)
            ?: return ModelDecision.ParseError(raw, "No JSON object in model output")
        val type = obj["type"]?.jsonPrimitive?.contentOrNull
            ?: return ModelDecision.ParseError(raw, "Missing type")
        return when (type) {
            "chat" -> ModelDecision.Chat(obj["message"]?.jsonPrimitive?.contentOrNull ?: "")
            "ask_user" -> ModelDecision.AskUser(obj["question"]?.jsonPrimitive?.contentOrNull ?: "Need more information")
            "plan" -> {
                val stepsEl = obj["steps"] ?: return ModelDecision.ParseError(raw, "plan missing steps")
                val arr = stepsEl as? JsonArray ?: stepsEl.jsonArray
                val steps = arr.mapIndexed { idx, el ->
                    val o = el.jsonObject
                    PlanStep(
                        id = o["id"]?.jsonPrimitive?.intOrNull ?: (idx + 1),
                        plugin = o["plugin"]?.jsonPrimitive?.contentOrNull
                            ?: return ModelDecision.ParseError(raw, "step missing plugin"),
                        params = o["params"] as? JsonObject ?: JsonObject(emptyMap()),
                        description = o["description"]?.jsonPrimitive?.contentOrNull ?: "",
                    )
                }
                if (steps.isEmpty()) ModelDecision.ParseError(raw, "empty plan")
                else ModelDecision.Plan(steps)
            }
            else -> ModelDecision.ParseError(raw, "unknown type $type")
        }
    }

    private fun extractJsonObject(raw: String): JsonObject? {
        val trimmed = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = trimmed.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var escape = false
        for (i in start until trimmed.length) {
            val c = trimmed[i]
            if (inStr) {
                if (escape) escape = false
                else if (c == '\\') escape = true
                else if (c == '"') inStr = false
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        val slice = trimmed.substring(start, i + 1)
                        return runCatching { json.parseToJsonElement(slice) as JsonObject }.getOrNull()
                    }
                }
            }
        }
        return runCatching { json.parseToJsonElement(trimmed) as JsonObject }.getOrNull()
    }

    companion object {
        // Constrains the 350M orchestrator to valid action JSON.
        const val ORCHESTRATOR_GBNF = """
root ::= object
object ::= "{" ws typefield ("," ws extra)? "}"
typefield ::= "\"type\"" ws ":" ws tvalue
tvalue ::= chat | ask | plan
chat ::= "\"chat\"" "," ws "\"message\"" ws ":" ws string
ask ::= "\"ask_user\"" "," ws "\"question\"" ws ":" ws string
plan ::= "\"plan\"" "," ws "\"steps\"" ws ":" ws "[" ws step ("," ws step)* ws "]"
step ::= "{" ws "\"id\"" ws ":" ws integer "," ws "\"plugin\"" ws ":" ws string "," ws "\"params\"" ws ":" ws json "," ws "\"description\"" ws ":" ws string ws "}"
extra ::= "\"message\"" ws ":" ws string
json ::= object2 | array | string | number | "true" | "false" | "null"
object2 ::= "{" ws (string ws ":" ws json ("," ws string ws ":" ws json)*)? ws "}"
array ::= "[" ws (json ("," ws json)*)? ws "]"
string ::= "\"" chars "\""
chars ::= char*
char ::= [^"\\] | "\\" escape
escape ::= ["\\/bfnrt] | "u" hex hex hex hex
hex ::= [0-9a-fA-F]
number ::= "-"? int frac? exp?
int ::= "0" | [1-9] [0-9]*
integer ::= int
frac ::= "." [0-9]+
exp ::= [eE] [+\-]? [0-9]+
ws ::= [ \t\n]*
"""
    }
}
