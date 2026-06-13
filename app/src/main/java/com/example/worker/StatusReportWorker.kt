package com.example.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.local.EncryptedPreferencesManager
import com.example.data.remote.RetrofitClient
import com.example.data.remote.model.SmsStatusReportRequest

class StatusReportWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val requestId = inputData.getString("request_id") ?: return Result.failure()
        val status = inputData.getString("status") ?: return Result.failure()
        val error = inputData.getString("error")
        val timestamp = inputData.getString("timestamp") ?: ""

        val prefs = EncryptedPreferencesManager.getInstance(applicationContext)
        val url = prefs.getBackendUrl()
        val devToken = prefs.getDeviceToken()

        if (url.isBlank() || devToken.isBlank()) {
            return Result.failure() // Cannot report without pairing details
        }

        try {
            val service = RetrofitClient.create(url)
            service.reportSmsStatus(
                "Bearer $devToken",
                SmsStatusReportRequest(requestId, status, error, timestamp)
            )
            Log.d("StatusReportWorker", "Background retry report succeeded for: $requestId")
            return Result.success()
        } catch (e: Exception) {
            Log.e("StatusReportWorker", "Background retry report failed for $requestId: ${e.message}")
            return Result.retry()
        }
    }
}
