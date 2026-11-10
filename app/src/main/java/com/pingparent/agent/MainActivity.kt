package com.pingparent.agent

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

class MainActivity : Activity() {

    private val db by lazy { FirebaseFirestore.getInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("PingParentPrefs", Context.MODE_PRIVATE)

        // 1. Hardware-bound Permanent Pairing Code
        var pairingCode = prefs.getString("pairing_code", null)
        if (pairingCode == null) {
            val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "9999"
            val cleanNum = Math.abs(androidId.hashCode() % 9000) + 1000
            pairingCode = "PG-$cleanNum"
            
            prefs.edit()
                .putString("pairing_code", pairingCode)
                .putString("parent_pin", "1234")
                .apply()

            val initialData = hashMapOf(
                "pairingCode" to pairingCode,
                "pin" to "1234",
                "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                "lastSeen" to com.google.firebase.firestore.FieldValue.serverTimestamp()
            )
            db.collection("devices").document(pairingCode).set(initialData)
        }

        // 2. Sync Real-Time Battery to Firestore
        syncBatteryStatus(pairingCode)

        // 3. Scan & Sync All Installed Apps to Firestore
        syncInstalledAppsDirectly(pairingCode)

        val pinContainer = findViewById<LinearLayout>(R.id.pinContainer)
        val mainContentContainer = findViewById<LinearLayout>(R.id.mainContentContainer)
        val etPin = findViewById<EditText>(R.id.etPin)
        val btnUnlock = findViewById<Button>(R.id.btnUnlock)
        val tvPinError = findViewById<TextView>(R.id.tvPinError)
        val tvForgotPin = findViewById<TextView>(R.id.tvForgotPin)
        val btnPermissions = findViewById<Button>(R.id.btnPermissions)
        val btnLockApp = findViewById<Button>(R.id.btnLockApp)
        val tvDisplayPairingCode = findViewById<TextView>(R.id.tvDisplayPairingCode)
        val tvAgentCode = findViewById<TextView>(R.id.tvAgentCode)

        tvDisplayPairingCode.text = "Pairing Code: $pairingCode"
        tvAgentCode.text = "Pairing Code: $pairingCode"

        btnUnlock.setOnClickListener {
            val enteredPin = etPin.text.toString().trim()
            val currentPin = prefs.getString("parent_pin", "1234") ?: "1234"

            if (enteredPin == currentPin) {
                tvPinError.visibility = View.GONE
                etPin.text.clear()
                pinContainer.visibility = View.GONE
                mainContentContainer.visibility = View.VISIBLE
            } else {
                tvPinError.visibility = View.VISIBLE
            }
        }

        tvForgotPin.setOnClickListener {
            val input = EditText(this)
            input.hint = "Enter Pairing Code (e.g. $pairingCode)"

            AlertDialog.Builder(this)
                .setTitle("Reset PIN Recovery")
                .setMessage("Enter the 6-character Pairing Code shown on parent dashboard:")
                .setView(input)
                .setPositiveButton("Verify") { _, _ ->
                    val enteredCode = input.text.toString().trim().uppercase()
                    if (enteredCode == pairingCode) {
                        prefs.edit().putString("parent_pin", "1234").apply()
                        db.collection("devices").document(pairingCode).update("pin", "1234")
                        Toast.makeText(this, "PIN reset to 1234", Toast.LENGTH_LONG).show()
                        etPin.setText("1234")
                    } else {
                        Toast.makeText(this, "Incorrect Pairing Code!", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        db.collection("devices").document(pairingCode).addSnapshotListener { snapshot, _ ->
            if (snapshot != null && snapshot.exists()) {
                val cloudPin = snapshot.getString("pin")
                if (!cloudPin.isNullOrEmpty()) {
                    prefs.edit().putString("parent_pin", cloudPin).apply()
                }
            }
        }

        btnPermissions.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnLockApp.setOnClickListener {
            mainContentContainer.visibility = View.GONE
            pinContainer.visibility = View.VISIBLE
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("PingParentPrefs", Context.MODE_PRIVATE)
        val pairingCode = prefs.getString("pairing_code", null)
        if (pairingCode != null) {
            syncInstalledAppsDirectly(pairingCode)
        }
    }

    private fun syncInstalledAppsDirectly(deviceId: String) {
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

                val distinctApps = appsList.distinctBy { it["package"] }

                if (distinctApps.isNotEmpty()) {
                    db.collection("devices").document(deviceId)
                        .set(mapOf("installedApps" to distinctApps), SetOptions.merge())
                        .addOnSuccessListener {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "Apps Synced: ${distinctApps.size}", Toast.LENGTH_SHORT).show()
                            }
                        }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun syncBatteryStatus(pairingCode: String) {
        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = registerReceiver(null, batteryFilter)

        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level != -1 && scale != -1) (level * 100 / scale.toFloat()).toInt() else 0

        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging: Boolean = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val batteryData = mapOf(
            "battery" to batteryPct,
            "isCharging" to isCharging,
            "lastSeen" to com.google.firebase.firestore.FieldValue.serverTimestamp()
        )

        db.collection("devices").document(pairingCode).update(batteryData)
    }

    override fun onBackPressed() {
        finish()
    }
}
