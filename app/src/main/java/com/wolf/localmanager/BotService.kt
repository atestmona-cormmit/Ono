package com.wolf.localmanager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.util.Calendar
import java.util.concurrent.Executors

class BotService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private var wake: PowerManager.WakeLock? = null
    private val lastDesired = HashMap<String, Boolean>()

    private val ticker = object : Runnable {
        override fun run() {
            exec.execute { try { doTick() } catch (e: Exception) { } }
            handler.postDelayed(this, 20_000)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "WOLF Local", NotificationManager.IMPORTANCE_LOW))
        val n = notif("المحرك يعمل")
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wolflocal:service").apply { acquire() }
        NodeHost.ensureStarted(this)
        handler.post(ticker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        try { wake?.release() } catch (e: Exception) { }
        super.onDestroy()
    }

    private fun notif(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH)
            .setContentTitle("WOLF Local")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun startIt(b: Bot): String =
        NodeClient.start(b.id, BotStore.dir(this, b.id).absolutePath, b.entry)

    private fun doTick() {
        if (!NodeClient.ready()) return
        val st = NodeClient.statusSafe()
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        for (b in BotStore.all(this)) {
            if (!b.auto) { lastDesired.remove(b.id); continue }
            val want = b.hours[hour]
            val s = st[b.id]
            val running = s?.optBoolean("running", false) == true
            val manual = s?.optBoolean("manual", false) == true
            val ended = s?.optLong("endedAt", 0L) ?: 0L
            if (lastDesired[b.id] != want) {
                lastDesired[b.id] = want
                if (want && !running) {
                    val r = startIt(b)
                    if (r != "ok" && r != "already running") lastDesired.remove(b.id)
                } else if (!want && running) NodeClient.stop(b.id)
            } else if (want && !running && !manual && ended > 0 && System.currentTimeMillis() - ended > 15_000) {
                startIt(b) // crash / self-exit recovery
            }
        }
        val n = NodeClient.statusSafe().values.count { it.optBoolean("running", false) }
        getSystemService(NotificationManager::class.java).notify(1, notif("$n بوت يعمل الآن"))
    }

    companion object { const val CH = "wolf_local" }
}
