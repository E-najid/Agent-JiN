package com.ngi.agentjin.core.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import com.ngi.agentjin.core.crypto.CryptoEngine
import com.ngi.agentjin.core.crypto.CryptoException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Portable workspace on a user-chosen folder (Storage Access Framework).
 *
 * Layout:
 *   /manifest.json
 *   /memories/conversations.db
 *   /memories/notes.json
 *   /memories/preferences.json
 *   /plugins_config/enabled_plugins.json
 *   /logs/
 *   /task_history/
 *   /models/<model-files>
 */
class StorageRoot(
    private val context: Context,
    private val devicePreferences: DevicePreferences,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    val treeUri: Uri? get() = devicePreferences.treeUri

    fun hasPersistedTree(): Boolean = treeUri != null && rootOrNull() != null

    fun takePersistablePermission(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            throw IllegalStateException("Could not persist access to the chosen folder", e)
        }
        devicePreferences.treeUri = uri
    }

    fun rootOrNull(): DocumentFile? {
        val uri = treeUri ?: return null
        return DocumentFile.fromTreeUri(context, uri)
    }

    fun requireRoot(): DocumentFile {
        return rootOrNull() ?: throw IllegalStateException("No storage folder selected")
    }

    fun ensureLayout() {
        val root = requireRoot()
        dir(root, "memories")
        dir(root, "plugins_config")
        dir(root, "logs")
        dir(root, "task_history")
        dir(root, "models")
    }

    fun readManifest(): WorkspaceManifest? {
        val bytes = readBytes(listOf("manifest.json")) ?: return null
        val text = bytes.toString(Charsets.UTF_8)
        return json.decodeFromString<WorkspaceManifest>(text)
    }

    fun writeManifest(manifest: WorkspaceManifest) {
        val text = json.encodeToString(WorkspaceManifest.serializer(), manifest)
        writeBytes(listOf("manifest.json"), text.toByteArray(Charsets.UTF_8))
    }

    fun manifestExists(): Boolean = find(listOf("manifest.json")) != null

    fun find(path: List<String>): DocumentFile? {
        var cur = rootOrNull() ?: return null
        for (seg in path) {
            cur = cur.findFile(seg) ?: return null
        }
        return cur
    }

    fun readBytes(path: List<String>): ByteArray? {
        val file = find(path) ?: return null
        context.contentResolver.openInputStream(file.uri)?.use { input ->
            val buf = ByteArrayOutputStream()
            input.copyTo(buf)
            return buf.toByteArray()
        }
        return null
    }

    fun writeBytes(path: List<String>, bytes: ByteArray) {
        require(path.isNotEmpty())
        val root = requireRoot()
        var dir = root
        for (seg in path.dropLast(1)) {
            dir = dir(dir, seg)
        }
        val name = path.last()
        val existing = dir.findFile(name)
        val target = existing ?: dir.createFile(mimeFor(name), name)
            ?: throw IllegalStateException("Unable to create $name")
        openOutputStreamTruncating(target).use { out ->
            out.write(bytes)
            out.flush()
        }
    }

    fun openRead(path: List<String>): java.io.InputStream? {
        val file = find(path) ?: return null
        return context.contentResolver.openInputStream(file.uri)
    }

    fun openWriteTruncating(path: List<String>): java.io.OutputStream {
        require(path.isNotEmpty())
        var dir = requireRoot()
        for (seg in path.dropLast(1)) dir = dir(dir, seg)
        val name = path.last()
        val existing = dir.findFile(name)
        val target = existing ?: dir.createFile(mimeFor(name), name)
            ?: throw IllegalStateException("Unable to create $name")
        return openOutputStreamTruncating(target)
    }

    fun openWriteResumable(path: List<String>): Pair<DocumentFile, Long> {
        require(path.isNotEmpty())
        var dir = requireRoot()
        for (seg in path.dropLast(1)) dir = dir(dir, seg)
        val name = path.last()
        val existing = dir.findFile(name)
        val target = existing ?: dir.createFile(mimeFor(name), name)
            ?: throw IllegalStateException("Unable to create $name")
        return target to target.length()
    }

    fun delete(path: List<String>): Boolean {
        return find(path)?.delete() == true
    }

    fun list(path: List<String>): List<DocumentFile> {
        val d = if (path.isEmpty()) rootOrNull() else find(path)
        return d?.listFiles()?.toList().orEmpty()
    }

    fun childUri(path: List<String>): Uri? = find(path)?.uri

    private fun dir(parent: DocumentFile, name: String): DocumentFile {
        parent.findFile(name)?.let { if (it.isDirectory) return it }
        return parent.createDirectory(name)
            ?: throw IllegalStateException("Unable to create directory $name")
    }

    private fun mimeFor(name: String): String = when {
        name.endsWith(".json") -> "application/json"
        name.endsWith(".db") -> "application/octet-stream"
        name.endsWith(".gguf") -> "application/octet-stream"
        name.endsWith(".log") -> "text/plain"
        name.endsWith(".jsonl") -> "application/jsonl"
        else -> "application/octet-stream"
    }
}

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
