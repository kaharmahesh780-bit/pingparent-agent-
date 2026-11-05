package com.pingparent.agent

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val textView = TextView(this).apply {
            text = "PingParent Agent is running..."
            textSize = 20f
            setPadding(50, 50, 50, 50)
        }
        setContentView(textView)

        try {
            FirebaseApp.initializeApp(this)
            val db = FirebaseFirestore.getInstance()

            val deviceStatus = hashMapOf(
                "status" to "online",
                "lastSeen" to System.currentTimeMillis()
            )

            db.collection("devices")
                .document("agent_test_device")
                .set(deviceStatus)
                .addOnSuccessListener {
                    textView.text = "Connected to Firebase!\nPingParent Agent active."
                }
                .addOnFailureListener { e ->
                    textView.text = "Firebase Error: ${e.message}"
                }
        } catch (e: Exception) {
            textView.text = "Startup Error:\n${e.localizedMessage}"
        }
    }
}
