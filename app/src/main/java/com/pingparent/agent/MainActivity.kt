package com.pingparent.agent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    // Default PIN: 1234
    private val defaultPin = "1234"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val pinContainer = findViewById<LinearLayout>(R.id.pinContainer)
        val mainContentContainer = findViewById<LinearLayout>(R.id.mainContentContainer)
        val etPin = findViewById<EditText>(R.id.etPin)
        val btnUnlock = findViewById<Button>(R.id.btnUnlock)
        val tvPinError = findViewById<TextView>(R.id.tvPinError)
        val btnPermissions = findViewById<Button>(R.id.btnPermissions)
        val btnLockApp = findViewById<Button>(R.id.btnLockApp)

        val sharedPrefs = getSharedPreferences("PingParentPrefs", Context.MODE_PRIVATE)
        val activePin = sharedPrefs.getString("parent_pin", defaultPin) ?: defaultPin

        btnUnlock.setOnClickListener {
            val enteredPin = etPin.text.toString().trim()
            if (enteredPin == activePin) {
                tvPinError.visibility = View.GONE
                etPin.text.clear()
                pinContainer.visibility = View.GONE
                mainContentContainer.visibility = View.VISIBLE
            } else {
                tvPinError.visibility = View.VISIBLE
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
