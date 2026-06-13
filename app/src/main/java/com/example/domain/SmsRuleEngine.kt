package com.example.domain

import android.content.Context
import android.util.Log
import com.example.data.local.EncryptedPreferencesManager
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

class SmsRuleEngine(private val context: Context) {

    private val prefs = EncryptedPreferencesManager.getInstance(context)

    // Running memory list of transaction timestamps to enforce sliding window rate limiter
    companion object {
        private val recentTimestamps = mutableListOf<Long>()
    }

    /**
     * Checks if the request is fresh (within 2-minute skew to prevent replay attacks).
     * Accommodates both Unix epochs in seconds and milliseconds gracefully.
     */
    fun isTimestampFresh(timestampString: String): Boolean {
        return try {
            var epochMs = timestampString.toLongOrNull() ?: System.currentTimeMillis()
            // If the timestamp has 10 digits or fewer, it is in seconds. Scale to milliseconds.
            if (epochMs < 99999999999L) {
                epochMs *= 1000
            }
            val currentMs = System.currentTimeMillis()
            val diff = abs(currentMs - epochMs)
            diff <= 120000 // 120 seconds in milliseconds (2 minutes)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Computes HMAC-SHA256 of the payload fields using the registration device token as the secret key.
     * Supports both Vercel backend pipe format and the legacy colon-separated format for full coverage.
     */
    fun verifySignature(
        type: String,
        requestId: String,
        mobile: String,
        message: String,
        clientId: String,
        timestamp: String,
        providedSignature: String
    ): Boolean {
        var secret = prefs.getHmacSecret()
        if (secret.isBlank()) {
            secret = prefs.getDeviceToken()
        }
        if (secret.isBlank()) {
            Log.e("RuleEngine", "Verification failed: Device is not paired, key missing.")
            return false
        }

        try {
            // Format 1 (Vercel Backend Contract): "request_id|mobile|message|client_id|timestamp"
            val hmacInputBackend = "$requestId|$mobile|$message|$clientId|$timestamp"
            
            // Format 2 (Legacy Mock Format): "type:request_id:mobile:message:client_id:timestamp"
            val hmacInputLegacy = "$type:$requestId:$mobile:$message:$clientId:$timestamp"

            val keySpec = SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")

            // Test Format 1 (Vercel Format)
            val mac1 = Mac.getInstance("HmacSHA256")
            mac1.init(keySpec)
            val bytes1 = mac1.doFinal(hmacInputBackend.toByteArray(StandardCharsets.UTF_8))
            val computedSig1 = bytes1.joinToString("") { "%02x".format(it) }

            if (providedSignature.equals(computedSig1, ignoreCase = true)) {
                Log.d("RuleEngine", "HMAC verified successfully with Vercel backend format.")
                return true
            }

            // Test Format 2 (Legacy Format)
            val mac2 = Mac.getInstance("HmacSHA256")
            mac2.init(keySpec)
            val bytes2 = mac2.doFinal(hmacInputLegacy.toByteArray(StandardCharsets.UTF_8))
            val computedSig2 = bytes2.joinToString("") { "%02x".format(it) }

            if (providedSignature.equals(computedSig2, ignoreCase = true)) {
                Log.d("RuleEngine", "HMAC verified successfully with Legacy colon format.")
                return true
            }

            Log.e("RuleEngine", "HMAC Signature mismatch! Provided: '$providedSignature'. " +
                    "Computed backend-format: '$computedSig1' (Input: '$hmacInputBackend'). " +
                    "Computed legacy-format: '$computedSig2' (Input: '$hmacInputLegacy').")
            return false
        } catch (e: Exception) {
            Log.e("RuleEngine", "HMAC computation error: ${e.message}", e)
            return false
        }
    }

    /**
     * Enforces strict compliance on local transactional limits (max 5/min, customized max/day).
     */
    suspend fun checkRateLimits(currentSentToday: Int): RateLimitResult {
        val now = System.currentTimeMillis()

        synchronized(recentTimestamps) {
            // Clean slate for minute constraints older than 60 seconds
            recentTimestamps.removeAll { now - it > 60000 }

            val maxPerMin = prefs.getMaxSmsPerMinute()
            if (recentTimestamps.size >= maxPerMin) {
                return RateLimitResult.Blocked("Minute limit exceeded (Max $maxPerMin / min)")
            }

            val maxPerDay = prefs.getMaxSmsPerDay()
            if (currentSentToday >= maxPerDay) {
                return RateLimitResult.Blocked("Daily quota reached ($currentSentToday/$maxPerDay used)")
            }

            // Passed limits, record timestamp
            recentTimestamps.add(now)
            return RateLimitResult.Allowed
        }
    }
}

sealed class RateLimitResult {
    object Allowed : RateLimitResult()
    data class Blocked(val reason: String) : RateLimitResult()
}
