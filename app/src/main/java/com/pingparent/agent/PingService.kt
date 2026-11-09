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
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import java.util.Calendar

class PingService : Service() {

    private val db by lazy { FirebaseFirestore.getInstance() }
    private val handler = Handler(Looper.getMainLooper())
    private var pauseListener: ListenerRegistration? = null
    private val pingInterval = 15000L // 15 सेकंड में बैटरी व ऑनलाइन हार्टबीट सिंक

    private val pingRunnable = object : Runnable {
        override fun run() {
            sendPingToFirebase()
            handler.postDelayed(this, pingInterval)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(101, createNotification())
        listenForRemotePause()
        handler.post(pingRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    // 1. रिमोट पॉज लिसनर - जैसे ही पैरेंट डैशबोर्ड से पॉज दबाएगा, यह स्क्रीन ऊपर खींच लाएगा
    private fun listenForRemotePause() {
        val prefs = getSharedPreferences("PingParentPrefs", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("pairing_code", null) 
            ?: prefs.getString("paired_code", "") ?: ""

        if (deviceId.isNotEmpty()) {
            pauseListener = db.collection("devices").document(deviceId)
                .addSnapshotListener { snapshot, _ ->
                    if (snapshot != null && snapshot.exists()) {
                        val isPaused = snapshot.getBoolean("isPaused") ?: false
                        if (isPaused) {
                            val pauseIntent = Intent(this, PauseActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            }
                            startActivity(pauseIntent)
                        }
                    }
                }
        }
    }

    // 2. डिवाइस का लाइव स्टेटस, बैटरी और स्क्रीन-टाइम भेजना
    private fun sendPingToFirebase() {
        val prefs = getSharedPreferences("PingParentPrefs", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("pairing_code", null) 
            ?: prefs.getString("paired_code", "") ?: ""

        if (deviceId.isEmpty()) return

        val batteryLevel = getBatteryPercentage()
        val isCharging = isDeviceCharging()
        val screenTimeMinutes = getDailyScreenTimeMinutes()

        val data = hashMapOf(
            "lastSeen" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
            "battery" to batteryLevel,
            "charging" to isCharging,
            "status" to "online",
            "dailyScreenTimeMinutes" to screenTimeMinutes
        )

        db.collection("devices").document(deviceId)
            .set(data, SetOptions.merge())
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

    private fun getBatteryPercentage(): Int {
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = registerReceiver(null, ifilter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) ((level / scale.toFloat()) * 100).toInt() else 0
    }

    private fun isDeviceCharging(): Boolean {
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = registerReceiver(null, ifilter)
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "PingParentShield",
                "PingParent Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "PingParentShield")
            .setContentTitle("PingParent Shield Active")
            .setContentText("Keeping this device safe & monitored")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(pingRunnable)
        pauseListener?.remove()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
