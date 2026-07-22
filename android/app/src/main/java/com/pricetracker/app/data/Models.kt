package com.pricetracker.app.data

/** Plain UI models assembled from Room entities by [Repository]. */

data class PricePoint(
    val price: Double,
    val currency: String = "EUR",
    val recordedAt: Long,
)

data class TrackedUrl(
    val id: Int,
    val site: String,
    val url: String,
    val lastCheckedAt: Long?,
    val lastError: String?,
    val latestPrice: PricePoint?,
)

data class Alert(
    val id: Int,
    val productId: Int,
    val targetPrice: Double,
    val active: Boolean,
    val triggered: Boolean,
)

data class Product(
    val id: Int,
    val name: String,
    val createdAt: Long,
    val urls: List<TrackedUrl>,
    val alerts: List<Alert>,
) {
    val lowestPrice: PricePoint?
        get() = urls.mapNotNull { it.latestPrice }.minByOrNull { it.price }
}

data class ProductDetail(
    val id: Int,
    val name: String,
    val createdAt: Long,
    val urls: List<TrackedUrl>,
    val alerts: List<Alert>,
    /** tracked-url id (as string) -> chronological price points */
    val history: Map<String, List<PricePoint>>,
)

data class AppNotification(
    val id: Int,
    val title: String,
    val body: String,
    val kind: String,
    val productId: Int?,
    val createdAt: Long,
)
