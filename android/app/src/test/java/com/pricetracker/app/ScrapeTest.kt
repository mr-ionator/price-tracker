package com.pricetracker.app

import com.pricetracker.app.data.scrape.ScrapeException
import com.pricetracker.app.data.scrape.extractJsonLdPrice
import com.pricetracker.app.data.scrape.extractMetaPrice
import com.pricetracker.app.data.scrape.parsePriceText
import com.pricetracker.app.data.scrape.siteForUrl
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ScrapeTest {

    @Test
    fun parsesVariousLocalePriceFormats() {
        assertEquals(149.0, parsePriceText("€149.00"), 0.001)
        assertEquals(149.0, parsePriceText("149,00 €"), 0.001)
        assertEquals(1299.99, parsePriceText("€1,299.99"), 0.001)
        assertEquals(1299.99, parsePriceText("1.299,99"), 0.001)
        assertEquals(89.0, parsePriceText("EUR 89"), 0.001)
        assertEquals(59.99, parsePriceText("Now €59.99 was €79.99"), 0.001)
        assertEquals(1299.0, parsePriceText("1.299"), 0.001)
    }

    @Test
    fun rejectsGarbagePrice() {
        assertThrows(ScrapeException::class.java) { parsePriceText("out of stock") }
    }

    @Test
    fun detectsSiteFromUrl() {
        assertEquals("amazon", siteForUrl("https://www.amazon.ie/dp/B0ABC"))
        assertEquals("paradigit", siteForUrl("https://www.paradigit.ie/x/1/product"))
        assertEquals("currys", siteForUrl("https://www.currys.ie/products/laptop-123.html"))
        assertEquals("generic", siteForUrl("https://example.com/item"))
    }

    @Test
    fun extractsSchemaOrgJsonLdPrice() {
        val html = """
            <html><head>
            <script type="application/ld+json">
            {"@context":"https://schema.org","@type":"Product","name":"Test Laptop",
             "offers":{"@type":"Offer","price":"1049.99","priceCurrency":"EUR"}}
            </script>
            </head><body></body></html>
        """.trimIndent()
        val result = extractJsonLdPrice(Jsoup.parse(html))
        assertNotNull(result)
        assertEquals(1049.99, result!!.price, 0.001)
        assertEquals("EUR", result.currency)
    }

    @Test
    fun extractsJsonLdPriceFromGraphArray() {
        val html = """
            <html><head>
            <script type="application/ld+json">
            [{"@type":"BreadcrumbList"},
             {"@type":"Product","offers":{"@type":"Offer","price":329.5,"priceCurrency":"EUR"}}]
            </script>
            </head><body></body></html>
        """.trimIndent()
        val result = extractJsonLdPrice(Jsoup.parse(html))
        assertNotNull(result)
        assertEquals(329.5, result!!.price, 0.001)
    }

    @Test
    fun fallsBackToMetaPrice() {
        val html = """
            <html><head>
            <meta property="product:price:amount" content="59.95">
            </head><body></body></html>
        """.trimIndent()
        val result = extractMetaPrice(Jsoup.parse(html))
        assertNotNull(result)
        assertEquals(59.95, result!!.price, 0.001)
    }
}
