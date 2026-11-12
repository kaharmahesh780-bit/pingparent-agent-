package com.pingparent.agent

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

// Bacche ke phone ka silent (anonymous) login + device se jodna
object ChildAuth {
    private const val TAG = "PingAuth"

    // Login ho jaye tab block chalao (pehle se login ho to turant chalta hai)
    fun ensure(onReady: (String) -> Unit) {
        try {
            val auth = FirebaseAuth.getInstance()
            val user = auth.currentUser
            if (user != null) {
                onReady(user.uid)
                return
            }
            auth.signInAnonymously()
                .addOnSuccessListener { result -> result.user?.let { onReady(it.uid) } }
                .addOnFailureListener { e -> Log.e(TAG, "Anonymous login failed", e) }
        } catch (e: Exception) {
            Log.e(TAG, "ensure error", e)
        }
    }

    // Is phone ko device document se jodta hai (naya ho to document banata hai)
    fun bind(context: Context, code: String, onFail: () -> Unit = {}, onBound: () -> Unit) {
        ensure { uid ->
            val ref = FirebaseFirestore.getInstance().collection("devices").document(code)
            ref.update("childUid", uid)
                .addOnSuccessListener { onBound() }
                .addOnFailureListener {
                    // Document nahi hai (ya naya hai): banao. Kisi aur ka hai to ye bhi fail hoga.
                    val pin = context.getSharedPreferences("PingParentPrefs", Context.MODE_PRIVATE)
                        .getString("parent_pin", "1234") ?: "1234"
                    val data = hashMapOf(
                        "pairingCode" to code,
                        "pin" to pin,
                        "childUid" to uid,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "lastSeen" to FieldValue.serverTimestamp()
                    )
                    ref.set(data)
                        .addOnSuccessListener { onBound() }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "bind failed (Re-link chahiye)", e)
                            onFail()
                        }
                }
        }
    }
}
