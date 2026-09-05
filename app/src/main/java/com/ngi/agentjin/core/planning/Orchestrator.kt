package com.ngi.agentjin.core.planning

import com.ngi.agentjin.core.inference.ModelNotReadyException
import com.ngi.agentjin.core.inference.TextModelEngine
import com.ngi.agentjin.core.safety.UserQuestionGate
import com.ngi.agentjin.core.screen.ScreenPerception
import com.ngi.agentjin.core.storage.ConversationRepository

class Orchestrator(
    private val text: TextModelEngine,
    private val planner: Planner,
    private val executor: TaskExecutor,
    private val conversations: ConversationRepository,
    private val screen: ScreenPerception,
    private val questions: UserQuestionGate,
) {
    suspend fun handleUserMessage(
        conversationId: Long,
        userText: String,
        onToken: (String) -> Unit,
        onStatus: (String) -> Unit,
    ): String {
        conversations.addMessage(conversationId, "user", userText)
        try {
            text.ensureLoaded()
        } catch (e: ModelNotReadyException) {
            val msg = e.message ?: "Text model is not ready"
            conversations.addMessage(conversationId, "assistant", msg)
            return msg
        }
        val history = conversations.messages(conversationId)
            .dropLast(1)
            .takeLast(12)
            .map { it.role to it.content }
        val dump = if (screen.isAvailable()) screen.dumpText() else null
        onStatus("Thinking…")
        val acc = StringBuilder()
        val decision = planner.decide(userText, history, dump, extra = null) { tok ->
            acc.append(tok)
            onToken(tok)
        }
        val reply = when (decision) {
            is ModelDecision.Chat -> decision.message.ifBlank { acc.toString() }
            is ModelDecision.AskUser -> {
                val answer = questions.ask(decision.question)
                if (answer.isNullOrBlank()) {
                    decision.question
                } else {
                    handleUserMessage(conversationId, "My answer: $answer", onToken, onStatus)
                }
            }
            is ModelDecision.Plan -> {
                onStatus("Executing plan…")
                executor.run(userText, decision.steps, onStatus)
            }
            is ModelDecision.ParseError -> {
                // One retry without grammar if the constrained decode failed.
                if (decision.raw.isBlank()) {
                    "The model produced no output. The GGUF may be missing or llama.cpp failed to generate."
                } else {
                    val prose = decision.raw.trim()
                    if (prose.startsWith("{")) {
                        "I couldn't parse a plan from the model output (${decision.reason}). Raw: ${prose.take(500)}"
                    } else {
                        prose
                    }
                }
            }
        }
        conversations.addMessage(conversationId, "assistant", reply)
        conversations.persist()
        return reply
    }
}
