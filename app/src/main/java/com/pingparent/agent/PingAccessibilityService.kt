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
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions

class PingService : Service() {

    private val db by lazy { FirebaseFirestore.getInstance() }
    private val handler = Handler(Looper.getMainLooper())
    private var pauseListener: ListenerRegistration? = null

    private val pingRunnable = object : Runnable {
        override fun run() {
            sendPingToFirebase()
            handler.postDelayed(this, 15000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, createNotification())
        listenForRemotePause()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.removeCallbacks(pingRunnable)
        handler.post(pingRunnable)
        return START_STICKY
    }

    private fun listenForRemotePause() {
        val prefs = getSharedPreferences("PingParentPrefs", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("pairing_code", "") ?: ""

        if (deviceId.isNotEmpty()) {
            pauseListener = db.collection("devices").document(deviceId)
                .addSnapshotListener { snapshot, _ ->
                    if (snapshot != null && snapshot.exists()) {
                        val isPaused = snapshot.getBoolean("isPaused") ?: false
                        if (isPaused) {
                            val pauseIntent = Intent(this, PauseActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            }
                            startActivity(pauseIntent)
                        }
                    }
                }
        }
    }

    private fun sendPingToFirebase() {
        val prefs = getSharedPreferences("PingParentPrefs", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("pairing_code", "") ?: ""

        if (deviceId.isEmpty()) return

        val batteryLevel = getBatteryPercentage()
        val isCharging = isDeviceCharging()

        val data = hashMapOf(
            "lastSeen" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
            "battery" to batteryLevel,
            "charging" to isCharging,
            "status" to "online"
        )

        db.collection("devices").document(deviceId)
            .set(data, SetOptions.merge())
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
                "PingParent Background Protection",
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
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
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
