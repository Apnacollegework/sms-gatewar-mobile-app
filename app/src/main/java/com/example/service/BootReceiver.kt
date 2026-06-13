package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.data.local.EncryptedPreferencesManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = EncryptedPreferencesManager.getInstance(context)
            if (prefs.isGatewayActive() && prefs.getDeviceToken().isNotBlank()) {
                val serviceIntent = Intent(context, SmsGatewayService::class.java).apply {
                    action = SmsGatewayService.ACTION_START
                }
                try {
                    ContextCompat.startForegroundService(context, serviceIntent)
                } catch (e: Exception) {
                    // Fail gracefully under newer SDK runtime changes
                }
            }
        }
    }
}
