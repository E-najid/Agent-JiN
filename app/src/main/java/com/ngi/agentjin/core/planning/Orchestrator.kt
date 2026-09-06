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
            onStatus("Loading model…")
            text.ensureLoaded()
        } catch (e: ModelNotReadyException) {
            val msg = e.message ?: "Text model is not ready"
            conversations.addMessage(conversationId, "assistant", msg)
            return msg
        } catch (t: Throwable) {
            val msg = "Failed to load the text model: ${t.message ?: t.javaClass.simpleName}"
            conversations.addMessage(conversationId, "assistant", msg)
            return msg
        }
        val history = conversations.messages(conversationId)
            .dropLast(1)
            .takeLast(4)
            .map { it.role to it.content.take(300) }
        onStatus("Thinking…")
        val reply = try {
            if (looksLikeTask(userText)) {
                val dump = if (screen.isAvailable()) screen.dumpText()?.take(800) else null
                when (val decision = planner.decide(userText, history, dump, extra = null, onToken = null)) {
                    is ModelDecision.Chat -> decision.message
                    is ModelDecision.AskUser -> {
                        val answer = questions.ask(decision.question)
                        if (answer.isNullOrBlank()) decision.question
                        else handleUserMessage(conversationId, "My answer: $answer", onToken, onStatus)
                    }
                    is ModelDecision.Plan -> {
                        onStatus("Executing plan…")
                        executor.run(userText, decision.steps, onStatus)
                    }
                    is ModelDecision.ParseError -> {
                        val prose = decision.raw.trim()
                        if (prose.isBlank()) {
                            "The model produced no output."
                        } else if (prose.startsWith("{")) {
                            "I couldn't parse a plan (${decision.reason})."
                        } else {
                            prose.take(1500)
                        }
                    }
                }
            } else {
                chatReply(userText, history)
            }
        } catch (t: Throwable) {
            "Generation failed: ${t.message ?: t.javaClass.simpleName}"
        }
        conversations.addMessage(conversationId, "assistant", reply)
        runCatching { conversations.persist() }
        return reply
    }

    private suspend fun chatReply(userText: String, history: List<Pair<String, String>>): String {
        val prompt = buildString {
            append("You are Agent JiN. Answer briefly in the user's language.\n")
            for ((role, content) in history) {
                append(role).append(": ").append(content).append('\n')
            }
            append("user: ").append(userText.take(400)).append('\n')
            append("assistant: ")
        }
        val out = text.complete(
            prompt = prompt,
            maxTokens = 96,
            temperature = 0.4f,
            grammar = null,
            onToken = null,
        )
        return out.trim().ifBlank { "I couldn't generate a reply. Try again." }
    }

    companion object {
        fun looksLikeTask(text: String): Boolean {
            val t = text.lowercase()
            val keys = listOf(
                "open ", "turn on", "turn off", "wifi", "wi-fi", "bluetooth",
                "brightness", "tap ", "click ", "scroll", "go back", "home screen",
                "screenshot", "type ", "enable ", "disable ", "launch ",
            )
            return keys.any { t.contains(it) }
        }
    }
}
