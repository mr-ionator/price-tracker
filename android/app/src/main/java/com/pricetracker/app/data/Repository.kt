package com.pricetracker.app.data

import android.content.Context

/** Thin facade that resolves the user-configured backend URL per call. */
class Repository(context: Context) {
    private val settings = SettingsStore(context.applicationContext)

    private suspend fun api(): PriceTrackerApi =
        ApiFactory.create(settings.currentBackendUrl())

    suspend fun health(): Health = api().health()
    suspend fun listProducts(): List<Product> = api().listProducts()
    suspend fun createProduct(name: String, urls: List<String>): Product =
        api().createProduct(NewProduct(name, urls.map { NewUrl(it) }))

    suspend fun getProduct(id: Int): ProductDetail = api().getProduct(id)
    suspend fun deleteProduct(id: Int) = api().deleteProduct(id)
    suspend fun refreshProduct(id: Int): Product = api().refreshProduct(id)

    suspend fun createAlert(productId: Int, targetPrice: Double): Alert =
        api().createAlert(productId, NewAlert(targetPrice))

    suspend fun deleteAlert(id: Int) = api().deleteAlert(id)
    suspend fun toggleAlert(id: Int): Alert = api().toggleAlert(id)

    suspend fun undeliveredNotifications(): List<AppNotification> =
        api().listNotifications(undeliveredOnly = true)

    suspend fun allNotifications(): List<AppNotification> = api().listNotifications()
    suspend fun markDelivered(ids: List<Int>) = api().markDelivered(ids)
}
