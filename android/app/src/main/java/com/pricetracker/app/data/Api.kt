package com.pricetracker.app.data

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface PriceTrackerApi {
    @GET("health")
    suspend fun health(): Health

    @GET("products")
    suspend fun listProducts(): List<Product>

    @POST("products")
    suspend fun createProduct(@Body product: NewProduct): Product

    @GET("products/{id}")
    suspend fun getProduct(@Path("id") id: Int): ProductDetail

    @DELETE("products/{id}")
    suspend fun deleteProduct(@Path("id") id: Int)

    @POST("products/{id}/refresh")
    suspend fun refreshProduct(@Path("id") id: Int): Product

    @POST("products/{id}/alerts")
    suspend fun createAlert(@Path("id") productId: Int, @Body alert: NewAlert): Alert

    @DELETE("alerts/{id}")
    suspend fun deleteAlert(@Path("id") id: Int)

    @POST("alerts/{id}/toggle")
    suspend fun toggleAlert(@Path("id") id: Int): Alert

    @GET("notifications")
    suspend fun listNotifications(
        @Query("undelivered_only") undeliveredOnly: Boolean = false,
    ): List<AppNotification>

    @POST("notifications/mark-delivered")
    suspend fun markDelivered(@Body ids: List<Int>)
}

object ApiFactory {
    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // Creating a product scrapes all its URLs synchronously; allow time.
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    @Volatile private var cached: Pair<String, PriceTrackerApi>? = null

    fun create(baseUrl: String): PriceTrackerApi {
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        cached?.let { (url, api) -> if (url == normalized) return api }
        val api = Retrofit.Builder()
            .baseUrl(normalized)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PriceTrackerApi::class.java)
        cached = normalized to api
        return api
    }
}
