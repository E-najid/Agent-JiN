package com.ngi.agentjin.core.inference

import android.app.ActivityManager
import android.content.Context

/**
 * 3GB phones are the target. Detect total RAM and degrade: smaller context,
 * never keep the vision model resident, fewer threads.
 */
class RamPolicy(context: Context) {
    private val info: ActivityManager.MemoryInfo = ActivityManager.MemoryInfo().also { mi ->
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.getMemoryInfo(mi)
    }

    val totalRamBytes: Long = info.totalMem
    val totalRamMb: Long = totalRamBytes / (1024L * 1024L)
    val constrained: Boolean = totalRamBytes <= 3L * 1024L * 1024L * 1024L + 256L * 1024L * 1024L

    val textContextSize: Int = if (constrained) 1024 else 2048
    val visionContextSize: Int = if (constrained) 512 else 1024
    val nThreads: Int = if (constrained) 2 else 4
    val keepVisionResident: Boolean = !constrained
    val visionIdleUnloadMs: Long = if (constrained) 5_000L else 30_000L
    val useMmap: Boolean = true

    fun summary(): String {
        val gb = totalRamBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return "RAM %.1f GiB · %s · text_ctx=%d · vision_ctx=%d".format(
            gb,
            if (constrained) "constrained" else "normal",
            textContextSize,
            visionContextSize,
        )
    }
}
