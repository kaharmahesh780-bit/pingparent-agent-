package com.pingparent.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

class PingService : Service() {

    private val db by lazy { FirebaseFirestore.getInstance() }
    private val handler = Handler(Looper.getMainLooper())
    private val PING_INTERVAL = 60000L // 60s battery-friendly timer

    private val pingRunnable = object : Runnable {
        override fun run() {
            sendDevicePing()
            handler.postDelayed(this, PING_INTERVAL)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = createNotification()
        startForeground(101, notification)

        // सर्विस शुरू होते ही तुरंत ऐप्स स्कैन करें
        handler.postDelayed({ syncInstalledApps() }, 2000L)
        handler.post(pingRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.postDelayed({ syncInstalledApps() }, 1000L)
        return START_STICKY
    }

    private fun sendDevicePing() {
        val prefs = getSharedPreferences("PingParentPrefs", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("pairing_code", null) 
            ?: prefs.getString("paired_code", "") ?: ""

        if (deviceId.isEmpty()) return

        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
            applicationContext.registerReceiver(null, filter)
        }

        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level != -1 && scale != -1) (level * 100 / scale.toFloat()).toInt() else 0

        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || 
                         status == BatteryManager.BATTERY_STATUS_FULL

        val pingData = hashMapOf(
            "battery" to batteryPct,
            "charging" to isCharging,
            "lastSeen" to FieldValue.serverTimestamp()
        )

        db.collection("devices").document(deviceId)
            .set(pingData, SetOptions.merge())
    }

    private fun syncInstalledApps() {
        val prefs = getSharedPreferences("PingParentPrefs", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("pairing_code", null) 
            ?: prefs.getString("paired_code", "") ?: ""

        if (deviceId.isEmpty()) return

        Thread {
            try {
                val pm = packageManager
                val intent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val resolveInfoList = pm.queryIntentActivities(intent, 0)
                val appsList = ArrayList<HashMap<String, String>>()

                for (info in resolveInfoList) {
                    val pkg = info.activityInfo.packageName
                    if (pkg == packageName) continue

                    val name = info.loadLabel(pm).toString()
                    val item = HashMap<String, String>()
                    item["name"] = name
                    item["package"] = pkg
                    appsList.add(item)
                }

                // डुप्लिकेट्स हटाएँ
                val distinctApps = appsList.distinctBy { it["package"] }

                db.collection("devices").document(deviceId)
                    .set(mapOf("installedApps" to distinctApps), SetOptions.merge())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "PingParentServiceChannel",
                "PingParent Protection Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "PingParentServiceChannel")
            .setContentTitle("PingParent Shield Active")
            .setContentText("Child device protection is active in background")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(pingRunnable)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
