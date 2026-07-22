package com.pricetracker.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "tracked_urls",
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("productId")],
)
data class TrackedUrlEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val productId: Int,
    val site: String,
    val url: String,
    val lastCheckedAt: Long? = null,
    val lastError: String? = null,
)

@Entity(
    tableName = "price_points",
    foreignKeys = [
        ForeignKey(
            entity = TrackedUrlEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackedUrlId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("trackedUrlId")],
)
data class PricePointEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val trackedUrlId: Int,
    val price: Double,
    val currency: String = "EUR",
    val recordedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "alerts",
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("productId")],
)
data class AlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val productId: Int,
    val targetPrice: Double,
    val active: Boolean = true,
    // Set when the alert fires; cleared when the price rises above target again
    // so we don't re-notify on every check while the price stays low.
    val triggered: Boolean = false,
)

@Entity(tableName = "notifications")
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val body: String,
    val kind: String, // price_change | alert
    val productId: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
)
