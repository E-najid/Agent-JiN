package com.ngi.agentjin.core.inference

/**
 * On-device GGUF catalog. SHA-256 values are the Hugging Face LFS object ids
 * of the exact files we download.
 */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val filename: String,
    val relativePath: String,
    val sha256: String,
    val sizeBytes: Long,
    val sources: List<String>,
    val role: Role,
) {
    enum class Role { TEXT, VISION, MMPROJ }
}

object ModelCatalog {
    val TEXT = ModelSpec(
        id = "text",
        displayName = "LFM2.5-350M Instruct Q4_K_M",
        filename = "LFM2.5-350M-Q4_K_M.gguf",
        relativePath = "models/LFM2.5-350M-Q4_K_M.gguf",
        sha256 = "7e6f72643caafc9a68256686638c4d7916f2cec76d1df478d4c3ddcd95a6aed4",
        sizeBytes = 229_312_224L,
        sources = listOf(
            "https://huggingface.co/LiquidAI/LFM2.5-350M-GGUF/resolve/main/LFM2.5-350M-Q4_K_M.gguf",
            "https://hf-mirror.com/LiquidAI/LFM2.5-350M-GGUF/resolve/main/LFM2.5-350M-Q4_K_M.gguf",
        ),
        role = ModelSpec.Role.TEXT,
    )

    val VISION = ModelSpec(
        id = "vision",
        displayName = "LFM2.5-VL-1.6B Q4_K_M",
        filename = "LFM2.5-VL-1.6B-Q4_K_M.gguf",
        relativePath = "models/LFM2.5-VL-1.6B-Q4_K_M.gguf",
        sha256 = "aefc3c97c9eb30d9c0dd6af4c38250f5f5106b57c8cf92de7914c7d0a9c94da2",
        sizeBytes = 730_896_256L,
        sources = listOf(
            "https://huggingface.co/LiquidAI/LFM2.5-VL-1.6B-GGUF/resolve/main/LFM2.5-VL-1.6B-Q4_K_M.gguf",
            "https://hf-mirror.com/LiquidAI/LFM2.5-VL-1.6B-GGUF/resolve/main/LFM2.5-VL-1.6B-Q4_K_M.gguf",
        ),
        role = ModelSpec.Role.VISION,
    )

    val VISION_PROJ = ModelSpec(
        id = "vision_mmproj",
        displayName = "LFM2.5-VL-1.6B mmproj Q8_0",
        filename = "mmproj-LFM2.5-VL-1.6b-Q8_0.gguf",
        relativePath = "models/mmproj-LFM2.5-VL-1.6b-Q8_0.gguf",
        sha256 = "2ce89e610c56f3198ece2b86cf61743a08b9307279c89125eb2412ebb908689d",
        sizeBytes = 583_109_888L,
        sources = listOf(
            "https://huggingface.co/LiquidAI/LFM2.5-VL-1.6B-GGUF/resolve/main/mmproj-LFM2.5-VL-1.6b-Q8_0.gguf",
            "https://hf-mirror.com/LiquidAI/LFM2.5-VL-1.6B-GGUF/resolve/main/mmproj-LFM2.5-VL-1.6b-Q8_0.gguf",
        ),
        role = ModelSpec.Role.MMPROJ,
    )

    val ALL: List<ModelSpec> = listOf(TEXT, VISION, VISION_PROJ)

    fun byId(id: String): ModelSpec =
        ALL.firstOrNull { it.id == id } ?: error("unknown model id $id")

    fun manualInstructions(spec: ModelSpec): String {
        return buildString {
            appendLine("Automatic download failed for ${spec.filename}.")
            appendLine("Version / file: ${spec.filename}")
            appendLine("Expected SHA-256: ${spec.sha256}")
            appendLine("Expected size: ${spec.sizeBytes} bytes")
            appendLine()
            appendLine("Manual install:")
            appendLine("1. Download the file from one of:")
            spec.sources.forEach { appendLine("   - $it") }
            appendLine("2. Copy it into the models/ folder of your Agent JiN workspace.")
            appendLine("3. Return to the app; checksum will be verified before the model is used.")
        }
    }
}
