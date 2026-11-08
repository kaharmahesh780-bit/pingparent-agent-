package com.pingparent.agent

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Calendar

class MainActivity : Activity() {

    private lateinit var db: FirebaseFirestore
    private lateinit var statusText: TextView
    private lateinit var codeInput: EditText
    private lateinit var pairButton: Button
    private lateinit var usagePermissionButton: Button
    private lateinit var batteryOptimizeButton: Button

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val prefs = getSharedPreferences("PingParentPrefs", Context.MODE_PRIVATE)
            val code = prefs.getString("paired_code", null)
            if (!code.isNullOrEmpty()) {
                updateDeviceData(code)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        FirebaseApp.initializeApp(this)
        db = FirebaseFirestore.getInstance()

        val prefs = getSharedPreferences("PingParentPrefs", Context.MODE_PRIVATE)
        val savedCode = prefs.getString("paired_code", null)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(50, 50, 50, 50)
            setBackgroundColor(Color.parseColor("#F8F9FA"))
        }

        val titleText = TextView(this).apply {
            text = "PingParent Agent"
            textSize = 24f
            setTextColor(Color.parseColor("#212529"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 30)
        }

        codeInput = EditText(this).apply {
            hint = "Enter 6-digit Pairing Code"
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(40, 25, 40, 25)
            setBackgroundColor(Color.WHITE)
        }

        pairButton = Button(this).apply {
            text = "Connect Device"
            textSize = 18f
            setBackgroundColor(Color.parseColor("#0D6EFD"))
            setTextColor(Color.WHITE)
        }

        usagePermissionButton = Button(this).apply {
            text = "Grant Usage Access"
            textSize = 15f
            setBackgroundColor(Color.parseColor("#6C757D"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }

        batteryOptimizeButton = Button(this).apply {
            text = "Disable Battery Limits (Required)"
            textSize = 15f
            setBackgroundColor(Color.parseColor("#DC3545"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                requestBatteryOptimizationExemption()
            }
        }

        statusText = TextView(this).apply {
            text = "Enter code to pair device."
            textSize = 15f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 20)
        }

        layout.addView(titleText)
        layout.addView(codeInput)
        layout.addView(pairButton)
        layout.addView(usagePermissionButton)
        layout.addView(batteryOptimizeButton)
        layout.addView(statusText)

        setContentView(layout)

        // Check and prompt 1-click battery bypass immediately
        requestBatteryOptimizationExemption()

        if (!savedCode.isNullOrEmpty()) {
            codeInput.setText(savedCode)
            codeInput.isEnabled = false
            pairButton.isEnabled = false
            updateDeviceData(savedCode)
            startAgentService()
        }

        pairButton.setOnClickListener {
            val code = codeInput.text.toString().trim()
            if (code.isEmpty()) {
                statusText.text = "Please enter pairing code."
                return@setOnClickListener
            }
            statusText.text = "Connecting..."
            pairAndSync(code)
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        checkUsagePermission()
        checkBatteryOptimizationStatus()
        val prefs = getSharedPreferences("PingParentPrefs", Context.MODE_PRIVATE)
        val code = prefs.getString("paired_code", null)
        if (!code.isNullOrEmpty()) {
            updateDeviceData(code)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {}
    }

    private fun checkBatteryOptimizationStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isIgnoring = powerManager?.isIgnoringBatteryOptimizations(packageName) ?: false
            if (isIgnoring) {
                batteryOptimizeButton.isEnabled = false
                batteryOptimizeButton.text = "Battery Unrestricted (Active)"
                batteryOptimizeButton.setBackgroundColor(Color.parseColor("#198754"))
            } else {
                batteryOptimizeButton.isEnabled = true
                batteryOptimizeButton.text = "Disable Battery Limits (Required)"
                batteryOptimizeButton.setBackgroundColor(Color.parseColor("#DC3545"))
            }
        } else {
            batteryOptimizeButton.isEnabled = false
            batteryOptimizeButton.text = "Battery Unrestricted (Active)"
            batteryOptimizeButton.setBackgroundColor(Color.parseColor("#198754"))
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun checkUsagePermission() {
        val hasPermission = hasUsageStatsPermission()
        if (hasPermission) {
            usagePermissionButton.isEnabled = false
            usagePermissionButton.text = "Usage Access Granted"
            usagePermissionButton.setBackgroundColor(Color.parseColor("#198754"))
        } else {
            usagePermissionButton.isEnabled = true
            usagePermissionButton.text = "Grant Usage Access (Required)"
            usagePermissionButton.setBackgroundColor(Color.parseColor("#DC3545"))
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun pairAndSync(code: String) {
        val docRef = db.collection("devices").document(code)
        docRef.get().addOnSuccessListener { doc ->
            if (doc.exists()) {
                getSharedPreferences("PingParentPrefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("paired_code", code)
                    .apply()

                codeInput.isEnabled = false
                pairButton.isEnabled = false
                updateDeviceData(code)
                startAgentService()
            } else {
                statusText.text = "❌ Code not found."
            }
        }.addOnFailureListener { e ->
            statusText.text = "Error: ${e.message}"
        }
    }

    private fun startAgentService() {
        val serviceIntent = Intent(this, PingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun updateDeviceData(code: String) {
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

        db.collection("devices").document(code)
            .update(updates)
            .addOnSuccessListener {
                statusText.text = "✅ Device Active & Synced!\nBattery: $batteryLevel% (Charging: $isCharging)\nScreen Time: ${screenTimeMinutes}m\nService: Running"
            }
            .addOnFailureListener { e ->
                statusText.text = "Sync Failed: ${e.message}"
            }
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
}
