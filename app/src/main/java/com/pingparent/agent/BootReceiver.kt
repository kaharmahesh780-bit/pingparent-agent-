package com.pingparent.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED || 
            intent?.action == "android.intent.action.QUICKBOOT_POWERON") {
            
            // Phone restart hone par background sync service automatically kickstart hoti hai
            context?.let { ctx ->
                val serviceIntent = Intent(ctx, PingService::class.java)
                try {
                    ctx.startForegroundService(serviceIntent)
                } catch (e: Exception) {
                    ctx.startService(serviceIntent)
                }
            }
        }
    }
}
