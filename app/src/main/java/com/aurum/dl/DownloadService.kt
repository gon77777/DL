package com.aurum.dl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ダウンロードを実行するフォアグラウンドサービス。
 * 通知を表示し続けることで、アプリがバックグラウンドへ移っても処理が止まらない。
 * 同時に実行する件数は設定画面の値 ([Prefs.maxConcurrent]) に従う。
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastSignature = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> DownloadManager.cancelAll()
            ACTION_PAUSE -> DownloadManager.pauseAll()
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildProgressNotification(emptyList(), 0, 0),
            foregroundType(),
        )
        kick()
        return START_NOT_STICKY
    }

    /**
     * Android 14 以降は specialUse で動かす。Android 15 では dataSync に「24 時間で合計 6 時間」の制限があり、
     * 長い動画や大量のダウンロードの途中でサービスが止められてしまうため。
     */
    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

    /** Android 15 以降: dataSync サービスの制限時間に達したときに呼ばれる。 */
    override fun onTimeout(startId: Int, fgsType: Int) {
        DownloadManager.pauseAll()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun kick() {
        if (job?.isActive == true) return
        acquireWakeLock()
        job = scope.launch {
            val watcher = launch {
                DownloadManager.items.collect { refreshNotification(it) }
            }
            val active = mutableListOf<Job>()
            try {
                while (true) {
                    active.removeAll { it.isCompleted }
                    // Wi-Fi 限定の設定を現在の回線に合わせる (モバイルなら一時停止、Wi-Fi に戻れば自動再開)
                    DownloadManager.applyNetworkPolicy(applicationContext)
                    // 同時実行数は途中で変更しても次の周回から反映される
                    val limit = Prefs.maxConcurrent(applicationContext)
                    while (active.size < limit) {
                        val next = DownloadManager.claimNext() ?: break
                        active += launch(Dispatchers.IO) {
                            runCatching { DownloadManager.run(applicationContext, next) }
                            DownloadManager.find(next.id)?.let { notifyFinished(it) }
                        }
                    }
                    val idle = active.isEmpty() && DownloadManager.nextQueued() == null
                    val waitingWifi = DownloadManager.hasWaitingForWifi()
                    if (idle && !waitingWifi) break
                    if (idle) {
                        // Wi-Fi 待ちだけが残っている間は、電源ロックを手放してゆっくり見張る
                        releaseWakeLock()
                        delay(1500)
                    } else {
                        acquireWakeLock()
                        delay(400)
                    }
                }
            } finally {
                watcher.cancel()
            }
            withContext(Dispatchers.Main) {
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                // 終了処理の隙間に追加された分があれば、サービスを起こし直す
                if (DownloadManager.nextQueued() != null) DownloadManager.startService(applicationContext)
            }
        }
    }

    // ------------------------------------------------------------ notification

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "ダウンロード進行", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "ダウンロード結果", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun refreshNotification(items: List<DownloadItem>) {
        val running = items.filter { it.status == Status.RUNNING }
        val queued = items.count { it.status == Status.QUEUED }
        val waitingWifi = items.count { it.status == Status.PAUSED && it.waitingForWifi }
        val avg = if (running.isEmpty()) 0 else (running.map { it.progress }.average() * 100).toInt()
        val first = running.firstOrNull()
        val signature = "${running.size}|$avg|${first?.id}|${first?.title}|${first?.message}|$queued|$waitingWifi"
        if (signature == lastSignature) return
        lastSignature = signature
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildProgressNotification(running, queued, waitingWifi))
    }

    private fun serviceIntent(requestCode: Int, action: String): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, DownloadService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun buildProgressNotification(running: List<DownloadItem>, queued: Int, waitingWifi: Int): Notification {
        val pct = if (running.isEmpty()) 0 else (running.map { it.progress }.average() * 100).toInt()
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val title = when {
            running.size == 1 -> running[0].title ?: "きゅうりDL"
            running.size > 1 -> "${running.size} 件をダウンロード中"
            else -> "きゅうりDL"
        }
        val detail = when {
            running.isEmpty() && waitingWifi > 0 -> "Wi-Fi接続待ち ($waitingWifi 件)"
            running.isEmpty() -> "待機中"
            running.size == 1 && running[0].message.isNotBlank() -> "$pct%  ${running[0].message}"
            else -> "$pct%"
        } + if (queued > 0) "  (待機 $queued 件)" else ""

        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(detail)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(openApp)
            .setProgress(100, pct, if (running.isEmpty()) waitingWifi == 0 else pct == 0)
            .addAction(0, "一時停止", serviceIntent(1, ACTION_PAUSE))
            .addAction(0, "停止", serviceIntent(2, ACTION_CANCEL))
            .build()
    }

    private fun notifyFinished(item: DownloadItem) {
        if (item.status != Status.DONE && item.status != Status.FAILED) return
        val ok = item.status == Status.DONE
        val openApp = PendingIntent.getActivity(
            this,
            3,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(if (ok) "ダウンロード完了" else "ダウンロード失敗")
            .setContentText(item.title ?: item.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(item.message.ifBlank { item.title ?: "" }))
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        getSystemService(NotificationManager::class.java).notify(item.id.hashCode(), n)
    }

    // --------------------------------------------------------------- wake lock

    private fun acquireWakeLock() {
        acquireWifiLock()
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AurumDL:download").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    /** 画面が消えても Wi-Fi が省電力状態に入って通信が途切れないようにする。 */
    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AurumDL:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wifiLock = null
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    companion object {
        private const val CHANNEL_PROGRESS = "aurum_progress"
        private const val CHANNEL_DONE = "aurum_done"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_CANCEL = "com.aurum.dl.action.CANCEL_ALL"
        const val ACTION_PAUSE = "com.aurum.dl.action.PAUSE_ALL"
    }
}
