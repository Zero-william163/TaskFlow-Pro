package com.taskflow.app.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.taskflow.app.MainActivity
import com.taskflow.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Foreground service that downloads an update APK across multiple mirrors with a
 * progress notification, verifies its SHA-256, and hands it to the system installer
 * via a [FileProvider] content uri. Runs as `dataSync` to satisfy Android 14.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var notificationManager: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        ensureChannels()
        val notif = buildProgressNotification(0, 0, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val urls = intent?.getStringArrayExtra(EXTRA_URLS)?.toList().orEmpty()
        val sha256 = intent?.getStringExtra(EXTRA_SHA256)
        if (urls.isEmpty()) {
            stopSelf(); return START_NOT_STICKY
        }
        scope.launch { runDownload(urls, sha256) }
        return START_NOT_STICKY
    }

    private suspend fun runDownload(urls: List<String>, expectedSha256: String?) {
        val outputDir = File(getExternalFilesDir(null), "updates").apply { mkdirs() }
        var lastError: String? = null

        for ((i, url) in urls.withIndex()) {
            UpdateLogger.i("[$i/${urls.size}] Trying: $url")
            updateNotification(buildProgressNotification(0, 0, true))
            val target = File(outputDir, "taskflow-update.apk")
            target.delete()
            try {
                val ok = downloadFile(url, target) { read, total ->
                    val percent = if (total > 0) ((read * 100) / total).toInt() else 0
                    updateNotification(buildProgressNotification(percent, total, false))
                }
                if (!ok) { lastError = "下载中断"; UpdateLogger.w("[$i] downloadFile returned false for $url"); continue }

                if (!expectedSha256.isNullOrBlank()) {
                    val actual = sha256(target)
                    if (!actual.equals(expectedSha256, ignoreCase = true)) {
                        UpdateLogger.w("[$i] SHA256 mismatch: expected=$expectedSha256 actual=$actual")
                        target.delete()
                        lastError = "安装包校验失败"
                        continue
                    }
                }

                installApk(target)
                stopSelf()
                return
            } catch (t: Throwable) {
                UpdateLogger.e("[$i] Download failed for $url", t)
                lastError = t.message ?: "下载失败"
            }
        }

        updateNotification(buildErrorNotification(lastError ?: getString(R.string.update_download_failed)))
        stopSelf()
    }

    private fun downloadFile(url: String, target: File, onProgress: (Long, Long) -> Unit): Boolean {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        val request = Request.Builder().url(url).header("User-Agent", "TaskFlow-Updater").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return false
            val body = response.body ?: return false
            val total = body.contentLength()
            var lastReport = 0L
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var read: Int
                    var totalRead = 0L
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        totalRead += read
                        if (totalRead - lastReport > 256 * 1024 || total in 1..totalRead) {
                            onProgress(totalRead, total)
                            lastReport = totalRead
                        }
                    }
                    output.flush()
                }
            }
            return true
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun installApk(file: File) {
        val authority = "${packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(this, authority, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    // ---- Notifications ----

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_DOWNLOAD,
                getString(R.string.notif_channel_download),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.notif_channel_download_desc) }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildProgressNotification(percent: Int, totalBytes: Long, indeterminate: Boolean): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_DOWNLOAD)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.update_downloading))
            .setProgress(100, percent, indeterminate)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(tapIntent)
            .build()
    }

    private fun buildErrorNotification(message: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_DOWNLOAD)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(tapIntent)
            .build()
    }

    private fun updateNotification(notification: Notification) {
        notificationManager.notify(NOTIF_ID, notification)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 4201
        private const val CHANNEL_DOWNLOAD = "update_download"
        const val EXTRA_URLS = "extra_urls"
        const val EXTRA_SHA256 = "extra_sha256"

        fun start(context: Context, urls: List<String>, sha256: String?) {
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_URLS, urls.toTypedArray())
                if (!sha256.isNullOrBlank()) putExtra(EXTRA_SHA256, sha256)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }
}
