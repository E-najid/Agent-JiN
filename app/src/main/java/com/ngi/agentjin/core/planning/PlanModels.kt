package com.ngi.agentjin.core.planning

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

enum class StepStatus { PENDING, IN_PROGRESS, DONE, FAILED, SKIPPED }

@Serializable
data class PlanStep(
    val id: Int,
    val plugin: String,
    val params: JsonObject,
    val description: String,
    val status: StepStatus = StepStatus.PENDING,
    val resultMessage: String? = null,
)

@Serializable
data class TaskPlan(
    val taskId: String,
    val goal: String,
    val steps: MutableList<PlanStep>,
    var revision: Int = 0,
)

sealed class ModelDecision {
    data class Chat(val message: String) : ModelDecision()
    data class Plan(val steps: List<PlanStep>) : ModelDecision()
    data class AskUser(val question: String) : ModelDecision()
    data class ParseError(val raw: String, val reason: String) : ModelDecision()
}
