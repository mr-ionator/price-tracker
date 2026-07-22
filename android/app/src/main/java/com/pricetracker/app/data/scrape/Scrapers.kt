package com.pricetracker.app.data.scrape

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class ScrapeException(message: String) : Exception(message)

data class ScrapeResult(val price: Double, val currency: String = "EUR")

private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

private val PRICE_RE = Regex("""(\d{1,3}(?:[.,]\d{3})*(?:[.,]\d{1,2})?|\d+)""")

/** Parse a localized price string like "€1.299,99", "1,299.99" or "149.00". */
fun parsePriceText(text: String): Double {
    val cleaned = text.replace('\u00A0', ' ')
    val match = PRICE_RE.find(cleaned) ?: throw ScrapeException("no price found in: $text")
    val raw = match.groupValues[1]
    // The decimal separator is the last one, if it is followed by <= 2 digits.
    val lastSep = maxOf(raw.lastIndexOf(','), raw.lastIndexOf('.'))
    if (lastSep == -1) return raw.toDouble()
    val decimals = raw.substring(lastSep + 1)
    val integer = raw.substring(0, lastSep).replace(Regex("[.,]"), "")
    return if (decimals.length <= 2) "$integer.$decimals".toDouble()
    else raw.replace(Regex("[.,]"), "").toDouble()
}

fun siteForUrl(url: String): String {
    val host = runCatching { java.net.URI(url).host ?: "" }.getOrDefault("").lowercase()
    return when {
        host.contains("amazon.") -> "amazon"
        host.contains("paradigit.") -> "paradigit"
        host.contains("currys.") -> "currys"
        else -> "generic"
    }
}

// --- Shared fallbacks -------------------------------------------------------

private fun jsonNodePrice(node: Any?): ScrapeResult? {
    when (node) {
        is JSONObject -> {
            val type = node.opt("@type")
            val types = when (type) {
                is JSONArray -> (0 until type.length()).map { type.optString(it) }
                is String -> listOf(type)
                else -> emptyList()
            }
            if (types.any { it == "Offer" || it == "AggregateOffer" }) {
                val rawPrice = node.opt("price") ?: node.opt("lowPrice")
                val value = rawPrice?.toString()?.replace(",", ".")?.toDoubleOrNull()
                if (value != null && value > 0) {
                    val currency = node.optString("priceCurrency", "EUR").ifBlank { "EUR" }
                    return ScrapeResult(value, currency)
                }
            }
            for (key in node.keys()) {
                jsonNodePrice(node.get(key))?.let { return it }
            }
        }
        is JSONArray -> {
            for (i in 0 until node.length()) {
                jsonNodePrice(node.get(i))?.let { return it }
            }
        }
    }
    return null
}

fun extractJsonLdPrice(doc: Document): ScrapeResult? {
    for (script in doc.select("script[type=application/ld+json]")) {
        val raw = script.data().ifBlank { script.html() }.trim()
        if (raw.isEmpty()) continue
        val parsed: Any? = runCatching {
            when (raw.first()) {
                '[' -> JSONArray(raw)
                else -> JSONObject(raw)
            }
        }.getOrNull() ?: continue
        jsonNodePrice(parsed)?.let { return it }
    }
    return null
}

fun extractMetaPrice(doc: Document): ScrapeResult? {
    val selectors = listOf(
        "meta[itemprop=price]",
        "meta[property=product:price:amount]",
        "meta[property=og:price:amount]",
        "meta[name=twitter:data1]",
        "[itemprop=price]",
    )
    for (selector in selectors) {
        val el = doc.selectFirst(selector) ?: continue
        val raw = el.attr("content").ifBlank { el.text() }
        if (raw.isBlank()) continue
        runCatching { return ScrapeResult(parsePriceText(raw)) }
    }
    return null
}

// --- Scrapers ---------------------------------------------------------------

private val httpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .callTimeout(45, TimeUnit.SECONDS)
    .followRedirects(true)
    .build()

abstract class SiteScraper {
    abstract val site: String

    protected open fun headers(): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9," +
            "image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-IE,en-GB;q=0.9,en;q=0.8",
        "Upgrade-Insecure-Requests" to "1",
    )

    private suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url)
        headers().forEach { (key, value) -> builder.header(key, value) }
        httpClient.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw ScrapeException("HTTP ${response.code} from $url")
            response.body?.string() ?: throw ScrapeException("empty body from $url")
        }
    }

    /** Site-specific selectors; null means fall back to shared extractors. */
    protected open fun parseSite(doc: Document): ScrapeResult? = null

    suspend fun scrape(url: String): ScrapeResult {
        val doc = Jsoup.parse(fetch(url), url)
        return parseSite(doc)
            ?: extractJsonLdPrice(doc)
            ?: extractMetaPrice(doc)
            ?: throw ScrapeException("could not extract a price ($site)")
    }
}

class GenericScraper : SiteScraper() {
    override val site = "generic"
}

class ParadigitScraper : SiteScraper() {
    override val site = "paradigit"

    override fun parseSite(doc: Document): ScrapeResult? {
        // Paradigit ships schema.org JSON-LD (handled by the shared fallback);
        // these cover the visible price block if the JSON-LD is ever absent.
        for (selector in listOf(
            "[data-testid=product-price]",
            ".product-price .price",
            ".price-current",
        )) {
            val el = doc.selectFirst(selector) ?: continue
            val text = el.text()
            if (text.isNotBlank()) runCatching { return ScrapeResult(parsePriceText(text)) }
        }
        return null
    }
}

class CurrysScraper : SiteScraper() {
    override val site = "currys"

    override fun headers(): Map<String, String> = super.headers() + mapOf(
        // Currys uses aggressive bot protection; a Referer and client hints
        // measurably improve the pass rate.
        "Referer" to "https://www.currys.ie/",
        "sec-ch-ua" to "\"Chromium\";v=\"126\", \"Google Chrome\";v=\"126\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Windows\"",
    )

    override fun parseSite(doc: Document): ScrapeResult? {
        for (selector in listOf(
            "[data-testid=product-price]",
            ".product-price .value",
            ".prices .price",
        )) {
            val el = doc.selectFirst(selector) ?: continue
            val text = el.text()
            if (text.isNotBlank()) runCatching { return ScrapeResult(parsePriceText(text)) }
        }
        return null
    }
}

class AmazonScraper : SiteScraper() {
    override val site = "amazon"

    override fun parseSite(doc: Document): ScrapeResult? {
        // Amazon serves an interstitial to suspected bots; surface it clearly.
        if (doc.selectFirst("form[action*=validateCaptcha]") != null) {
            throw ScrapeException("amazon served a captcha page; retry later")
        }
        for (containerId in listOf(
            "corePriceDisplay_desktop_feature_div",
            "corePrice_feature_div",
        )) {
            val container = doc.getElementById(containerId) ?: continue
            val offscreen = container.selectFirst(".a-price .a-offscreen")
            if (offscreen != null && offscreen.text().isNotBlank()) {
                return ScrapeResult(parsePriceText(offscreen.text()))
            }
            val whole = container.selectFirst(".a-price-whole")
            if (whole != null) {
                var text = whole.text().trimEnd('.', ',')
                container.selectFirst(".a-price-fraction")?.let { text += "." + it.text() }
                return ScrapeResult(parsePriceText(text))
            }
        }
        doc.selectFirst("#apex_desktop .a-price .a-offscreen")?.let {
            if (it.text().isNotBlank()) return ScrapeResult(parsePriceText(it.text()))
        }
        return null
    }
}

/** Facade that picks the right scraper for a URL. */
class PriceScraper {
    private val scrapers = mapOf(
        "amazon" to AmazonScraper(),
        "paradigit" to ParadigitScraper(),
        "currys" to CurrysScraper(),
        "generic" to GenericScraper(),
    )

    suspend fun scrape(url: String): ScrapeResult {
        val scraper = scrapers[siteForUrl(url)] ?: scrapers.getValue("generic")
        return scraper.scrape(url)
    }
}
