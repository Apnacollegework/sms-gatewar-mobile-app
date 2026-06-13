package com.example.data.remote.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RegisterDeviceRequest(
    @Json(name = "device_name") val deviceName: String,
    @Json(name = "phone_number") val phoneNumber: String,
    @Json(name = "android_version") val androidVersion: String,
    @Json(name = "app_version") val appVersion: String,
    @Json(name = "fcm_token") val fcmToken: String
)

@JsonClass(generateAdapter = true)
data class RegisterDeviceResponse(
    @Json(name = "device_id") val deviceId: String?,
    @Json(name = "device_token") val deviceToken: String?,
    @Json(name = "hmac_secret") val hmacSecret: String?,
    @Json(name = "status") val status: String?
)

@JsonClass(generateAdapter = true)
data class HeartbeatRequest(
    @Json(name = "battery_optimization_ignored") val batteryOptimizationIgnored: Boolean,
    @Json(name = "sms_permission") val smsPermission: Boolean,
    @Json(name = "sim_ready") val simReady: Boolean,
    @Json(name = "foreground_service_running") val foregroundServiceRunning: Boolean
)

@JsonClass(generateAdapter = true)
data class HeartbeatLimits(
    @Json(name = "dailyLimit") val dailyLimit: Int?,
    @Json(name = "perMinuteLimit") val perMinuteLimit: Int?
)

@JsonClass(generateAdapter = true)
data class HeartbeatResponse(
    @Json(name = "success") val success: Boolean,
    @Json(name = "limits") val limits: HeartbeatLimits?
)

@JsonClass(generateAdapter = true)
data class UpdateFcmTokenRequest(
    @Json(name = "fcm_token") val fcmToken: String
)

@JsonClass(generateAdapter = true)
data class UpdateFcmTokenResponse(
    @Json(name = "status") val status: String?
)

@JsonClass(generateAdapter = true)
data class SmsStatusReportRequest(
    @Json(name = "request_id") val requestId: String,
    @Json(name = "status") val status: String, // sent, failed, delivered
    @Json(name = "error") val error: String?,
    @Json(name = "timestamp") val timestamp: String
)

@JsonClass(generateAdapter = true)
data class SmsStatusReportResponse(
    @Json(name = "status") val status: String?
)
