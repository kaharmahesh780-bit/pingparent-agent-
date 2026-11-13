package com.pingparent.agent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

class PauseActivity : Activity() {

    private val db by lazy { FirebaseFirestore.getInstance() }
    private var pauseListener: ListenerRegistration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pause)

        val btnCall = findViewById<Button>(R.id.btnEmergencyCall)
        btnCall.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
            }
        }

        val prefs = getSharedPreferences("PingParentPrefs", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("pairing_code", "") ?: ""

        if (deviceId.isNotEmpty()) {
            // Parent Resume karte hi ye screen khud band ho jayegi
            pauseListener = db.collection("devices").document(deviceId)
                .addSnapshotListener { snapshot, _ ->
                    if (snapshot != null && snapshot.exists()) {
                        val isPaused = snapshot.getBoolean("isPaused") ?: false
                        if (!isPaused) {
                            finish()
                        }
                        // Emergency Call button parent ki setting ke hisaab se
                        val allowCall = snapshot.getBoolean("allowEmergency") != false
                        btnCall.visibility = if (allowCall) View.VISIBLE else View.GONE
                    }
                }
        }
    }

    // Bacha back button se lock screen band na kar sake
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Do nothing - screen remains locked
    }

    override fun onDestroy() {
        super.onDestroy()
        pauseListener?.remove()
    }
}
