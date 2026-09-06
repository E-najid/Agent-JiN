package com.ngi.agentjin.core.screen

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * MediaProjection screenshot capture. Requires a one-time system consent
 * which MainActivity requests when the vision fallback is first needed.
 */
class ScreenshotCapture(context: Context) {
    private val app = context.applicationContext
    private val mpm = app.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    val permissionIntent: Intent get() = mpm.createScreenCaptureIntent()

    private val _result = MutableStateFlow<Pair<Int, Intent>?>(null)

    fun onPermissionResult(resultCode: Int, data: Intent?) {
        if (data != null) {
            _result.value = resultCode to data
            ScreenCaptureService.start(app, resultCode, data)
        }
    }

    suspend fun captureRgb(timeoutMs: Long = 8_000L): Triple<ByteArray, Int, Int>? {
        JinAccessibilityService.instance?.takeScreenshotRgb()?.let { return it }

        if (_result.value == null) {
            withTimeoutOrNull(timeoutMs) { _result.filterNotNull().first() } ?: return null
        }
        return ScreenCaptureService.awaitFrame(timeoutMs)
    }
}
