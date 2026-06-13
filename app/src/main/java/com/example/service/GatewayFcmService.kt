package com.example.service

import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.example.data.local.EncryptedPreferencesManager
import com.example.data.local.SmsLogEntity
import com.example.domain.SmsGatewayRepository
import com.example.domain.SmsRuleEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class GatewayFcmService : FirebaseMessagingService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private lateinit var repository: SmsGatewayRepository
    private lateinit var ruleEngine: SmsRuleEngine
    private lateinit var prefs: EncryptedPreferencesManager

    override fun onCreate() {
        super.onCreate()
        repository = SmsGatewayRepository(applicationContext)
        ruleEngine = SmsRuleEngine(applicationContext)
        prefs = EncryptedPreferencesManager.getInstance(applicationContext)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("GatewayFcm", "New rotation FCM Token: $token")
        prefs.saveFcmToken(token)

        scope.launch {
            repository.updateFcmToken(token)
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d("GatewayFcm", "Data payload packet received from: ${remoteMessage.from}")

        val data = remoteMessage.data
        val type = data["type"] ?: ""
        val requestId = data["request_id"] ?: ""
        val mobile = data["mobile"] ?: ""
        val message = data["message"] ?: ""
        val clientId = data["client_id"] ?: "system"
        val signature = data["signature"] ?: ""
        val timestamp = data["timestamp"] ?: ""

        // Validate basic payload fields existence
        if (type != "SEND_SMS" || requestId.isBlank() || mobile.isBlank() || message.isBlank()) {
            Log.e("GatewayFcm", "FCM Parsing failure: Incorrect type or missing body properties.")
            reportFailure(requestId, "invalid_payload", "Type is not SEND_SMS or mandatory parameters missing")
            return
        }

        // Validate fresh timestamp rule (within 2-minute delta skew to prevent replay attacks)
        if (!ruleEngine.isTimestampFresh(timestamp)) {
            Log.e("GatewayFcm", "Payload validation failed: Expired timestamp deviation.")
            reportFailure(requestId, "expired_timestamp", "Packet epoch skew: older than 120 seconds")
            return
        }

        // Validate HMAC-SHA256 signature
        if (!ruleEngine.verifySignature(type, requestId, mobile, message, clientId, timestamp, signature)) {
            Log.e("GatewayFcm", "HMAC evaluation failure: Invalid signature match.")
            reportFailure(requestId, "invalid_signature", "HMAC key verification mismatch")
            return
        }

        // Passed security handshakes! Forward execution directly down to the SMS execution engine
        val intent = Intent(applicationContext, SmsGatewayService::class.java).apply {
            action = SmsGatewayService.ACTION_SEND_TRIGGER
            putExtra("request_id", requestId)
            putExtra("mobile", mobile)
            putExtra("message", message)
            putExtra("client_id", clientId)
        }

        try {
            if (prefs.isGatewayActive()) {
                // Keep the active foreground service handling the broadcast trigger
                ContextCompat.startForegroundService(applicationContext, intent)
            } else {
                // Service isn't active but start/bootstrap it to execute this specific message
                ContextCompat.startForegroundService(applicationContext, intent)
            }
        } catch (e: Exception) {
            Log.e("GatewayFcm", "Starting status delivery service failed: ${e.message}")
        }
    }

    private fun reportFailure(requestId: String, status: String, error: String) {
        if (requestId.isBlank()) return
        scope.launch {
            val masked = if (requestId.length > 3) requestId.take(3) + "***" else "***"
            val errorEntity = SmsLogEntity(
                requestId = requestId,
                mobile = "Remote Payload Error",
                messagePreview = "Error: $error",
                status = status,
                error = error
            )
            repository.insertLog(errorEntity)
            repository.reportSmsStatusToServer(requestId, "failed", error)
        }
    }

    override fun onDestroy() {
        job.cancel()
        super.onDestroy()
    }
}
