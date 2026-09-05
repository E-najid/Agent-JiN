package com.ngi.agentjin.core.inference

import com.ngi.agentjin.core.download.Sha256
import com.ngi.agentjin.core.storage.ModelMeta
import com.ngi.agentjin.core.storage.StorageRoot
import com.ngi.agentjin.core.storage.WorkspaceManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

enum class ModelFileStatus { OK, MISSING, CORRUPT, UNKNOWN }

data class ModelCheck(
    val spec: ModelSpec,
    val status: ModelFileStatus,
    val actualSha256: String? = null,
    val localPath: File? = null,
)

class ModelManager(
    private val storage: StorageRoot,
    private val ram: RamPolicy,
    private val cacheDir: File,
) {
    private val localModels = File(cacheDir, "models").apply { mkdirs() }

    /**
     * Materialize a GGUF onto a real filesystem path so llama.cpp can mmap it.
     * SAF URIs are not mmap-able; we copy (or reuse a checksummed cache copy).
     */
    suspend fun localFileFor(spec: ModelSpec): File? = withContext(Dispatchers.IO) {
        val dest = File(localModels, spec.filename)
        if (dest.exists() && dest.length() == spec.sizeBytes) {
            val sha = Sha256.ofFile(dest)
            if (sha.equals(spec.sha256, ignoreCase = true)) return@withContext dest
        }
        val remote = storage.find(spec.relativePath.split("/")) ?: return@withContext null
        storage.openRead(spec.relativePath.split("/"))?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: return@withContext null
        val sha = Sha256.ofFile(dest)
        if (!sha.equals(spec.sha256, ignoreCase = true)) {
            dest.delete()
            return@withContext null
        }
        dest
    }

    suspend fun check(spec: ModelSpec): ModelCheck = withContext(Dispatchers.IO) {
        val cached = File(localModels, spec.filename)
        if (cached.exists()) {
            if (cached.length() != spec.sizeBytes) {
                return@withContext ModelCheck(spec, ModelFileStatus.CORRUPT, localPath = cached)
            }
            val sha = Sha256.ofFile(cached)
            val st = if (sha.equals(spec.sha256, ignoreCase = true)) ModelFileStatus.OK else ModelFileStatus.CORRUPT
            return@withContext ModelCheck(spec, st, sha, cached)
        }
        val df = storage.find(spec.relativePath.split("/"))
        if (df == null || !df.exists()) {
            return@withContext ModelCheck(spec, ModelFileStatus.MISSING)
        }
        if (spec.sizeBytes > 0 && df.length() > 0 && df.length() != spec.sizeBytes) {
            return@withContext ModelCheck(spec, ModelFileStatus.CORRUPT)
        }
        val sha = storage.openRead(spec.relativePath.split("/"))?.use { Sha256.ofStream(it) }
            ?: return@withContext ModelCheck(spec, ModelFileStatus.UNKNOWN)
        val st = if (sha.equals(spec.sha256, ignoreCase = true)) ModelFileStatus.OK else ModelFileStatus.CORRUPT
        ModelCheck(spec, st, sha)
    }

    suspend fun checkAll(): List<ModelCheck> = ModelCatalog.ALL.map { check(it) }

    fun updateManifestModels(current: WorkspaceManifest): WorkspaceManifest {
        val now = Instant.now().toString()
        val models = current.models.toMutableMap()
        for (spec in ModelCatalog.ALL) {
            val existing = models[spec.id]
            models[spec.id] = ModelMeta(
                name = spec.displayName,
                path = spec.relativePath,
                sha256 = spec.sha256,
                downloadedAt = existing?.downloadedAt ?: now,
                sizeBytes = spec.sizeBytes,
            )
        }
        return current.copy(models = models)
    }

    val ramPolicy: RamPolicy get() = ram
}
