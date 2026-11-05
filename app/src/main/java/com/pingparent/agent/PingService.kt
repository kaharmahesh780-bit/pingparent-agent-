package com.pingparent.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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

class PingService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var db: FirebaseFirestore
    private val pingInterval = 60000L // हर 1 मिनट में पिंग भेजेगा

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

        val updates = hashMapOf<String, Any>(
            "status" to "paired",
            "isOnline" to true,
            "lastSeen" to System.currentTimeMillis(),
            "batteryLevel" to batteryLevel,
            "isCharging" to isCharging
        )

        db.collection("devices").document(code).update(updates)
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
