package com.ngi.agentjin.core.storage

import android.content.Context
import android.util.Base64
import com.ngi.agentjin.core.crypto.CryptoEngine
import com.ngi.agentjin.core.crypto.CryptoException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Decrypts /memories/* into a working copy under the app's private files dir,
 * and writes encrypted blobs back to the portable folder.
 *
 * The encryption key lives only in RAM (and optionally a biometric-wrapped
 * Keystore cache). It is never written to the SD-card folder.
 */
class EncryptedMemoryStore(
    context: Context,
    private val storage: StorageRoot,
    private val crypto: CryptoEngine,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val workDir = File(context.filesDir, "memories_work").apply { mkdirs() }
    private val keyRef = AtomicReference<ByteArray?>(null)

    val isUnlocked: Boolean get() = keyRef.get() != null

    fun unlockWithKey(key: ByteArray) {
        keyRef.set(key.copyOf())
    }

    fun lock() {
        keyRef.getAndSet(null)?.let { crypto.wipe(it) }
        workDir.listFiles()?.forEach { it.delete() }
    }

    fun requireKey(): ByteArray {
        return keyRef.get() ?: throw CryptoException("memory store is locked")
    }

    fun conversationsWorkFile(): File = File(workDir, "conversations.db")

    fun loadConversationsDbToWork(): File {
        val dest = conversationsWorkFile()
        val blob = storage.readBytes(listOf("memories", "conversations.db"))
        if (blob == null) {
            if (dest.exists()) dest.delete()
            return dest
        }
        val plain = crypto.decrypt(requireKey(), blob)
        dest.writeBytes(plain)
        crypto.wipe(plain)
        return dest
    }

    fun persistConversationsDb() {
        val src = conversationsWorkFile()
        if (!src.exists()) return
        val plain = src.readBytes()
        val blob = crypto.encrypt(requireKey(), plain)
        crypto.wipe(plain)
        storage.writeBytes(listOf("memories", "conversations.db"), blob)
    }

    fun readNotes(): JsonObject {
        return readEncryptedJson(listOf("memories", "notes.json")) ?: buildJsonObject { }
    }

    fun writeNotes(obj: JsonObject) {
        writeEncryptedJson(listOf("memories", "notes.json"), obj)
    }

    fun readPreferences(): JsonObject {
        return readEncryptedJson(listOf("memories", "preferences.json")) ?: buildJsonObject { }
    }

    fun writePreferences(obj: JsonObject) {
        writeEncryptedJson(listOf("memories", "preferences.json"), obj)
    }

    fun readEnabledPluginList(): List<String>? {
        val bytes = storage.readBytes(listOf("plugins_config", "enabled_plugins.json")) ?: return null
        val el = json.parseToJsonElement(bytes.toString(Charsets.UTF_8))
        val arr = el.jsonObject["enabled"] as? JsonArray ?: return null
        return arr.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
    }

    fun writeEnabledPlugins(enabled: List<String>) {
        val body = buildJsonObject {
            put("enabled", JsonArray(enabled.map { JsonPrimitive(it) }))
        }
        storage.writeBytes(
            listOf("plugins_config", "enabled_plugins.json"),
            body.toString().toByteArray(Charsets.UTF_8),
        )
    }

    private fun readEncryptedJson(path: List<String>): JsonObject? {
        val blob = storage.readBytes(path) ?: return null
        val plain = crypto.decrypt(requireKey(), blob)
        val text = plain.toString(Charsets.UTF_8)
        crypto.wipe(plain)
        return json.parseToJsonElement(text).jsonObject
    }

    private fun writeEncryptedJson(path: List<String>, obj: JsonObject) {
        val plain = obj.toString().toByteArray(Charsets.UTF_8)
        val blob = crypto.encrypt(requireKey(), plain)
        crypto.wipe(plain)
        storage.writeBytes(path, blob)
    }

    companion object {
        val DEFAULT_ENABLED = setOf("screen_agent", "app_manager", "settings")
    }
}

fun ByteArray.toB64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
fun String.fromB64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)
