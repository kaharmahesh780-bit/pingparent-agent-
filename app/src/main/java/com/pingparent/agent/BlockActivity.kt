package com.pingparent.agent

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class BlockActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appName = intent.getStringExtra("BLOCKED_APP_NAME") ?: "Restricted App"

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#111827"))
            setPadding(60, 60, 60, 60)
        }

        val iconText = TextView(this).apply {
            text = "🛡️"
            textSize = 64f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 30)
        }

        val titleText = TextView(this).apply {
            text = "Access Restricted"
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 15)
        }

        val messageText = TextView(this).apply {
            text = "$appName is restricted by your parent."
            textSize = 16f
            setTextColor(Color.parseColor("#9CA3AF"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }

        val homeButton = Button(this).apply {
            text = "Go to Home"
            textSize = 16f
            setBackgroundColor(Color.parseColor("#2563EB"))
            setTextColor(Color.WHITE)
            setPadding(40, 20, 40, 20)
            setOnClickListener {
                finishAffinity()
            }
        }

        layout.addView(iconText)
        layout.addView(titleText)
        layout.addView(messageText)
        layout.addView(homeButton)

        setContentView(layout)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // बैक बटन से लॉक स्क्रीन बंद नहीं होगी
    }
}
