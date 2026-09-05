package com.ngi.agentjin.core.planning

import com.ngi.agentjin.core.plugin.PluginManager
import com.ngi.agentjin.core.safety.UserQuestionGate
import com.ngi.agentjin.core.screen.ScreenPerception
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class TaskExecutor(
    private val plugins: PluginManager,
    private val planner: Planner,
    private val history: TaskHistoryStore,
    private val screen: ScreenPerception,
    private val questions: UserQuestionGate,
    private val maxSteps: () -> Int,
) {
    private val _plan = MutableStateFlow<TaskPlan?>(null)
    val plan: StateFlow<TaskPlan?> = _plan.asStateFlow()

    @Volatile
    var cancelRequested: Boolean = false

    suspend fun run(goal: String, initial: List<PlanStep>, onEvent: (String) -> Unit): String {
        cancelRequested = false
        val taskId = history.newTaskId()
        var plan = TaskPlan(taskId, goal, initial.take(maxSteps()).toMutableList())
        publish(plan)
        history.logTask(taskId, "task_start", goal)
        var used = 0
        var replans = 0
        while (used < maxSteps()) {
            if (cancelRequested) {
                history.logTask(taskId, "task_cancel", "cancelled")
                return "Cancelled."
            }
            val index = plan.steps.indexOfFirst { it.status == StepStatus.PENDING }
            if (index < 0) break
            val next = plan.steps[index]
            plan = replaceStep(plan, index, next.copy(status = StepStatus.IN_PROGRESS))
            onEvent("Step ${next.id}: ${next.description}")
            var result = plugins.execute(next.plugin, next.params, taskId)
            if (result.needsUserInput && result.userPrompt != null) {
                val answer = questions.ask(result.userPrompt)
                if (answer.isNullOrBlank()) {
                    val failed = next.copy(status = StepStatus.FAILED, resultMessage = "User did not provide the requested information")
                    plan = replaceStep(plan, index, failed)
                    history.logTask(taskId, "task_fail", failed.resultMessage!!)
                    return failed.resultMessage!!
                }
                val params = JsonObject(next.params + ("user_answer" to JsonPrimitive(answer)))
                result = plugins.execute(next.plugin, params, taskId)
            }
            if (result.ok) {
                plan = replaceStep(plan, index, next.copy(status = StepStatus.DONE, resultMessage = result.message))
                used++
                continue
            }
            val failed = next.copy(status = StepStatus.FAILED, resultMessage = result.message)
            plan = replaceStep(plan, index, failed)
            used++
            if (replans >= 2) {
                history.logTask(taskId, "task_fail", failed.resultMessage ?: "failed")
                return "Stopped after repeated failures: ${failed.resultMessage}"
            }
            val remaining = maxSteps() - used
            if (remaining <= 0) break
            onEvent("Re-planning after failure…")
            val dump = if (screen.isAvailable()) screen.dumpText() else null
            when (val decision = planner.replan(goal, failed, dump, remaining)) {
                is ModelDecision.Plan -> {
                    replans++
                    val kept = plan.steps.filter { it.status == StepStatus.DONE }
                    plan = plan.copy(
                        steps = (kept + decision.steps).toMutableList(),
                        revision = plan.revision + 1,
                    )
                    publish(plan)
                    history.logTask(taskId, "replan", "revision ${plan.revision}")
                }
                is ModelDecision.AskUser -> {
                    val answer = questions.ask(decision.question)
                    if (answer.isNullOrBlank()) return "Need more information: ${decision.question}"
                    val extraDump = if (screen.isAvailable()) screen.dumpText() else null
                    when (val d2 = planner.decide("$goal\nUser answer: $answer", emptyList(), extraDump)) {
                        is ModelDecision.Plan -> {
                            replans++
                            val kept = plan.steps.filter { it.status == StepStatus.DONE }
                            plan = plan.copy(steps = (kept + d2.steps).toMutableList(), revision = plan.revision + 1)
                            publish(plan)
                        }
                        is ModelDecision.Chat -> return d2.message
                        else -> return decision.question
                    }
                }
                is ModelDecision.Chat -> return decision.message
                is ModelDecision.ParseError -> return "Re-plan failed: ${decision.reason}\n${decision.raw}"
            }
        }
        val failed = plan.steps.any { it.status == StepStatus.FAILED }
        val msg = if (failed) {
            "Finished with failures. " + plan.steps.joinToString(" | ") { "${it.id}:${it.status}" }
        } else {
            "Done. ${plan.steps.count { it.status == StepStatus.DONE }} steps completed."
        }
        history.logTask(taskId, if (failed) "task_fail" else "task_done", msg)
        return msg
    }

    fun clear() {
        _plan.value = null
        cancelRequested = true
    }

    private fun replaceStep(plan: TaskPlan, index: Int, step: PlanStep): TaskPlan {
        val steps = plan.steps.toMutableList()
        steps[index] = step
        val updated = plan.copy(steps = steps)
        publish(updated)
        return updated
    }

    private fun publish(plan: TaskPlan) {
        _plan.value = plan.copy(steps = plan.steps.toList().toMutableList())
    }
}
