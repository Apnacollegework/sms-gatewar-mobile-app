package com.example.service

import android.Manifest
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.R
import com.example.MainActivity
import com.example.data.local.EncryptedPreferencesManager
import com.example.data.local.SmsLogEntity
import com.example.domain.RateLimitResult
import com.example.domain.SmsGatewayRepository
import com.example.domain.SmsRuleEngine
import com.example.domain.SmsSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SmsGatewayService : Service() {

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + serviceJob)

    private lateinit var repository: SmsGatewayRepository
    private lateinit var ruleEngine: SmsRuleEngine
    private lateinit var smsSender: SmsSender
    private lateinit var prefs: EncryptedPreferencesManager

    companion object {
        const val CHANNEL_ID = "sms_gateway_foreground_channel"
        const val NOTIFICATION_ID = 991

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_SEND_TRIGGER = "ACTION_SEND_TRIGGER"

        const val SENT_INTENT_ACTION = "com.example.SMS_SENT_ACTION"
        const val DELIVERED_INTENT_ACTION = "com.example.SMS_DELIVERED_ACTION"

        var isServiceRunning = false
    }

    override fun onCreate() {
        super.onCreate()
        repository = SmsGatewayRepository(applicationContext)
        ruleEngine = SmsRuleEngine(applicationContext)
        smsSender = SmsSender(applicationContext)
        prefs = EncryptedPreferencesManager.getInstance(applicationContext)

        createNotificationChannel()
        registerSmsCallbacks()
        isServiceRunning = true

        startHeartbeatLoop()
    }

    private fun startHeartbeatLoop() {
        scope.launch {
            while (true) {
                try {
                    repository.sendHeartbeat()
                } catch (e: Exception) {
                    Log.e("GatewayService", "Heartbeat transmission failure: ${e.message}")
                }
                kotlinx.coroutines.delay(180_000) // 3 minutes interval
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundServiceCompat()
            }
            ACTION_STOP -> {
                stopForeground(true)
                stopSelf()
            }
            ACTION_SEND_TRIGGER -> {
                val requestId = intent.getStringExtra("request_id") ?: return START_STICKY
                val mobile = intent.getStringExtra("mobile") ?: return START_STICKY
                val message = intent.getStringExtra("message") ?: return START_STICKY
                val clientId = intent.getStringExtra("client_id") ?: "system"

                dispatchSmsTrigger(requestId, mobile, message, clientId)
            }
        }
        return START_STICKY
    }

    private fun startForegroundServiceCompat() {
        val notification = buildStatusNotification(0, 0)
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e("GatewayService", "Failed to start foreground: ${e.message}")
        }
    }

    private fun updateServiceNotification() {
        scope.launch {
            val sent = repository.getCountSentToday()
            val failed = repository.getCountFailedToday()
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildStatusNotification(sent, failed))
        }
    }

    private fun buildStatusNotification(sent: Int, failed: Int): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) CHANNEL_ID else ""

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("SMS Gateway Active")
            .setContentText("Dispatched today: $sent sent | $failed failed")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SMS Gateway Operation Services",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the cellular OTP forwarding module persistent"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun dispatchSmsTrigger(requestId: String, mobile: String, message: String, clientId: String) {
        scope.launch {
            // Check duplicates
            val existing = repository.findByRequestId(requestId)
            if (existing != null) {
                Log.w("GatewayService", "Attempted duplicate SMS for request_id: $requestId")
                repository.reportSmsStatusToServer(requestId, "failed", "duplicate_request")
                return@launch
            }

            // Check permissions
            if (ContextCompat.checkSelfPermission(this@SmsGatewayService, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                recordLog(requestId, mobile, message, "permission_denied", "SEND_SMS permission missing")
                repository.reportSmsStatusToServer(requestId, "failed", "permission_denied")
                return@launch
            }

            // Check dynamic limits
            val sentTodayCount = repository.getCountSentToday()
            when (val check = ruleEngine.checkRateLimits(sentTodayCount)) {
                is RateLimitResult.Blocked -> {
                    recordLog(requestId, mobile, message, "rate_limited", check.reason)
                    repository.reportSmsStatusToServer(requestId, "failed", "rate_limited")
                    return@launch
                }
                RateLimitResult.Allowed -> { /* Passed check */ }
            }

            // Save log status as 'sending'
            val maskedPhone = maskPhoneNumber(mobile)
            val previewText = if (prefs.isDebugMode()) message else "${message.take(12)}... [OTP Hidden]"
            val initialLog = SmsLogEntity(
                requestId = requestId,
                mobile = maskedPhone,
                messagePreview = previewText,
                status = "sending",
                error = null
            )
            repository.insertLog(initialLog)

            // Setup Intents for callbacks
            val sentPI = PendingIntent.getBroadcast(
                this@SmsGatewayService,
                requestId.hashCode(),
                Intent(SENT_INTENT_ACTION).apply { setPackage(packageName) }.putExtra("request_id", requestId),
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_MUTABLE else 0)
            )

            val deliveredPI = PendingIntent.getBroadcast(
                this@SmsGatewayService,
                requestId.hashCode(),
                Intent(DELIVERED_INTENT_ACTION).apply { setPackage(packageName) }.putExtra("request_id", requestId),
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_MUTABLE else 0)
            )

            try {
                smsSender.sendSms(mobile, message, sentPI, deliveredPI, null)
                Log.d("GatewayService", "Delivered payload trigger sequence complete for request $requestId")
            } catch (e: Exception) {
                Log.e("GatewayService", "Programmatic smsManager execution error: ${e.message}")
                updateSmsStatus(requestId, "failed", "sim_not_ready_or_error: ${e.message}")
            }
        }
    }

    private fun registerSmsCallbacks() {
        val sentFilter = IntentFilter(SENT_INTENT_ACTION)
        val deliveredFilter = IntentFilter(DELIVERED_INTENT_ACTION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(smsSentReceiver, sentFilter, RECEIVER_EXPORTED)
            registerReceiver(smsDeliveredReceiver, deliveredFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(smsSentReceiver, sentFilter)
            registerReceiver(smsDeliveredReceiver, deliveredFilter)
        }
    }

    private val smsSentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val requestId = intent?.getStringExtra("request_id") ?: return
            val resultCode = resultCode
            scope.launch {
                if (resultCode == Activity.RESULT_OK) {
                    updateSmsStatus(requestId, "sent", null)
                } else {
                    val errorString = when (resultCode) {
                        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "generic_failure"
                        SmsManager.RESULT_ERROR_NO_SERVICE -> "carrier_no_service"
                        SmsManager.RESULT_ERROR_NULL_PDU -> "null_pdu"
                        SmsManager.RESULT_ERROR_RADIO_OFF -> "radio_off"
                        else -> "failed_code_$resultCode"
                    }
                    updateSmsStatus(requestId, "failed", errorString)
                }
                updateServiceNotification()
            }
        }
    }

    private val smsDeliveredReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val requestId = intent?.getStringExtra("request_id") ?: return
            scope.launch {
                updateSmsStatus(requestId, "delivered", null)
                updateServiceNotification()
            }
        }
    }

    private suspend fun updateSmsStatus(requestId: String, status: String, error: String?) {
        val existing = repository.findByRequestId(requestId)
        if (existing != null) {
            val updated = existing.copy(
                status = status,
                error = error,
                updatedAt = System.currentTimeMillis()
            )
            repository.updateLog(updated)
            repository.reportSmsStatusToServer(requestId, status, error)
        }
    }

    private suspend fun recordLog(requestId: String, mobile: String, message: String, status: String, error: String) {
        val masked = maskPhoneNumber(mobile)
        val preview = if (prefs.isDebugMode()) message else "${message.take(12)}... [Privacy Filter]"
        val log = SmsLogEntity(
            requestId = requestId,
            mobile = masked,
            messagePreview = preview,
            status = status,
            error = error
        )
        repository.insertLog(log)
        updateServiceNotification()
    }

    private fun maskPhoneNumber(phone: String): String {
        return if (phone.length > 5) {
            phone.take(3) + "****" + phone.takeLast(3)
        } else {
            "****"
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isServiceRunning = false
        try {
            unregisterReceiver(smsSentReceiver)
            unregisterReceiver(smsDeliveredReceiver)
        } catch (e: Exception) {
            Log.e("GatewayService", "Error during cleanup: ${e.message}")
        }
        serviceJob.cancel()
        super.onDestroy()
    }
}
