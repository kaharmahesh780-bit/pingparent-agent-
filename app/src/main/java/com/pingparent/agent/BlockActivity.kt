package com.pingparent.agent

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.pingparent.agent.R

class BlockActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Status bar aur full screen lock
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        setContentView(R.layout.activity_block)

        val btnGoHome = findViewById<Button>(R.id.btnGoHome)
        btnGoHome.setOnClickListener {
            goToHome()
        }
    }

    // Back press par bhi home screen
    override fun onBackPressed() {
        goToHome()
    }

    private fun goToHome() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
        finish()
    }
}
