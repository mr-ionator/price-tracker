package com.pricetracker.app.data

import android.content.Context
import com.pricetracker.app.data.db.AlertEntity
import com.pricetracker.app.data.db.AppDatabase
import com.pricetracker.app.data.db.NotificationEntity
import com.pricetracker.app.data.db.PricePointEntity
import com.pricetracker.app.data.db.ProductEntity
import com.pricetracker.app.data.db.TrackedUrlEntity
import com.pricetracker.app.data.scrape.PriceScraper
import com.pricetracker.app.data.scrape.siteForUrl
import kotlin.math.abs
import kotlinx.coroutines.delay

/**
 * Everything runs on the phone: prices are scraped directly and stored in a
 * local Room database. No server, no account, no configuration.
 */
class Repository(context: Context) {
    private val db = AppDatabase.get(context)
    private val scraper = PriceScraper()

    private val productDao = db.productDao()
    private val urlDao = db.urlDao()
    private val priceDao = db.priceDao()
    private val alertDao = db.alertDao()
    private val notificationDao = db.notificationDao()

    companion object {
        // Politeness pause between two scrapes in one run.
        private const val SCRAPE_DELAY_MS = 1500L
        private const val PRICE_EPSILON = 0.005
    }

    // --- Reads ---------------------------------------------------------------

    suspend fun listProducts(): List<Product> =
        productDao.all().map { assembleProduct(it) }

    suspend fun getProduct(id: Int): ProductDetail {
        val product = productDao.byId(id) ?: throw NoSuchElementException("product not found")
        val base = assembleProduct(product)
        val history = base.urls.associate { url ->
            url.id.toString() to priceDao.history(url.id).map {
                PricePoint(it.price, it.currency, it.recordedAt)
            }
        }
        return ProductDetail(product.id, product.name, product.createdAt, base.urls, base.alerts, history)
    }

    suspend fun allNotifications(): List<AppNotification> =
        notificationDao.all().map {
            AppNotification(it.id, it.title, it.body, it.kind, it.productId, it.createdAt)
        }

    // --- Mutations -----------------------------------------------------------

    suspend fun createProduct(name: String, urls: List<String>): Product {
        val productId = productDao.insert(ProductEntity(name = name)).toInt()
        for (raw in urls) {
            urlDao.insert(TrackedUrlEntity(productId = productId, site = siteForUrl(raw), url = raw))
        }
        checkProductInternal(productId)
        return assembleProduct(productDao.byId(productId)!!)
    }

    suspend fun deleteProduct(id: Int) = productDao.delete(id)

    suspend fun refreshProduct(id: Int): Product {
        checkProductInternal(id)
        return assembleProduct(productDao.byId(id) ?: throw NoSuchElementException("product not found"))
    }

    suspend fun createAlert(productId: Int, targetPrice: Double): Alert {
        val alertId = alertDao.insert(
            AlertEntity(productId = productId, targetPrice = targetPrice)
        ).toInt()
        evaluateAlerts(productId)
        val entity = alertDao.byId(alertId)!!
        return Alert(entity.id, entity.productId, entity.targetPrice, entity.active, entity.triggered)
    }

    suspend fun deleteAlert(id: Int) = alertDao.delete(id)

    suspend fun toggleAlert(id: Int): Alert {
        val current = alertDao.byId(id) ?: throw NoSuchElementException("alert not found")
        val updated = current.copy(
            active = !current.active,
            triggered = if (current.active) false else current.triggered,
        )
        alertDao.update(updated)
        return Alert(updated.id, updated.productId, updated.targetPrice, updated.active, updated.triggered)
    }

    /** Re-check every product; returns the events produced (for notifications). */
    suspend fun checkAll(): List<AppNotification> {
        val events = mutableListOf<AppNotification>()
        productDao.all().forEachIndexed { index, product ->
            if (index > 0) delay(SCRAPE_DELAY_MS)
            events += checkProductInternal(product.id)
        }
        return events
    }

    // --- Internals -----------------------------------------------------------

    private suspend fun checkProductInternal(productId: Int): List<AppNotification> {
        val events = mutableListOf<AppNotification>()
        urlDao.forProduct(productId).forEachIndexed { index, url ->
            if (index > 0) delay(SCRAPE_DELAY_MS)
            events += checkUrl(url)
        }
        events += evaluateAlerts(productId)
        return events
    }

    private suspend fun checkUrl(url: TrackedUrlEntity): List<AppNotification> {
        val events = mutableListOf<AppNotification>()
        try {
            val result = scraper.scrape(url.url)
            urlDao.update(url.copy(lastCheckedAt = System.currentTimeMillis(), lastError = null))
            val previous = priceDao.latest(url.id)
            if (previous == null || abs(previous.price - result.price) >= PRICE_EPSILON) {
                priceDao.insert(
                    PricePointEntity(
                        trackedUrlId = url.id,
                        price = result.price,
                        currency = result.currency,
                    )
                )
                if (previous != null) {
                    val name = productDao.byId(url.productId)?.name ?: "Product"
                    val direction = if (result.price < previous.price) "dropped" else "increased"
                    events += recordEvent(
                        title = "$name: price $direction",
                        body = "$name on ${url.site} $direction from " +
                            "€%.2f to €%.2f\n%s".format(previous.price, result.price, url.url),
                        kind = "price_change",
                        productId = url.productId,
                    )
                }
            }
        } catch (e: Exception) {
            urlDao.update(
                url.copy(
                    lastCheckedAt = System.currentTimeMillis(),
                    lastError = (e.message ?: "check failed").take(500),
                )
            )
        }
        return events
    }

    private suspend fun evaluateAlerts(productId: Int): List<AppNotification> {
        val events = mutableListOf<AppNotification>()
        val priced = urlDao.forProduct(productId).mapNotNull { url ->
            priceDao.latest(url.id)?.let { url to it }
        }
        if (priced.isEmpty()) return events
        val (bestUrl, best) = priced.minByOrNull { it.second.price }!!
        val name = productDao.byId(productId)?.name ?: "Product"

        for (alert in alertDao.forProduct(productId)) {
            if (!alert.active) continue
            if (best.price <= alert.targetPrice) {
                if (!alert.triggered) {
                    alertDao.update(alert.copy(triggered = true))
                    events += recordEvent(
                        title = "$name hit your target price!",
                        body = "$name is now €%.2f on %s (target €%.2f)\n%s".format(
                            best.price, bestUrl.site, alert.targetPrice, bestUrl.url,
                        ),
                        kind = "alert",
                        productId = productId,
                    )
                }
            } else if (alert.triggered) {
                alertDao.update(alert.copy(triggered = false))
            }
        }
        return events
    }

    private suspend fun recordEvent(
        title: String,
        body: String,
        kind: String,
        productId: Int?,
    ): AppNotification {
        val id = notificationDao.insert(
            NotificationEntity(title = title, body = body, kind = kind, productId = productId)
        ).toInt()
        return AppNotification(id, title, body, kind, productId, System.currentTimeMillis())
    }

    private suspend fun assembleProduct(product: ProductEntity): Product {
        val urls = urlDao.forProduct(product.id).map { url ->
            val latest = priceDao.latest(url.id)
            TrackedUrl(
                id = url.id,
                site = url.site,
                url = url.url,
                lastCheckedAt = url.lastCheckedAt,
                lastError = url.lastError,
                latestPrice = latest?.let { PricePoint(it.price, it.currency, it.recordedAt) },
            )
        }
        val alerts = alertDao.forProduct(product.id).map {
            Alert(it.id, it.productId, it.targetPrice, it.active, it.triggered)
        }
        return Product(product.id, product.name, product.createdAt, urls, alerts)
    }
}
