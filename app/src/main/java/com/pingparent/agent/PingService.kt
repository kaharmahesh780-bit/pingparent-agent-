package com.pingparent.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Calendar

class PingService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var db: FirebaseFirestore
    private val pingInterval = 60000L // 1 minute

    private val pingRunnable = object : Runnable {
        override fun run() {
            sendPing()
            handler.postDelayed(this, pingInterval)
        }
    }

    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        db = FirebaseFirestore.getInstance()
        startForegroundServiceWithNotification()
        handler.post(pingRunnable)
    }

    private fun startForegroundServiceWithNotification() {
        val channelId = "pingparent_agent_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "PingParent Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("PingParent Agent Active")
                .setContentText("Monitoring device in background")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("PingParent Agent Active")
                .setContentText("Monitoring device in background")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .build()
        }

        startForeground(101, notification)
    }

    private fun sendPing() {
        val prefs = getSharedPreferences("PingParentPrefs", Context.MODE_PRIVATE)
        val code = prefs.getString("paired_code", null) ?: return

        val (batteryLevel, isCharging) = getBatteryInfo()
        val screenTimeMinutes = getDailyScreenTimeMinutes()

        val updates = hashMapOf<String, Any>(
            "status" to "paired",
            "isOnline" to true,
            "lastSeen" to System.currentTimeMillis(),
            "batteryLevel" to batteryLevel,
            "isCharging" to isCharging,
            "dailyScreenTimeMinutes" to screenTimeMinutes
        )

        db.collection("devices").document(code).update(updates)
    }

    private fun getDailyScreenTimeMinutes(): Long {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return 0L
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return 0L

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()

        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        )

        var totalTimeMillis = 0L
        stats?.forEach {
            totalTimeMillis += it.totalTimeInForeground
        }

        return totalTimeMillis / (1000 * 60)
    }

    private fun getBatteryInfo(): Pair<Int, Boolean> {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = registerReceiver(null, filter)

        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1

        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        return Pair(batteryPct, isCharging)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(pingRunnable)
    }
}
