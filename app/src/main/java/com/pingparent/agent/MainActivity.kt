package com.pingparent.agent

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.firestore.FirebaseFirestore
import kotlin.random.Random

class MainActivity : Activity() {

    private val db by lazy { FirebaseFirestore.getInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("PingParentPrefs", Context.MODE_PRIVATE)

        // 1. Get or Generate Pairing Code
        var pairingCode = prefs.getString("pairing_code", null)
        if (pairingCode == null) {
            val randomNum = Random.nextInt(1000, 9999)
            pairingCode = "PG-$randomNum"
            prefs.edit()
                .putString("pairing_code", pairingCode)
                .putString("parent_pin", "1234")
                .apply()

            // Initialize on Firestore
            val initialData = hashMapOf(
                "pairingCode" to pairingCode,
                "pin" to "1234",
                "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
            )
            db.collection("devices").document(pairingCode).set(initialData)
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
        tvAgentCode.text = "Pairing Code: $pairingCode"

        // 2. Unlock Button Logic
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

        // 3. Option 2: Forgot PIN recovery using Pairing Code
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

        // 4. Option 1: Live listener for Remote PIN changes from Parent Dashboard
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

    override fun onBackPressed() {
        finish()
    }
}
