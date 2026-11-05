package com.pingparent.agent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.BatteryManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore

class MainActivity : Activity() {

    private lateinit var db: FirebaseFirestore
    private lateinit var statusText: TextView
    private lateinit var codeInput: EditText
    private lateinit var pairButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        FirebaseApp.initializeApp(this)
        db = FirebaseFirestore.getInstance()

        val prefs = getSharedPreferences("PingParentPrefs", Context.MODE_PRIVATE)
        val savedCode = prefs.getString("paired_code", null)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(60, 60, 60, 60)
            setBackgroundColor(Color.parseColor("#F8F9FA"))
        }

        val titleText = TextView(this).apply {
            text = "PingParent Agent"
            textSize = 24f
            setTextColor(Color.parseColor("#212529"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }

        codeInput = EditText(this).apply {
            hint = "Enter 6-digit Pairing Code"
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(40, 30, 40, 30)
            setBackgroundColor(Color.WHITE)
        }

        pairButton = Button(this).apply {
            text = "Connect Device"
            textSize = 18f
            setBackgroundColor(Color.parseColor("#0D6EFD"))
            setTextColor(Color.WHITE)
        }

        statusText = TextView(this).apply {
            text = "Enter code to pair device."
            textSize = 16f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 30, 0, 30)
        }

        layout.addView(titleText)
        layout.addView(codeInput)
        layout.addView(pairButton)
        layout.addView(statusText)

        setContentView(layout)

        // अगर पहले से पेयर है तो सीधे सिंक करें
        if (!savedCode.isNullOrEmpty()) {
            codeInput.setText(savedCode)
            codeInput.isEnabled = false
            pairButton.isEnabled = false
            updateDeviceData(savedCode)
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
            } else {
                statusText.text = "❌ Code not found."
            }
        }.addOnFailureListener { e ->
            statusText.text = "Error: ${e.message}"
        }
    }

    private fun updateDeviceData(code: String) {
        val (batteryLevel, isCharging) = getBatteryInfo()

        val updates = hashMapOf<String, Any>(
            "status" to "paired",
            "isOnline" to true,
            "lastSeen" to System.currentTimeMillis(),
            "batteryLevel" to batteryLevel,
            "isCharging" to isCharging
        )

        db.collection("devices").document(code)
            .update(updates)
            .addOnSuccessListener {
                statusText.text = "✅ Device Active & Synced!\nBattery: $batteryLevel% (Charging: $isCharging)"
            }
            .addOnFailureListener { e ->
                statusText.text = "Sync Failed: ${e.message}"
            }
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
