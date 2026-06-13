package com.example.domain

import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager

class SmsSender(private val context: Context) {

    fun sendSms(
        phoneNumber: String,
        message: String,
        sentIntent: PendingIntent,
        deliveredIntent: PendingIntent,
        subscriptionId: Int? = null
    ) {
        val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val systemSmsManager = context.getSystemService(SmsManager::class.java)
            if (subscriptionId != null && subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                systemSmsManager.createForSubscriptionId(subscriptionId)
            } else {
                systemSmsManager
            }
        } else {
            @Suppress("DEPRECATION")
            val defaultSmsManager = SmsManager.getDefault()
            if (subscriptionId != null && subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                @Suppress("DEPRECATION")
                SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            } else {
                defaultSmsManager
            }
        }

        if (message.length > 160) {
            val parts = smsManager.divideMessage(message)
            val sentIntents = ArrayList<PendingIntent>().apply {
                repeat(parts.size) { add(sentIntent) }
            }
            val deliveredIntents = ArrayList<PendingIntent>().apply {
                repeat(parts.size) { add(deliveredIntent) }
            }
            smsManager.sendMultipartTextMessage(
                phoneNumber,
                null,
                parts,
                sentIntents,
                deliveredIntents
            )
        } else {
            smsManager.sendTextMessage(
                phoneNumber,
                null,
                message,
                sentIntent,
                deliveredIntent
            )
        }
    }
}
