package com.pingparent.agent

import android.app.Activity
import android.content.Context
import android.os.Bundle
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

class PauseActivity : Activity() {

    private val db by lazy { FirebaseFirestore.getInstance() }
    private var pauseListener: ListenerRegistration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pause)

        val prefs = getSharedPreferences("PingParentPrefs", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("pairing_code", "") ?: ""

        if (deviceId.isNotEmpty()) {
            // जैसे ही पैरेंट 'Resume' करेगा, यह स्क्रीन खुद बंद हो जाएगी
            pauseListener = db.collection("devices").document(deviceId)
                .addSnapshotListener { snapshot, _ ->
                    if (snapshot != null && snapshot.exists()) {
                        val isPaused = snapshot.getBoolean("isPaused") ?: false
                        if (!isPaused) {
                            finish()
                        }
                    }
                }
        }
    }

    // बच्चा बैक बटन दबाकर लॉक स्क्रीन बंद न कर सके
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Do nothing - screen remains locked
    }

    override fun onDestroy() {
        super.onDestroy()
        pauseListener?.remove()
    }
}
