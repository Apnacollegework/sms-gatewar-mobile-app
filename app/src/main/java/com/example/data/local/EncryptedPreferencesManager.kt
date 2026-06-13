package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class EncryptedPreferencesManager private constructor(context: Context) {

    private val prefs: SharedPreferences

    init {
        prefs = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                "sms_gateway_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e("SecurePrefs", "Fallback to standard preferences: ${e.message}")
            context.getSharedPreferences("sms_gateway_fallback_prefs", Context.MODE_PRIVATE)
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: EncryptedPreferencesManager? = null

        fun getInstance(context: Context): EncryptedPreferencesManager {
            return INSTANCE ?: synchronized(this) {
                val instance = EncryptedPreferencesManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }

    fun saveBackendUrl(url: String) {
        prefs.edit().putString("backend_url", url.trim()).apply()
    }

    fun getBackendUrl(): String {
        return prefs.getString("backend_url", "") ?: ""
    }

    fun saveAdminToken(token: String) {
        prefs.edit().putString("admin_token", token.trim()).apply()
    }

    fun getAdminToken(): String {
        return prefs.getString("admin_token", "") ?: ""
    }

    fun saveDeviceToken(token: String) {
        prefs.edit().putString("device_token", token.trim()).apply()
    }

    fun getDeviceToken(): String {
        return prefs.getString("device_token", "") ?: ""
    }

    fun saveDeviceId(id: String) {
        prefs.edit().putString("device_id", id.trim()).apply()
    }

    fun getDeviceId(): String {
        return prefs.getString("device_id", "") ?: ""
    }

    fun saveHmacSecret(secret: String) {
        prefs.edit().putString("hmac_secret", secret.trim()).apply()
    }

    fun getHmacSecret(): String {
        return prefs.getString("hmac_secret", "") ?: ""
    }

    fun savePhoneNumber(phone: String) {
        prefs.edit().putString("phone_number", phone.trim()).apply()
    }

    fun getPhoneNumber(): String {
        return prefs.getString("phone_number", "") ?: ""
    }

    fun saveLastHeartbeatTime(time: Long) {
        prefs.edit().putLong("last_heartbeat_time", time).apply()
    }

    fun getLastHeartbeatTime(): Long {
        return prefs.getLong("last_heartbeat_time", 0L)
    }

    fun saveFcmToken(token: String) {
        prefs.edit().putString("fcm_token", token.trim()).apply()
    }

    fun getFcmToken(): String {
        return prefs.getString("fcm_token", "") ?: ""
    }

    fun saveGatewayActive(active: Boolean) {
        prefs.edit().putBoolean("gateway_active", active).apply()
    }

    fun isGatewayActive(): Boolean {
        return prefs.getBoolean("gateway_active", true)
    }

    fun saveMaxSmsPerMinute(limit: Int) {
        prefs.edit().putInt("max_sms_per_min", limit).apply()
    }

    fun getMaxSmsPerMinute(): Int {
        return prefs.getInt("max_sms_per_min", 5)
    }

    fun saveMaxSmsPerDay(limit: Int) {
        prefs.edit().putInt("max_sms_per_day", limit).apply()
    }

    fun getMaxSmsPerDay(): Int {
        return prefs.getInt("max_sms_per_day", 100)
    }

    fun saveDebugMode(enabled: Boolean) {
        prefs.edit().putBoolean("debug_mode", enabled).apply()
    }

    fun isDebugMode(): Boolean {
        return prefs.getBoolean("debug_mode", false)
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
