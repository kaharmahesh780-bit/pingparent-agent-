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
import android.app.usage.UsageStatsManager
import android.app.usage.UsageEvents
import java.util.Calendar
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
    private val PING_INTERVAL = 15 * 60 * 1000L // 15 minute (Firebase kharcha kam)

    private val pingRunnable = object : Runnable {
        override fun run() {
            sendDevicePing()
            syncUsageReport()
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
        handler.postDelayed({ syncUsageReport() }, 3000L)
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

        ChildAuth.ensure {
            db.collection("devices").document(deviceId)
                .set(pingData, SetOptions.merge())
        }
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

                ChildAuth.ensure {
                    db.collection("devices").document(deviceId)
                        .set(mapOf("installedApps" to distinctApps), SetOptions.merge())
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    // ---------- Aaj ka Screen Time report (har app kitni der + kitni baar khula) ----------
    private fun syncUsageReport() {
        val prefs = getSharedPreferences("PingParentPrefs", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("pairing_code", null)
            ?: prefs.getString("paired_code", "") ?: ""
        if (deviceId.isEmpty()) return

        Thread {
            try {
                val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return@Thread
                val cal = Calendar.getInstance()
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                val startTime = cal.timeInMillis
                val endTime = System.currentTimeMillis()

                // Har app kitni der chala (minute mein)
                val stats = usm.queryAndAggregateUsageStats(startTime, endTime)
                val minutesByPkg = HashMap<String, Int>()
                for ((pkg, usage) in stats) {
                    val mins = (usage.totalTimeInForeground / 60000L).toInt()
                    if (mins > 0) minutesByPkg[pkg] = mins
                }

                // Har app kitni baar foreground mein aaya (open count)
                val openCountByPkg = HashMap<String, Int>()
                try {
                    val events = usm.queryEvents(startTime, endTime)
                    val e = UsageEvents.Event()
                    while (events.hasNextEvent()) {
                        events.getNextEvent(e)
                        if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                            e.eventType == UsageEvents.Event.ACTIVITY_RESUMED
                        ) {
                            openCountByPkg[e.packageName] = (openCountByPkg[e.packageName] ?: 0) + 1
                        }
                    }
                } catch (e: Exception) {
                }

                val pm = packageManager
                val rows = minutesByPkg.entries
                    .sortedByDescending { it.value }
                    .take(10)
                    .map { (pkg, mins) ->
                        val label = try {
                            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                        } catch (e: Exception) { pkg }
                        hashMapOf(
                            "package" to pkg,
                            "name" to label,
                            "minutes" to mins,
                            "opens" to (openCountByPkg[pkg] ?: 0)
                        )
                    }

                ChildAuth.ensure {
                    db.collection("devices").document(deviceId)
                        .set(
                            mapOf(
                                "appUsageToday" to rows,
                                "appUsageDate" to java.text.SimpleDateFormat("yyyy-MM-dd").format(java.util.Date())
                            ),
                            SetOptions.merge()
                        )
                }
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
