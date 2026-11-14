package com.pingparent.agent

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.app.AppOpsManager
import android.os.Process
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

        }

        // PingService ko app khulte hi bhi start karo (reboot ki zarurat nahi)
        try {
            val svc = Intent(this, PingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Phone ko silent login se device document se jodo, phir sync shuru karo
        val boundCode: String = pairingCode
        ChildAuth.bind(this, boundCode, {
            runOnUiThread {
                Toast.makeText(
                    this,
                    "Ye phone kisi aur login se juda hai. Parent dashboard mein Re-link dabao.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }) {
            syncBatteryStatus(boundCode)
            syncInstalledAppsDirectly(boundCode)
            listenForPinChanges(prefs, boundCode)
            // Secret token device document mein (QR se jodne ke liye)
            db.collection("devices").document(boundCode).update("pairToken", getPairToken(prefs))
        }

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
        tvAgentCode.text = "Pairing Code: $pairingCode (QR ke liye tap karo)"
        val qrCode: String = pairingCode
        tvAgentCode.setOnClickListener { showQrDialog(qrCode, getPairToken(prefs)) }

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


        btnPermissions.setOnClickListener { startNextMissingPermission() }

        findViewById<Button>(R.id.btnUnlinkParent)?.setOnClickListener { confirmUnlinkParent() }

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
        // Permission screen se wapas aate hi khud agli zaroori permission khol deta hai
        if (setupInProgress) {
            setupInProgress = false
            Handler(Looper.getMainLooper()).postDelayed({ startNextMissingPermission(showDoneMsg = true) }, 400)
        }
    }

    private var setupInProgress = false

    private fun isAccessibilityOn(): Boolean {
        return try {
            val enabled = android.provider.Settings.Secure.getInt(
                contentResolver, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 0
            )
            if (enabled != 1) return false
            val list = android.provider.Settings.Secure.getString(
                contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            list.contains(packageName)
        } catch (e: Exception) { false }
    }

    private fun isDeviceAdminOn(): Boolean {
        return try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.isAdminActive(ComponentName(this, PingDeviceAdminReceiver::class.java))
        } catch (e: Exception) { false }
    }

    private fun isUsageAccessOn(): Boolean {
        return try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) { false }
    }

    // Ek hi button: jo permission abhi tak ON nahi hui, uski screen khud khol deta hai.
    // Us screen se ON karke "Back" dabate hi (onResume) ye agli missing permission khud khol dega.
    private fun startNextMissingPermission(showDoneMsg: Boolean = false) {
        when {
            !isAccessibilityOn() -> {
                Toast.makeText(this, "Step 1/3: Accessibility ko PingParent ke liye ON karo", Toast.LENGTH_LONG).show()
                setupInProgress = true
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            !isDeviceAdminOn() -> {
                Toast.makeText(this, "Step 2/3: Activate dabao", Toast.LENGTH_LONG).show()
                setupInProgress = true
                requestDeviceAdmin()
            }
            !isUsageAccessOn() -> {
                Toast.makeText(this, "Step 3/3: PingParent ko ON karo", Toast.LENGTH_LONG).show()
                setupInProgress = true
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            else -> {
                if (showDoneMsg) Toast.makeText(this, "Sab Permissions ON ho gayi ✅", Toast.LENGTH_LONG).show()
            }
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

    private fun listenForPinChanges(prefs: android.content.SharedPreferences, code: String) {
        db.collection("devices").document(code).addSnapshotListener { snapshot, _ ->
            if (snapshot != null && snapshot.exists()) {
                val cloudPin = snapshot.getString("pin")
                if (!cloudPin.isNullOrEmpty()) {
                    prefs.edit().putString("parent_pin", cloudPin).apply()
                }
            }
        }
    }

    // Random secret token (ek baar banta hai, QR ke saath parent ko milta hai)
    private fun getPairToken(prefs: android.content.SharedPreferences): String {
        val existing = prefs.getString("pair_token", null)
        if (!existing.isNullOrEmpty()) return existing
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val rnd = java.security.SecureRandom()
        val token = (1..12).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
        prefs.edit().putString("pair_token", token).apply()
        return token
    }

    private fun makeQr(text: String): android.graphics.Bitmap {
        val size = 600
        val matrix = com.google.zxing.MultiFormatWriter()
            .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size)
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        return bmp
    }

    private fun showQrDialog(code: String, token: String) {
        val payload = "$code:$token"
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(40, 24, 40, 8)

        val img = android.widget.ImageView(this)
        img.setImageBitmap(makeQr(payload))
        box.addView(img)

        val txt = TextView(this)
        txt.text = payload
        txt.textSize = 13f
        txt.gravity = android.view.Gravity.CENTER
        txt.setTextIsSelectable(true)
        box.addView(txt)

        AlertDialog.Builder(this)
            .setTitle("Parent ke phone se scan karo")
            .setView(box)
            .setPositiveButton("Band karo", null)
            .show()
    }

    // Agar ye device kisi purane/galat parent account se juda reh gaya ho, usko hatata hai.
    private fun confirmUnlinkParent() {
        val prefs = getSharedPreferences("PingParentPrefs", Context.MODE_PRIVATE)
        val code = prefs.getString("pairing_code", null) ?: prefs.getString("paired_code", "") ?: ""
        if (code.isEmpty()) {
            Toast.makeText(this, "Pehle PIN daal kar app unlock karo", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Parent Unlink karein?")
            .setMessage("Is phone ka purana parent account hata diya jayega. Parent ko naya QR scan karke dobara jodna hoga.")
            .setPositiveButton("Haan, Unlink karo") { _, _ ->
                ChildAuth.ensure {
                    db.collection("devices").document(code)
                        .update("parentUid", com.google.firebase.firestore.FieldValue.delete())
                        .addOnSuccessListener {
                            Toast.makeText(this, "Parent unlink ho gaya ✅", Toast.LENGTH_LONG).show()
                        }
                        .addOnFailureListener {
                            Toast.makeText(this, "Unlink nahi hua, dobara try karo", Toast.LENGTH_LONG).show()
                        }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestDeviceAdmin() {
        val admin = ComponentName(this, PingDeviceAdminReceiver::class.java)
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (dpm.isAdminActive(admin)) {
            Toast.makeText(this, "Uninstall Protection pehle se ON hai", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Ye ON rakhne se bacha PingParent ko uninstall nahi kar sakta."
            )
        startActivity(intent)
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
