package com.pingparent.agent

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView

class BlockActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blocked)

        val category = intent.getStringExtra("category") ?: "Restricted Content"
        val tvCategoryBadge = findViewById<TextView>(R.id.tvCategoryBadge)
        val btnGoBack = findViewById<Button>(R.id.btnGoBack)

        tvCategoryBadge.text = "Category: $category"

        // बटन दबाने पर सीधे सेफ़ होम स्क्रीन पर ले जाएँ
        btnGoBack.setOnClickListener {
            goToHome()
        }
    }

    private fun goToHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(homeIntent)
        finish()
    }

    // अगर बच्चा फ़ोन का बैक बटन दबाए तब भी सीधे होम स्क्रीन पर जाए
    override fun onBackPressed() {
        goToHome()
    }
}
