package com.ngi.agentjin.core.safety

import kotlinx.serialization.json.JsonObject
import java.util.ArrayDeque

data class UndoRecord(
    val pluginName: String,
    val token: String,
    val params: JsonObject,
    val atEpochMs: Long = System.currentTimeMillis(),
)

class UndoManager {
    private val stack = ArrayDeque<UndoRecord>()

    fun push(pluginName: String, token: String, params: JsonObject) {
        stack.addLast(UndoRecord(pluginName, token, params))
        while (stack.size > 32) stack.removeFirst()
    }

    fun pop(): UndoRecord? = if (stack.isEmpty()) null else stack.removeLast()

    fun last(): UndoRecord? = stack.lastOrNull()

    fun isEmpty(): Boolean = stack.isEmpty()
}
