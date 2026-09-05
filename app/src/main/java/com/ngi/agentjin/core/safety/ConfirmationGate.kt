package com.ngi.agentjin.core.safety

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID

data class ConfirmRequest(
    val id: String,
    val title: String,
    val message: String,
    val deferred: CompletableDeferred<Boolean>,
)

class ConfirmationGate {
    private val _requests = MutableSharedFlow<ConfirmRequest>(extraBufferCapacity = 8)
    val requests: SharedFlow<ConfirmRequest> = _requests.asSharedFlow()

    suspend fun request(title: String, message: String): Boolean {
        val d = CompletableDeferred<Boolean>()
        val req = ConfirmRequest(UUID.randomUUID().toString(), title, message, d)
        _requests.emit(req)
        return d.await()
    }

    fun answer(id: String, accepted: Boolean) {
        // Collected by UI; ChatViewModel keeps the pending map.
    }
}

class UserQuestionGate {
    data class Question(
        val id: String,
        val prompt: String,
        val deferred: CompletableDeferred<String?>,
    )

    private val _questions = MutableSharedFlow<Question>(extraBufferCapacity = 8)
    val questions: SharedFlow<Question> = _questions.asSharedFlow()

    suspend fun ask(prompt: String): String? {
        val d = CompletableDeferred<String?>()
        _questions.emit(Question(UUID.randomUUID().toString(), prompt, d))
        return d.await()
    }
}
