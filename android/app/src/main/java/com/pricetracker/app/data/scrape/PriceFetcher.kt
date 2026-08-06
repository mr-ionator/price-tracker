package com.pricetracker.app.data.scrape

import android.content.Context

/**
 * Chooses how to get a price per site:
 *  - paradigit / generic ship the price in the HTML, so try a fast OkHttp+Jsoup
 *    fetch first and only render if that fails.
 *  - amazon / currys need a real rendered page (JS / bot protection), so go
 *    straight to the hidden WebView.
 */
class PriceFetcher(context: Context) {
    private val scraper = PriceScraper()
    private val renderer = WebViewRenderer(context)

    suspend fun fetchPrice(site: String, url: String): ScrapeResult {
        if (site == "paradigit" || site == "generic") {
            runCatching { return scraper.scrape(url) }
        }
        return renderer.render(url, site)
    }
}
