package com.example.domain

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.*
import com.example.data.local.AppDatabase
import com.example.data.local.EncryptedPreferencesManager
import com.example.data.local.SmsLogEntity
import com.example.data.remote.RetrofitClient
import com.example.data.remote.model.RegisterDeviceRequest
import com.example.data.remote.model.SmsStatusReportRequest
import com.example.data.remote.model.UpdateFcmTokenRequest
import com.example.data.remote.model.HeartbeatRequest
import com.example.worker.StatusReportWorker
import com.example.service.SmsGatewayService
import android.os.PowerManager
import android.telephony.TelephonyManager
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class SmsGatewayRepository(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val dao = db.smsLogDao()
    private val prefs = EncryptedPreferencesManager.getInstance(context)

    // Flow database queries
    val allLogs: Flow<List<SmsLogEntity>> = dao.getAllLogs()

    suspend fun getCountSentToday(): Int = withContext(Dispatchers.IO) {
        dao.getCountSentToday(getStartOfToday())
    }

    suspend fun getCountFailedToday(): Int = withContext(Dispatchers.IO) {
        dao.getCountFailedToday(getStartOfToday())
    }

    suspend fun insertLog(log: SmsLogEntity) = withContext(Dispatchers.IO) {
        dao.insertLog(log)
    }

    suspend fun updateLog(log: SmsLogEntity) = withContext(Dispatchers.IO) {
        dao.updateLog(log)
    }

    suspend fun findByRequestId(requestId: String): SmsLogEntity? = withContext(Dispatchers.IO) {
        dao.findByRequestId(requestId)
    }

    suspend fun clearLogs() = withContext(Dispatchers.IO) {
        dao.clearAll()
    }

    // Register details
    suspend fun registerDevice(
        url: String,
        adminToken: String,
        deviceName: String,
        phoneNumber: String,
        fcmToken: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Basic secure inspection: HTTPS is mandatory or localhost for debug builds
            val cleanedUrl = url.trim()
            if (!cleanedUrl.startsWith("https://") && !cleanedUrl.contains("localhost") && !cleanedUrl.contains("127.0.0.1") && !cleanedUrl.contains("10.0.2.2")) {
                throw IllegalArgumentException("Non-HTTPS target URL endpoints are blocked for remote pairing.")
            }

            val service = RetrofitClient.create(cleanedUrl)
            val authHeader = if (adminToken.isNotBlank()) "Bearer $adminToken" else null

            val androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
            val appVersion = "1.0_gateway"

            val response = service.registerDevice(
                authHeader,
                RegisterDeviceRequest(deviceName, phoneNumber, androidVersion, appVersion, fcmToken)
            )

            val dId = response.deviceId ?: ""
            val dToken = response.deviceToken ?: ""
            val hSec = response.hmacSecret ?: ""

            val token = if (dToken.isNotBlank()) dToken else hSec

            if (token.isNotBlank()) {
                prefs.saveBackendUrl(cleanedUrl)
                prefs.saveAdminToken(adminToken)
                prefs.saveDeviceId(dId)
                prefs.saveDeviceToken(token)
                prefs.saveHmacSecret(hSec)
                prefs.savePhoneNumber(phoneNumber)
                prefs.saveFcmToken(fcmToken)

                // Immediately send an initial heartbeat payload to establish registration status
                try {
                    sendHeartbeatWithService(service, token)
                } catch (e: Exception) {
                    Log.e("Repository", "Initial startup heartbeat sync error: ${e.message}")
                }

                return@withContext true
            }
            false
        } catch (e: Exception) {
            Log.e("Repository", "Device Registration failure: ${e.message}", e)
            throw e
        }
    }

    suspend fun sendHeartbeat(): Boolean = withContext(Dispatchers.IO) {
        val url = prefs.getBackendUrl()
        val devToken = prefs.getDeviceToken()
        if (url.isBlank() || devToken.isBlank()) return@withContext false

        try {
            val service = RetrofitClient.create(url)
            sendHeartbeatWithService(service, devToken)
        } catch (e: Exception) {
            Log.e("Repository", "Periodic background heartbeat transit failed: ${e.message}", e)
            false
        }
    }

    private suspend fun sendHeartbeatWithService(service: com.example.data.remote.GatewayApiService, devToken: String): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isBatteryOptimizationDisabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }

        val hasSmsPermission = ContextCompat.checkSelfPermission(context, android.Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val isSimReady = telephonyManager.simState == TelephonyManager.SIM_STATE_READY

        val isForegroundRunning = SmsGatewayService.isServiceRunning

        val request = HeartbeatRequest(
            batteryOptimizationIgnored = isBatteryOptimizationDisabled,
            smsPermission = hasSmsPermission,
            simReady = isSimReady,
            foregroundServiceRunning = isForegroundRunning
        )

        val response = service.sendHeartbeat("Bearer $devToken", request)
        if (response.success) {
            response.limits?.let { limits ->
                limits.dailyLimit?.let { prefs.saveMaxSmsPerDay(it) }
                limits.perMinuteLimit?.let { prefs.saveMaxSmsPerMinute(it) }
            }
            prefs.saveLastHeartbeatTime(System.currentTimeMillis())
            Log.i("Repository", "FCM Device Heartbeat acknowledged successfully.")
            return true
        }
        return false
    }

    suspend fun updateFcmToken(newToken: String): Boolean = withContext(Dispatchers.IO) {
        val url = prefs.getBackendUrl()
        val devToken = prefs.getDeviceToken()
        if (url.isBlank() || devToken.isBlank()) return@withContext false

        try {
            val service = RetrofitClient.create(url)
            service.updateFcmToken("Bearer $devToken", UpdateFcmTokenRequest(newToken))
            prefs.saveFcmToken(newToken)
            true
        } catch (e: Exception) {
            Log.e("Repository", "FCM token registration update failed: ${e.message}")
            false
        }
    }

    suspend fun reportSmsStatusToServer(
        requestId: String,
        status: String, // sent, failed, delivered
        error: String?
    ): Boolean = withContext(Dispatchers.IO) {
        if (requestId.startsWith("test_")) {
            Log.d("Repository", "Skipping backend reporting for local test SMS dispatch: $requestId")
            return@withContext true
        }

        val url = prefs.getBackendUrl()
        val devToken = prefs.getDeviceToken()
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        val timestamp = sdf.format(Date())

        if (url.isBlank() || devToken.isBlank()) {
            return@withContext false
        }

        try {
            val service = RetrofitClient.create(url)
            service.reportSmsStatus(
                "Bearer $devToken",
                SmsStatusReportRequest(requestId, status, error, timestamp)
            )
            true
        } catch (e: Exception) {
            Log.e("Repository", "Status Report failed, scheduling cache worker backup: ${e.message}")
            scheduleOfflineReportBackup(requestId, status, error ?: "Offline connection backup", timestamp)
            false
        }
    }

    private fun scheduleOfflineReportBackup(requestId: String, status: String, error: String, timestamp: String) {
        val data = workDataOf(
            "request_id" to requestId,
            "status" to status,
            "error" to error,
            "timestamp" to timestamp
        )

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val job = OneTimeWorkRequestBuilder<StatusReportWorker>()
            .setInputData(data)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "sync_report_$requestId",
            ExistingWorkPolicy.REPLACE,
            job
        )
    }

    private fun getStartOfToday(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
