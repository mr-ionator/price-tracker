package com.pricetracker.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ProductEntity::class,
        TrackedUrlEntity::class,
        PricePointEntity::class,
        AlertEntity::class,
        NotificationEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun urlDao(): UrlDao
    abstract fun priceDao(): PriceDao
    abstract fun alertDao(): AlertDao
    abstract fun notificationDao(): NotificationDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "pricetracker.db",
            ).build().also { instance = it }
        }
    }
}
