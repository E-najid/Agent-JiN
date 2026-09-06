package com.ngi.agentjin.core.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

data class Conversation(
    val id: Long,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

data class ChatMessage(
    val id: Long,
    val conversationId: Long,
    val role: String,
    val content: String,
    val createdAt: Long,
)

class ConversationRepository(
    context: Context,
    private val memory: EncryptedMemoryStore,
) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private var helper: Helper? = null

    suspend fun openIfNeeded() = mutex.withLock {
        if (helper != null) return
        val file = memory.loadConversationsDbToWork()
        helper = Helper(appContext, file)
    }

    suspend fun closeAndPersist() = mutex.withLock {
        helper?.close()
        helper = null
        if (memory.isUnlocked) {
            memory.persistConversationsDb()
        }
    }

    suspend fun persist() = mutex.withLock {
        helper?.writableDatabase?.close()
        helper?.close()
        val file = memory.conversationsWorkFile()
        helper = Helper(appContext, file)
        memory.persistConversationsDb()
    }

    private fun db(): SQLiteDatabase {
        return helper?.writableDatabase ?: throw IllegalStateException("conversations db not open")
    }

    suspend fun listConversations(): List<Conversation> = mutex.withLock {
        val out = mutableListOf<Conversation>()
        db().rawQuery(
            "SELECT id, title, created_at, updated_at FROM conversations ORDER BY updated_at DESC",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                out += Conversation(c.getLong(0), c.getString(1), c.getLong(2), c.getLong(3))
            }
        }
        out
    }

    suspend fun createConversation(title: String = "New chat"): Conversation = mutex.withLock {
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("title", title)
            put("created_at", now)
            put("updated_at", now)
        }
        val id = db().insert("conversations", null, cv)
        Conversation(id, title, now, now)
    }

    suspend fun messages(conversationId: Long): List<ChatMessage> = mutex.withLock {
        val out = mutableListOf<ChatMessage>()
        db().rawQuery(
            "SELECT id, conversation_id, role, content, created_at FROM messages WHERE conversation_id=? ORDER BY id ASC",
            arrayOf(conversationId.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out += ChatMessage(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getLong(4))
            }
        }
        out
    }

    suspend fun addMessage(conversationId: Long, role: String, content: String): ChatMessage = mutex.withLock {
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("conversation_id", conversationId)
            put("role", role)
            put("content", content)
            put("created_at", now)
        }
        val id = db().insert("messages", null, cv)
        db().execSQL("UPDATE conversations SET updated_at=? WHERE id=?", arrayOf(now, conversationId))
        if (role == "user") {
            val title = content.trim().lineSequence().firstOrNull()?.take(48).orEmpty()
            if (title.isNotEmpty()) {
                db().execSQL("UPDATE conversations SET title=? WHERE id=? AND title=?", arrayOf(title, conversationId, "New chat"))
            }
        }
        ChatMessage(id, conversationId, role, content, now)
    }

    private class Helper(context: Context, file: File) :
        SQLiteOpenHelper(context, file.absolutePath, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE conversations (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    title TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    conversation_id INTEGER NOT NULL,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    FOREIGN KEY(conversation_id) REFERENCES conversations(id)
                )
                """.trimIndent(),
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
