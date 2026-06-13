package com.example

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

class SmsGatewayApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        initializeFirebaseSafely()
    }

    private fun initializeFirebaseSafely() {
        try {
            // First attempt: try to initialize Firebase using standard resources (generated via google-services plugin)
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this)
                Log.d("SmsGatewayApp", "Firebase initialized successfully with default resource options.")
            }
        } catch (e: Exception) {
            Log.w("SmsGatewayApp", "Default Firebase dynamic resource initialization failed: ${e.message}. Falling back to programmatic options.")
            try {
                // Fallback attempt: programmatic fallback configs so FirebaseApp is always available and never crashes
                val options = FirebaseOptions.Builder()
                    .setApplicationId("1:531776510:android:a1b2c3d4e5f6g7")
                    .setApiKey("AIzaSyDummyKeyForSmsGateway")
                    .setProjectId("sms-gateway-dummy-project")
                    .setGcmSenderId("531776510")
                    .build()
                FirebaseApp.initializeApp(this, options)
                Log.i("SmsGatewayApp", "Firebase successfully initialized with programmatic fallback options.")
            } catch (ex: Exception) {
                Log.e("SmsGatewayApp", "Critical: Programmatic Firebase initialization fallback failed: ${ex.message}", ex)
            }
        }
    }
}
