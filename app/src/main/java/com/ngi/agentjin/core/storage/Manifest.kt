package com.ngi.agentjin.core.storage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val MANIFEST_SCHEMA_VERSION = 1

@Serializable
data class WorkspaceManifest(
    @SerialName("schema_version") val schemaVersion: Int = MANIFEST_SCHEMA_VERSION,
    val encryption: EncryptionMeta,
    val models: Map<String, ModelMeta> = emptyMap(),
)

@Serializable
data class EncryptionMeta(
    val kdf: String = "argon2id",
    val cipher: String = "AES-256-GCM",
    @SerialName("salt") val encSaltB64: String,
    @SerialName("verify_salt") val verifySaltB64: String,
    @SerialName("verify_hash") val verifyHashB64: String,
    @SerialName("argon2_memory_kib") val argon2MemoryKib: Int,
    @SerialName("argon2_iterations") val argon2Iterations: Int,
    @SerialName("argon2_parallelism") val argon2Parallelism: Int,
)

@Serializable
data class ModelMeta(
    val name: String,
    val path: String,
    val sha256: String,
    @SerialName("downloaded_at") val downloadedAt: String? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
)
