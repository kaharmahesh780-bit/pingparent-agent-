package com.pingparent.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.firebase.firestore.FirebaseFirestore

class PingAccessibilityService : AccessibilityService() {

    private val db by lazy { FirebaseFirestore.getInstance() }
    private var lastTriggerTime: Long = 0

    // 5 Major High-Risk Protection Categories
    private val adultKeywords = listOf("porn", "xxx", "xvideos", "sex", "nude", "erotic", "nsfw", "brazzers")
    private val alcoholKeywords = listOf("alcohol", "whiskey", "vodka", "beer", "rum", "wine", "buy liquor")
    private val drugsKeywords = listOf("drugs", "cocaine", "weed", "marijuana", "buy weed", "heroin")
    private val bettingKeywords = listOf("bet365", "stake casino", "gambling", "lottery online", "teen patti real cash", "1xbet")
    private val violenceKeywords = listOf("suicide", "how to kill", "bomb making", "self harm")

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val rootNode = rootInActiveWindow ?: return
        val textList = mutableListOf<String>()
        collectAllText(rootNode, textList)

        val fullScreenText = textList.joinToString(" ").lowercase()
        val packageName = event.packageName?.toString() ?: "app"

        // Check categories
        val (matchedCategory, matchedWord) = checkCategories(fullScreenText)

        if (matchedCategory != null && matchedWord != null) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastTriggerTime > 3000) { // 3-second debounce
                lastTriggerTime = currentTime
                triggerProtection(packageName, matchedCategory, matchedWord)
            }
        }
    }

    private fun checkCategories(text: String): Pair<String?, String?> {
        for (k in adultKeywords) if (text.contains(k)) return Pair("Adult Content", k)
        for (k in alcoholKeywords) if (text.contains(k)) return Pair("Alcohol & Tobacco", k)
        for (k in drugsKeywords) if (text.contains(k)) return Pair("Substances & Drugs", k)
        for (k in bettingKeywords) if (text.contains(k)) return Pair("Gambling & Bets", k)
        for (k in violenceKeywords) if (text.contains(k)) return Pair("Violence & Self Harm", k)
        return Pair(null, null)
    }

    private fun triggerProtection(appPackage: String, category: String, keyword: String) {
        val prefs = getSharedPreferences("PingParentPrefs", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("pairing_code", "") ?: ""

        // 1. Send Alert Card to Firebase
        if (deviceId.isNotEmpty()) {
            val alertData = hashMapOf(
                "category" to category,
                "keyword" to keyword,
                "app" to cleanPackageName(appPackage),
                "timestamp" to com.google.firebase.firestore.FieldValue.serverTimestamp()
            )
            db.collection("devices").document(deviceId)
                .collection("alerts")
                .add(alertData)
        }

        // 2. Launch Qustodio-Style Shield Screen
        val blockIntent = Intent(this, BlockActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("category", category)
            putExtra("keyword", keyword)
        }
        startActivity(blockIntent)
    }

    private fun cleanPackageName(pkg: String): String {
        return when {
            pkg.contains("chrome") -> "Google Chrome"
            pkg.contains("youtube") -> "YouTube"
            pkg.contains("instagram") -> "Instagram"
            else -> pkg
        }
    }

    private fun collectAllText(node: AccessibilityNodeInfo?, list: MutableList<String>) {
        if (node == null) return
        node.text?.let { list.add(it.toString()) }
        node.contentDescription?.let { list.add(it.toString()) }
        for (i in 0 until node.childCount) {
            collectAllText(node.getChild(i), list)
        }
    }

    override fun onInterrupt() {}
}
