package com.pricetracker.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PricePoint(
    val price: Double,
    val currency: String = "EUR",
    @SerialName("recorded_at") val recordedAt: String,
)

@Serializable
data class TrackedUrl(
    val id: Int,
    val site: String,
    val url: String,
    @SerialName("last_checked_at") val lastCheckedAt: String? = null,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("latest_price") val latestPrice: PricePoint? = null,
)

@Serializable
data class Alert(
    val id: Int,
    @SerialName("product_id") val productId: Int,
    @SerialName("target_price") val targetPrice: Double,
    val active: Boolean,
    val triggered: Boolean,
)

@Serializable
data class Product(
    val id: Int,
    val name: String,
    @SerialName("created_at") val createdAt: String,
    val urls: List<TrackedUrl> = emptyList(),
    val alerts: List<Alert> = emptyList(),
) {
    val lowestPrice: PricePoint?
        get() = urls.mapNotNull { it.latestPrice }.minByOrNull { it.price }
}

@Serializable
data class ProductDetail(
    val id: Int,
    val name: String,
    @SerialName("created_at") val createdAt: String,
    val urls: List<TrackedUrl> = emptyList(),
    val alerts: List<Alert> = emptyList(),
    /** JSON object keys are strings: tracked_url_id -> chronological price points */
    val history: Map<String, List<PricePoint>> = emptyMap(),
)

@Serializable
data class AppNotification(
    val id: Int,
    val title: String,
    val body: String,
    val kind: String,
    @SerialName("product_id") val productId: Int? = null,
    @SerialName("created_at") val createdAt: String,
    val delivered: Boolean,
)

@Serializable
data class NewUrl(val url: String)

@Serializable
data class NewProduct(val name: String, val urls: List<NewUrl>)

@Serializable
data class NewAlert(@SerialName("target_price") val targetPrice: Double)

@Serializable
data class Health(
    val status: String,
    @SerialName("check_interval_minutes") val checkIntervalMinutes: Int,
    @SerialName("email_configured") val emailConfigured: Boolean,
    @SerialName("ntfy_configured") val ntfyConfigured: Boolean,
)
