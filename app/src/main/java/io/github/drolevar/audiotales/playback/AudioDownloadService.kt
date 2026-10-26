package io.github.drolevar.audiotales.playback

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import dagger.hilt.android.AndroidEntryPoint
import io.github.drolevar.audiotales.MainActivity
import io.github.drolevar.audiotales.R
import javax.inject.Inject

@AndroidEntryPoint
@androidx.annotation.OptIn(UnstableApi::class)
class AudioDownloadService : DownloadService(
    1002,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    "audiotales-downloads",
    R.string.download_notification_channel,
    0,
) {
    @Inject lateinit var storage: MediaStorage

    override fun getDownloadManager(): DownloadManager = storage.downloadManager

    // Media3 uses this persisted restart job only below API 31; retain its modern-platform policy.
    override fun getScheduler(): Scheduler = createDownloadRestartScheduler(this)

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification {
        val intent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val knownTotal = downloads.isNotEmpty() && downloads.all { it.contentLength > 0 }
        val total = downloads.sumOf { it.contentLength.coerceAtLeast(0) }
        val bytes = downloads.sumOf { it.bytesDownloaded }
        val percent = if (knownTotal && total > 0) ((bytes.toDouble() / total) * 100).toInt().coerceIn(0, 100) else 0
        return NotificationCompat.Builder(this, "audiotales-downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.download_notification_title))
            .setContentText(getString(if (notMetRequirements == 0) R.string.download_notification_running else R.string.download_notification_waiting))
            .setContentIntent(intent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, !knownTotal)
            .build()
    }
}

// Keep this identity stable so a recreated service can replace or cancel its previous restart job.
private const val DOWNLOAD_RESTART_JOB_ID = 1003

@androidx.annotation.OptIn(UnstableApi::class)
internal fun createDownloadRestartScheduler(context: Context): Scheduler =
    PlatformScheduler(context, DOWNLOAD_RESTART_JOB_ID)
