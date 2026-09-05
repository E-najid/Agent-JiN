package com.ngi.agentjin.core.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ngi.agentjin.AgentJinApp
import com.ngi.agentjin.R
import com.ngi.agentjin.core.inference.ModelCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ModelDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ids = intent?.getStringArrayExtra(EXTRA_IDS)?.toList() ?: ModelCatalog.ALL.map { it.id }
        ensureChannel()
        val notification = buildNotification("Preparing download…", 0, 100, indeterminate = true)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        job?.cancel()
        job = scope.launch {
            val app = application as AgentJinApp
            val downloader = ModelDownloader(
                this@ModelDownloadService,
                app.container.storageRoot,
                app.container.modelManager,
                wifiOnly = { app.container.devicePreferences.wifiOnlyDownloads },
            )
            try {
                _running.value = true
                _lastError.value = null
                for (id in ids) {
                    val spec = ModelCatalog.byId(id)
                    downloader.ensure(spec) { p ->
                        _progress.value = p
                        val pct = (p.fraction * 100).toInt()
                        val text = when (p.phase) {
                            DownloadProgress.Phase.SKIPPED -> "Already present: ${p.filename}"
                            DownloadProgress.Phase.FAILED -> p.error ?: "Failed ${p.filename}"
                            else -> "${p.phase} ${p.filename} $pct%"
                        }
                        notify(text, pct, p.phase == DownloadProgress.Phase.CHECKING)
                    }
                }
                _progress.value = _progress.value?.copy(phase = DownloadProgress.Phase.DONE)
            } catch (t: Throwable) {
                _lastError.value = t.message
            } finally {
                _running.value = false
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.download_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = getString(R.string.download_channel_desc)
                },
            )
        }
    }

    private fun notify(text: String, progress: Int, indeterminate: Boolean) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text, progress, 100, indeterminate))
    }

    private fun buildNotification(text: String, progress: Int, max: Int, indeterminate: Boolean): Notification {
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Agent JiN models")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(max, progress, indeterminate)
            .build()
    }

    companion object {
        const val CHANNEL = "model_downloads"
        const val NOTIF_ID = 42
        const val EXTRA_IDS = "ids"

        private val _progress = MutableStateFlow<DownloadProgress?>(null)
        val progress: StateFlow<DownloadProgress?> = _progress
        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running
        private val _lastError = MutableStateFlow<String?>(null)
        val lastError: StateFlow<String?> = _lastError

        fun start(context: Context, ids: List<String> = ModelCatalog.ALL.map { it.id }) {
            val i = Intent(context, ModelDownloadService::class.java)
            i.putExtra(EXTRA_IDS, ids.toTypedArray())
            context.startForegroundService(i)
        }
    }
}
