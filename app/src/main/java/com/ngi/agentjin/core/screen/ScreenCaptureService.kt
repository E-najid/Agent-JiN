package com.ngi.agentjin.core.screen

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.ngi.agentjin.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Holds a MediaProjection in a mediaProjection FGS (required on Android 10+ / 14+).
 * Started as soon as the user grants the capture permission.
 */
class ScreenCaptureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel()
        val notif = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Agent JiN")
            .setContentText("Screen capture permission is active for vision fallback")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF, notif)
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(resultCode, data)
        instance = this
        return START_STICKY
    }

    override fun onDestroy() {
        virtualDisplay?.release()
        virtualDisplay = null
        reader?.close()
        reader = null
        projection?.stop()
        projection = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun captureOnce(): CompletableDeferred<Triple<ByteArray, Int, Int>> {
        val deferred = CompletableDeferred<Triple<ByteArray, Int, Int>>()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        var width = metrics.widthPixels
        var height = metrics.heightPixels
        val density = metrics.densityDpi
        val maxDim = 720
        if (width > maxDim || height > maxDim) {
            val scale = maxDim.toFloat() / maxOf(width, height)
            width = (width * scale).toInt().coerceAtLeast(1)
            height = (height * scale).toInt().coerceAtLeast(1)
        }
        virtualDisplay?.release()
        reader?.close()
        val imgReader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
        reader = imgReader
        imgReader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val buf = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                val bitmap = android.graphics.Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    android.graphics.Bitmap.Config.ARGB_8888,
                )
                bitmap.copyPixelsFromBuffer(buf)
                val cropped = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, width, height)
                bitmap.recycle()
                val pixels = IntArray(width * height)
                cropped.getPixels(pixels, 0, width, 0, 0, width, height)
                cropped.recycle()
                val rgb = ByteArray(width * height * 3)
                var o = 0
                for (p in pixels) {
                    rgb[o++] = ((p shr 16) and 0xFF).toByte()
                    rgb[o++] = ((p shr 8) and 0xFF).toByte()
                    rgb[o++] = (p and 0xFF).toByte()
                }
                if (!deferred.isCompleted) deferred.complete(Triple(rgb, width, height))
            } catch (t: Throwable) {
                if (!deferred.isCompleted) deferred.completeExceptionally(t)
            } finally {
                image.close()
                virtualDisplay?.release()
                virtualDisplay = null
                reader?.close()
                reader = null
            }
        }, null)
        virtualDisplay = projection?.createVirtualDisplay(
            "agentjin-capture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imgReader.surface,
            null,
            null,
        )
        if (virtualDisplay == null && !deferred.isCompleted) {
            deferred.completeExceptionally(IllegalStateException("VirtualDisplay was not created"))
        }
        return deferred
    }

    private fun ensureChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.capture_channel_name), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        private const val CHANNEL = "screen_capture"
        private const val NOTIF = 43
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"

        @Volatile
        private var instance: ScreenCaptureService? = null

        @Volatile
        private var projection: MediaProjection? = null
        private var virtualDisplay: VirtualDisplay? = null
        private var reader: ImageReader? = null

        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, ScreenCaptureService::class.java)
            i.putExtra(EXTRA_CODE, resultCode)
            i.putExtra(EXTRA_DATA, data)
            context.startForegroundService(i)
        }

        suspend fun awaitFrame(timeoutMs: Long): Triple<ByteArray, Int, Int>? {
            val svc = instance ?: return null
            return withTimeoutOrNull(timeoutMs) { svc.captureOnce().await() }
        }
    }
}
