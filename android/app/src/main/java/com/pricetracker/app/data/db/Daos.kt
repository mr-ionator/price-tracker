package com.pricetracker.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface ProductDao {
    @Insert suspend fun insert(product: ProductEntity): Long

    @Query("SELECT * FROM products ORDER BY id")
    suspend fun all(): List<ProductEntity>

    @Query("SELECT * FROM products WHERE id = :id")
    suspend fun byId(id: Int): ProductEntity?

    @Query("DELETE FROM products WHERE id = :id")
    suspend fun delete(id: Int)
}

@Dao
interface UrlDao {
    @Insert suspend fun insert(url: TrackedUrlEntity): Long

    @Update suspend fun update(url: TrackedUrlEntity)

    @Query("SELECT * FROM tracked_urls WHERE productId = :productId ORDER BY id")
    suspend fun forProduct(productId: Int): List<TrackedUrlEntity>
}

@Dao
interface PriceDao {
    @Insert suspend fun insert(point: PricePointEntity): Long

    @Query(
        "SELECT * FROM price_points WHERE trackedUrlId = :urlId " +
            "ORDER BY recordedAt DESC, id DESC LIMIT 1"
    )
    suspend fun latest(urlId: Int): PricePointEntity?

    @Query(
        "SELECT * FROM price_points WHERE trackedUrlId = :urlId " +
            "ORDER BY recordedAt ASC, id ASC"
    )
    suspend fun history(urlId: Int): List<PricePointEntity>
}

@Dao
interface AlertDao {
    @Insert suspend fun insert(alert: AlertEntity): Long

    @Update suspend fun update(alert: AlertEntity)

    @Query("SELECT * FROM alerts WHERE id = :id")
    suspend fun byId(id: Int): AlertEntity?

    @Query("SELECT * FROM alerts WHERE productId = :productId ORDER BY id")
    suspend fun forProduct(productId: Int): List<AlertEntity>

    @Query("DELETE FROM alerts WHERE id = :id")
    suspend fun delete(id: Int)
}

@Dao
interface NotificationDao {
    @Insert suspend fun insert(notification: NotificationEntity): Long

    @Query("SELECT * FROM notifications ORDER BY createdAt DESC, id DESC LIMIT 200")
    suspend fun all(): List<NotificationEntity>
}
