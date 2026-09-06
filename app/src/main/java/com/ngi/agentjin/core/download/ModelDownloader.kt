package com.ngi.agentjin.core.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.ngi.agentjin.core.crypto.toHex
import com.ngi.agentjin.core.inference.ModelCatalog
import com.ngi.agentjin.core.inference.ModelFileStatus
import com.ngi.agentjin.core.inference.ModelManager
import com.ngi.agentjin.core.inference.ModelSpec
import com.ngi.agentjin.core.storage.StorageRoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class DownloadProgress(
    val specId: String,
    val filename: String,
    val bytesRead: Long,
    val totalBytes: Long,
    val source: String,
    val phase: Phase,
    val error: String? = null,
) {
    enum class Phase { CHECKING, DOWNLOADING, VERIFYING, COPYING, DONE, FAILED, SKIPPED }
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (bytesRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
}

class ModelDownloader(
    private val context: Context,
    private val storage: StorageRoot,
    private val models: ModelManager,
    private val wifiOnly: () -> Boolean,
) {
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val cacheDir = File(context.filesDir, "model_downloads").apply { mkdirs() }

    suspend fun ensureAll(onProgress: suspend (DownloadProgress) -> Unit) {
        for (spec in ModelCatalog.ALL) {
            ensure(spec, onProgress)
        }
    }

    suspend fun ensure(spec: ModelSpec, onProgress: suspend (DownloadProgress) -> Unit) = withContext(Dispatchers.IO) {
        onProgress(DownloadProgress(spec.id, spec.filename, 0, spec.sizeBytes, "", DownloadProgress.Phase.CHECKING))
        val check = models.check(spec)
        if (check.status == ModelFileStatus.OK) {
            onProgress(DownloadProgress(spec.id, spec.filename, spec.sizeBytes, spec.sizeBytes, "", DownloadProgress.Phase.SKIPPED))
            return@withContext
        }
        if (wifiOnly() && !onUnmeteredWifi()) {
            onProgress(
                DownloadProgress(
                    spec.id, spec.filename, 0, spec.sizeBytes, "", DownloadProgress.Phase.FAILED,
                    error = "Wi-Fi only is enabled and this network is not Wi-Fi. Connect to Wi-Fi or disable the toggle in Settings.",
                ),
            )
            throw DownloadException("Wi-Fi only")
        }
        val part = File(cacheDir, spec.filename + ".part")
        var lastError: String? = null
        for (url in spec.sources) {
            try {
                downloadResumable(spec, url, part, onProgress)
                onProgress(DownloadProgress(spec.id, spec.filename, part.length(), spec.sizeBytes, url, DownloadProgress.Phase.VERIFYING))
                val sha = Sha256.ofFile(part)
                if (!sha.equals(spec.sha256, ignoreCase = true)) {
                    part.delete()
                    lastError = "SHA-256 mismatch from $url (got $sha, expected ${spec.sha256})"
                    continue
                }
                onProgress(DownloadProgress(spec.id, spec.filename, spec.sizeBytes, spec.sizeBytes, url, DownloadProgress.Phase.COPYING))
                copyToWorkspace(spec, part)
                part.delete()
                onProgress(DownloadProgress(spec.id, spec.filename, spec.sizeBytes, spec.sizeBytes, url, DownloadProgress.Phase.DONE))
                return@withContext
            } catch (t: Throwable) {
                lastError = "${t.javaClass.simpleName}: ${t.message} ($url)"
            }
        }
        val manual = ModelCatalog.manualInstructions(spec) + "\n\nLast error: ${lastError ?: "unknown"}"
        onProgress(DownloadProgress(spec.id, spec.filename, 0, spec.sizeBytes, "", DownloadProgress.Phase.FAILED, error = manual))
        throw DownloadException(manual)
    }

    private fun downloadResumable(
        spec: ModelSpec,
        url: String,
        part: File,
        onProgress: suspend (DownloadProgress) -> Unit,
    ) {
        var existing = if (part.exists()) part.length() else 0L
        if (existing > spec.sizeBytes) {
            part.delete()
            existing = 0L
        }
        val reqBuilder = Request.Builder().url(url).header("User-Agent", "AgentJiN/0.1")
        if (existing > 0L) {
            reqBuilder.header("Range", "bytes=$existing-")
        }
        client.newCall(reqBuilder.build()).execute().use { resp ->
            if (resp.code == 416) {
                // Already complete according to the server.
                return
            }
            if (!resp.isSuccessful && resp.code != 206) {
                throw DownloadException("HTTP ${resp.code} from $url")
            }
            val body = resp.body ?: throw DownloadException("empty body from $url")
            val append = resp.code == 206 && existing > 0L
            val total = when {
                resp.code == 206 -> spec.sizeBytes
                body.contentLength() > 0 -> body.contentLength()
                else -> spec.sizeBytes
            }
            java.io.FileOutputStream(part, append).buffered().use { output ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var written = if (append) existing else 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        written += n
                        kotlinx.coroutines.runBlocking {
                            onProgress(
                                DownloadProgress(
                                    spec.id, spec.filename, written, total, url,
                                    DownloadProgress.Phase.DOWNLOADING,
                                ),
                            )
                        }
                    }
                    output.flush()
                }
            }
        }
    }

    private fun copyToWorkspace(spec: ModelSpec, part: File) {
        val path = spec.relativePath.split("/")
        part.inputStream().use { input ->
            storage.openWriteTruncating(path).use { output -> input.copyTo(output) }
        }
    }

    private fun onUnmeteredWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

class DownloadException(message: String) : Exception(message)
