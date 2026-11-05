package com.pingparent.agent

import android.app.Activity
import android.graphics.Color
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        FirebaseApp.initializeApp(this)
        val db = FirebaseFirestore.getInstance()

        // मुख्य लेआउट (वर्टिकल)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(60, 60, 60, 60)
            setBackgroundColor(Color.parseColor("#F8F9FA"))
        }

        // हेडिंग
        val titleText = TextView(this).apply {
            text = "PingParent Agent"
            textSize = 24f
            setTextColor(Color.parseColor("#212529"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }

        // पेयरिंग कोड इनपुट बॉक्स
        val codeInput = EditText(this).apply {
            hint = "Enter 6-digit Pairing Code"
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(40, 30, 40, 30)
            setBackgroundColor(Color.WHITE)
        }

        // स्टेटस टेक्स्ट
        val statusText = TextView(this).apply {
            text = "Enter code from parent dashboard to connect."
            textSize = 16f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 30, 0, 30)
        }

        // पेयरिंग बटन
        val pairButton = Button(this).apply {
            text = "Connect Device"
            textSize = 18f
            setBackgroundColor(Color.parseColor("#0D6EFD"))
            setTextColor(Color.WHITE)
        }

        pairButton.setOnClickListener {
            val code = codeInput.text.toString().trim()
            if (code.isEmpty()) {
                statusText.text = "Please enter a valid pairing code."
                return@setOnClickListener
            }

            statusText.text = "Verifying pairing code..."

            val docRef = db.collection("devices").document(code)

            docRef.get().addOnSuccessListener { document ->
                if (document.exists()) {
                    // कोड मौजूद है, स्टेटस और लास्ट सीन अपडेट करें
                    val updates = hashMapOf<String, Any>(
                        "status" to "paired",
                        "isOnline" to true,
                        "lastSeen" to System.currentTimeMillis()
                    )

                    docRef.update(updates)
                        .addOnSuccessListener {
                            statusText.text = "✅ Device Linked Successfully!\nParent: ${document.getString("parentPhone") ?: ""}"
                            codeInput.isEnabled = false
                            pairButton.isEnabled = false
                        }
                        .addOnFailureListener { e ->
                            statusText.text = "Update Failed: ${e.message}"
                        }
                } else {
                    statusText.text = "❌ Invalid Code: Document '$code' not found."
                }
            }.addOnFailureListener { e ->
                statusText.text = "Connection Error: ${e.message}"
            }
        }

        layout.addView(titleText)
        layout.addView(codeInput)
        layout.addView(pairButton)
        layout.addView(statusText)

        setContentView(layout)
    }
}
