package com.ngi.agentjin.core.storage

import com.ngi.agentjin.core.crypto.CryptoEngine
import com.ngi.agentjin.core.inference.ModelCatalog
import java.time.Instant

sealed class UnlockResult {
    data object Ok : UnlockResult()
    data class LockedOut(val remainingMs: Long) : UnlockResult()
    data class BadPassword(val attempts: Int) : UnlockResult()
    data class Error(val message: String) : UnlockResult()
}

class Session(
    private val crypto: CryptoEngine,
    private val storage: StorageRoot,
    private val memory: EncryptedMemoryStore,
    private val prefs: DevicePreferences,
    private val secrets: SecretStore,
) {
    fun lock() {
        memory.lock()
    }

    fun initializeFresh(password: CharArray): WorkspaceManifest {
        storage.ensureLayout()
        val material = crypto.deriveOnSetup(password)
        val encryption = EncryptionMeta(
            encSaltB64 = material.encSalt.toB64(),
            verifySaltB64 = material.verifySalt.toB64(),
            verifyHashB64 = material.verifyHash.toB64(),
            argon2MemoryKib = material.kdf.memoryKiB,
            argon2Iterations = material.kdf.iterations,
            argon2Parallelism = material.kdf.parallelism,
        )
        val models = ModelCatalog.ALL.associate { spec ->
            spec.id to ModelMeta(
                name = spec.displayName,
                path = spec.relativePath,
                sha256 = spec.sha256,
                downloadedAt = null,
                sizeBytes = spec.sizeBytes,
            )
        }
        val manifest = WorkspaceManifest(
            schemaVersion = MANIFEST_SCHEMA_VERSION,
            encryption = encryption,
            models = models,
        )
        storage.writeManifest(manifest)
        memory.unlockWithKey(material.encKey)
        memory.writeNotes(kotlinx.serialization.json.buildJsonObject { })
        memory.writePreferences(kotlinx.serialization.json.buildJsonObject { })
        memory.writeEnabledPlugins(EncryptedMemoryStore.DEFAULT_ENABLED.toList())
        prefs.setupCompleted = true
        prefs.clearFailedAttempts()
        return manifest
    }

    fun unlockWithPassword(password: CharArray): UnlockResult {
        val remaining = prefs.remainingLockMs()
        if (remaining > 0) return UnlockResult.LockedOut(remaining)
        val manifest = storage.readManifest()
            ?: return UnlockResult.Error("No manifest.json in the chosen folder")
        val kdf = CryptoEngine.KdfParams(
            memoryKiB = manifest.encryption.argon2MemoryKib,
            iterations = manifest.encryption.argon2Iterations,
            parallelism = manifest.encryption.argon2Parallelism,
        )
        val ok = crypto.verifyPassword(
            password,
            manifest.encryption.verifySaltB64.fromB64(),
            manifest.encryption.verifyHashB64.fromB64(),
            kdf,
        )
        if (!ok) {
            val until = prefs.recordFailedAttempt()
            val left = (until - System.currentTimeMillis()).coerceAtLeast(0L)
            return if (left > 0) UnlockResult.LockedOut(left)
            else UnlockResult.BadPassword(prefs.failedAttempts)
        }
        val key = crypto.deriveEncryptionKey(password, manifest.encryption.encSaltB64.fromB64(), kdf)
        memory.unlockWithKey(key)
        prefs.clearFailedAttempts()
        return UnlockResult.Ok
    }

    fun cacheKeyForBiometric() {
        if (!memory.isUnlocked) return
        // Re-derive is not stored; the in-memory key is wrapped.
        // Caller passes the current key via memory.requireKey().
        secrets.cacheDerivedKeyForBiometric(memory.requireKey())
        prefs.biometricUnlockEnabled = true
    }

    fun unlockWithUnwrappedKey(key: ByteArray): UnlockResult {
        val remaining = prefs.remainingLockMs()
        if (remaining > 0) return UnlockResult.LockedOut(remaining)
        memory.unlockWithKey(key)
        prefs.clearFailedAttempts()
        return UnlockResult.Ok
    }

    fun markModelDownloaded(id: String) {
        val manifest = storage.readManifest() ?: return
        val spec = ModelCatalog.byId(id)
        val models = manifest.models.toMutableMap()
        models[id] = ModelMeta(
            name = spec.displayName,
            path = spec.relativePath,
            sha256 = spec.sha256,
            downloadedAt = Instant.now().toString(),
            sizeBytes = spec.sizeBytes,
        )
        storage.writeManifest(manifest.copy(models = models))
    }
}
