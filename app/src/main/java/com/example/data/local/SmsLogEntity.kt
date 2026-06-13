package com.example.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sms_logs",
    indices = [Index(value = ["request_id"], unique = true)]
)
data class SmsLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "request_id") val requestId: String,
    @ColumnInfo(name = "mobile") val mobile: String, // Masked phone number (e.g. +91987******10)
    @ColumnInfo(name = "message_preview") val messagePreview: String, // Truncated/Secure preview
    @ColumnInfo(name = "status") val status: String, // queued, sending, sent, delivered, failed...
    @ColumnInfo(name = "error") val error: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "retry_count") val retryCount: Int = 0
)
