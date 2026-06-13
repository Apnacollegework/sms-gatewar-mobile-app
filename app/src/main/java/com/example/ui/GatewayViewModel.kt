package com.example.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.telephony.TelephonyManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.EncryptedPreferencesManager
import com.example.data.local.SmsLogEntity
import com.example.domain.SmsGatewayRepository
import com.example.service.SmsGatewayService
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GatewayViewModel(private val context: Context) : ViewModel() {

    private val repository = SmsGatewayRepository(context)
    private val prefs = EncryptedPreferencesManager.getInstance(context)

    // Observable Local Database Logs
    val smsLogs: StateFlow<List<SmsLogEntity>> = repository.allLogs
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // UI Reactive State variables
    var isPaired by mutableStateOf(false)
    var backendUrl by mutableStateOf("")
    var adminToken by mutableStateOf("")
    var deviceName by mutableStateOf("")
    var phoneNumber by mutableStateOf("")
    var deviceId by mutableStateOf("")
    var deviceToken by mutableStateOf("")
    var hmacSecret by mutableStateOf("")
    var lastHeartbeatTime by mutableStateOf(0L)
    var fcmToken by mutableStateOf("")
    var lastPairingError by mutableStateOf<String?>(null)
    var isSendingHeartbeat by mutableStateOf(false)

    var todaySent by mutableStateOf(0)
    var todayFailed by mutableStateOf(0)

    var isGatewayServiceActive by mutableStateOf(false)
    var maxSmsPerMinute by mutableStateOf(5)
    var maxSmsPerDay by mutableStateOf(100)
    var isDebugMode by mutableStateOf(false)

    // Diagnostic checklist indicators
    var hasSmsPermission by mutableStateOf(false)
    var hasNotificationPermission by mutableStateOf(false)
    var isSimCardReady by mutableStateOf(false)
    var isBatteryOptimizationDisabled by mutableStateOf(false)
    var isSmsCompatible by mutableStateOf(false)

    init {
        loadPreferencesState()
        refreshMetrics()
        fetchAndSyncFcmToken()
    }

    private fun loadPreferencesState() {
        val token = prefs.getDeviceToken()
        isPaired = token.isNotBlank()

        backendUrl = prefs.getBackendUrl()
        adminToken = prefs.getAdminToken()
        deviceName = Build.MODEL
        phoneNumber = prefs.getPhoneNumber()
        deviceId = prefs.getDeviceId()
        deviceToken = token
        hmacSecret = prefs.getHmacSecret()
        lastHeartbeatTime = prefs.getLastHeartbeatTime()

        fcmToken = prefs.getFcmToken()
        isGatewayServiceActive = prefs.isGatewayActive() && SmsGatewayService.isServiceRunning
        maxSmsPerMinute = prefs.getMaxSmsPerMinute()
        maxSmsPerDay = prefs.getMaxSmsPerDay()
        isDebugMode = prefs.isDebugMode()
    }

    fun refreshMetrics() {
        viewModelScope.launch {
            todaySent = repository.getCountSentToday()
            todayFailed = repository.getCountFailedToday()
            runSystemDiagnosticChecks()
        }
    }

    @SuppressLint("BatteryLife")
    fun runSystemDiagnosticChecks() {
        hasSmsPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

        hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        isBatteryOptimizationDisabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }

        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        isSmsCompatible = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        isSimCardReady = telephonyManager.simState == TelephonyManager.SIM_STATE_READY
    }

    fun fetchAndSyncFcmToken() {
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result
                    if (token != null && token != fcmToken) {
                        fcmToken = token
                        prefs.saveFcmToken(token)
                        viewModelScope.launch {
                            repository.updateFcmToken(token)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("GatewayViewModel", "Failed to retrieve or init FCM Token cleanly: ${e.message}", e)
        }
    }

    fun pairDevice(onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            lastPairingError = null
            try {
                val success = repository.registerDevice(
                    url = backendUrl,
                    adminToken = adminToken,
                    deviceName = deviceName,
                    phoneNumber = phoneNumber,
                    fcmToken = fcmToken
                )
                if (success) {
                    isPaired = true
                    loadPreferencesState()
                    toggleGatewayService(true) // Run engine automatically upon successful registration
                } else {
                    lastPairingError = "Server registration failed (no token returned)"
                }
                onComplete(success)
            } catch (e: Exception) {
                lastPairingError = e.localizedMessage ?: e.message ?: e.toString()
                android.util.Log.e("GatewayViewModel", "Registration error: ${e.message}", e)
                onComplete(false)
            }
        }
    }

    fun sendManualHeartbeat(onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            isSendingHeartbeat = true
            val success = repository.sendHeartbeat()
            if (success) {
                lastHeartbeatTime = prefs.getLastHeartbeatTime()
                maxSmsPerMinute = prefs.getMaxSmsPerMinute()
                maxSmsPerDay = prefs.getMaxSmsPerDay()
                runSystemDiagnosticChecks()
            }
            isSendingHeartbeat = false
            onComplete(success)
        }
    }

    fun unpairDevice() {
        toggleGatewayService(false)
        prefs.clearAll()
        viewModelScope.launch {
            repository.clearLogs()
        }
        isPaired = false
        backendUrl = ""
        adminToken = ""
        phoneNumber = ""
        deviceId = ""
        deviceToken = ""
        hmacSecret = ""
        lastHeartbeatTime = 0L
        fcmToken = ""
        refreshMetrics()
    }

    fun toggleGatewayService(active: Boolean) {
        prefs.saveGatewayActive(active)
        isGatewayServiceActive = active

        val intent = Intent(context, SmsGatewayService::class.java).apply {
            action = if (active) SmsGatewayService.ACTION_START else SmsGatewayService.ACTION_STOP
        }

        try {
            if (active) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.stopService(intent)
            }
        } catch (e: Exception) {
            // Fail gracefully
        }
    }

    fun updateLimits(minuteLimit: Int, dayLimit: Int) {
        prefs.saveMaxSmsPerMinute(minuteLimit)
        prefs.saveMaxSmsPerDay(dayLimit)
        maxSmsPerMinute = minuteLimit
        maxSmsPerDay = dayLimit
    }

    fun toggleDebugMode(enabled: Boolean) {
        prefs.saveDebugMode(enabled)
        isDebugMode = enabled
    }

    fun clearLogHistory() {
        viewModelScope.launch {
            repository.clearLogs()
            refreshMetrics()
        }
    }

    fun sendTestSms(targetNumber: String, textContent: String) {
        val testId = "test_" + java.util.UUID.randomUUID().toString().take(8)
        val intent = Intent(context, SmsGatewayService::class.java).apply {
            action = SmsGatewayService.ACTION_SEND_TRIGGER
            putExtra("request_id", testId)
            putExtra("mobile", targetNumber.trim())
            putExtra("message", textContent)
            putExtra("client_id", "local_test_tool")
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            // Fail gracefully
        }
    }

    @SuppressLint("HardwareIds")
    fun tryAutoDetectPhoneNumber(): String? {
        val hasNumbersPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val hasStatePermission = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

        if (!hasNumbersPermission && !hasStatePermission) {
            return null
        }

        try {
            // Try SubscriptionManager
            val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            if (subscriptionManager != null) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED)
                ) {
                    val activeList = subscriptionManager.activeSubscriptionInfoList
                    if (!activeList.isNullOrEmpty()) {
                        for (info in activeList) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                val number = subscriptionManager.getPhoneNumber(info.subscriptionId)
                                if (!number.isNullOrBlank()) {
                                    return number
                                }
                            } else {
                                @Suppress("DEPRECATION")
                                val number = info.number
                                if (!number.isNullOrBlank()) {
                                    return number
                                }
                            }
                        }
                    }
                }
            }

            // Fallback to TelephonyManager line1Number
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (telephonyManager != null) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED)
                ) {
                    val number = telephonyManager.line1Number
                    if (!number.isNullOrBlank()) {
                        return number
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ViewModel", "Failed to retrieve phone number: ${e.message}")
        }
        return null
    }

    @SuppressLint("HardwareIds")
    fun getActiveSubscriptionPhoneNumbers(): List<String> {
        val numbers = mutableListOf<String>()
        val hasStatePermission = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val hasNumbersPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        if (!hasStatePermission && !hasNumbersPermission) {
            return emptyList()
        }

        try {
            // Try SubscriptionManager
            val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            if (subscriptionManager != null) {
                val activeList = subscriptionManager.activeSubscriptionInfoList
                if (!activeList.isNullOrEmpty()) {
                    for (info in activeList) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            val number = subscriptionManager.getPhoneNumber(info.subscriptionId)
                            if (!number.isNullOrBlank()) {
                                numbers.add(number.trim())
                            }
                        } else {
                            @Suppress("DEPRECATION")
                            val number = info.number
                            if (!number.isNullOrBlank()) {
                                numbers.add(number.trim())
                            }
                        }
                    }
                }
            }

            // TelephonyManager line1Number
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (telephonyManager != null) {
                val line1 = telephonyManager.line1Number
                if (!line1.isNullOrBlank()) {
                    numbers.add(line1.trim())
                }
            }
        } catch (e: Exception) {
            Log.e("ViewModel", "Failed to query SIM sub numbers: ${e.message}")
        }
        return numbers.filter { it.isNotBlank() }.distinct()
    }

    fun parseQrCodePayload(jsonStr: String): Boolean {
        return try {
            val json = org.json.JSONObject(jsonStr)
            val apiUrl = json.optString("api_url")
            val clientApiKey = json.optString("client_api_key")

            if (apiUrl.isNotBlank() && clientApiKey.isNotBlank()) {
                backendUrl = apiUrl
                adminToken = clientApiKey
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    fun triggerQrCodeScan(onSuccess: (String) -> Unit, onFailure: (String) -> Unit) {
        try {
            val options = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
                .build()
            val scanner = com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(context, options)
            scanner.startScan()
                .addOnSuccessListener { barcode ->
                    val rawValue = barcode.rawValue
                    if (!rawValue.isNullOrBlank()) {
                        onSuccess(rawValue)
                    } else {
                        onFailure("Empty QR code payload read.")
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("ViewModel", "QR Scan Failed: ${e.message}")
                    onFailure(e.localizedMessage ?: "Scanning failed or was cancelled.")
                }
        } catch (e: Exception) {
            Log.e("ViewModel", "QR API build error: ${e.message}")
            onFailure("GMS Google Barcode Scanner API is currently initializing or unavailable.")
        }
    }
}

class GatewayViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(GatewayViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return GatewayViewModel(context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
