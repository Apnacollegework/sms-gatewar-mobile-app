package com.example.data.remote

import com.example.data.remote.model.*
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

interface GatewayApiService {

    @POST("api/device/register")
    suspend fun registerDevice(
        @Header("Authorization") adminOrPairingToken: String?, // "Bearer <token>" if required
        @Body request: RegisterDeviceRequest
    ): RegisterDeviceResponse

    @POST("api/device/heartbeat")
    suspend fun sendHeartbeat(
        @Header("Authorization") bearerDeviceToken: String,
        @Body request: HeartbeatRequest
    ): HeartbeatResponse

    @POST("api/device/update-fcm-token")
    suspend fun updateFcmToken(
        @Header("Authorization") bearerDeviceToken: String,
        @Body request: UpdateFcmTokenRequest
    ): UpdateFcmTokenResponse

    @POST("api/device/sms-status")
    suspend fun reportSmsStatus(
        @Header("Authorization") bearerDeviceToken: String,
        @Body request: SmsStatusReportRequest
    ): SmsStatusReportResponse
}

object RetrofitClient {

    fun create(baseUrl: String): GatewayApiService {
        // Enforce trailing slash to adhere to Retrofit specifications
        val formattedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(formattedUrl)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(GatewayApiService::class.java)
    }
}
