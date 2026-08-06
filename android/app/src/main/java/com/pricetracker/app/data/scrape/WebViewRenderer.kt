package com.pricetracker.app.data.scrape

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Renders a shop page in a hidden, off-screen WebView so JavaScript runs and
 * the price is present in the DOM — the on-device equivalent of the browser
 * extension's background-tab rendering. This is how Amazon (JS-rendered) and
 * Currys (bot-walled to plain HTTP) actually yield a price on Android.
 */
class WebViewRenderer(context: Context) {
    private val appContext = context.applicationContext

    private sealed interface Parsed {
        data class Price(val result: ScrapeResult) : Parsed
        data class Failure(val message: String, val fatal: Boolean) : Parsed
        data object None : Parsed
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun render(url: String, site: String): ScrapeResult = withContext(Dispatchers.Main) {
        val webView = WebView(appContext)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = USER_AGENT
            loadsImagesAutomatically = false
            blockNetworkImage = true
        }
        try {
            // Wait for initial load; even on timeout the DOM may already be usable.
            withTimeoutOrNull(PAGE_LOAD_TIMEOUT_MS) { awaitPageFinished(webView, url) }

            var lastError: String? = null
            repeat(RENDER_RETRIES) {
                when (val parsed = parseResult(evaluate(webView, extractorJs(site)))) {
                    is Parsed.Price -> return@withContext parsed.result
                    is Parsed.Failure -> {
                        lastError = parsed.message
                        if (parsed.fatal) throw ScrapeException(parsed.message)
                    }
                    Parsed.None -> {}
                }
                delay(RENDER_WAIT_MS)
            }
            throw ScrapeException(lastError ?: "could not extract a price ($site) after render")
        } finally {
            webView.stopLoading()
            webView.destroy()
        }
    }

    private suspend fun awaitPageFinished(webView: WebView, url: String) =
        suspendCancellableCoroutine { cont ->
            webView.webViewClient = object : WebViewClient() {
                private var resumed = false
                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    if (!resumed) {
                        resumed = true
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
            }
            webView.loadUrl(url)
            cont.invokeOnCancellation { webView.stopLoading() }
        }

    private suspend fun evaluate(webView: WebView, js: String): String? =
        suspendCancellableCoroutine { cont ->
            webView.evaluateJavascript(js) { value -> if (cont.isActive) cont.resume(value) }
        }

    private fun parseResult(value: String?): Parsed {
        if (value == null || value == "null") return Parsed.None
        val token = runCatching { JSONTokener(value).nextValue() }.getOrNull() ?: return Parsed.None
        val json = when (token) {
            is JSONObject -> token
            is String -> runCatching { JSONObject(token) }.getOrNull() ?: return Parsed.None
            else -> return Parsed.None
        }
        val price = json.optDouble("price", -1.0)
        if (price > 0) {
            val currency = json.optString("currency", "EUR").ifBlank { "EUR" }
            return Parsed.Price(ScrapeResult(price, currency))
        }
        val error = json.optString("error", "")
        if (error.isNotBlank()) {
            return Parsed.Failure(error, fatal = error.contains("captcha", ignoreCase = true))
        }
        return Parsed.None
    }

    private fun extractorJs(site: String): String {
        val siteLiteral = JSONObject.quote(site)
        // Mirrors extract-core.js (the extension's in-page extractor), in ES5 for
        // maximum WebView compatibility. Returns a JSON string.
        return """
        (function(){
          var site = $siteLiteral;
          var NBSP = String.fromCharCode(160);
          function trim(s){ return String(s).replace(/^\s+|\s+${'$'}/g,''); }
          function parsePrice(text){
            var cleaned = String(text).split(NBSP).join(' ');
            var m = cleaned.match(/(\d{1,3}(?:[.,]\d{3})*(?:[.,]\d{1,2})?|\d+)/);
            if(!m) return null;
            var raw = m[1];
            var sep = Math.max(raw.lastIndexOf(','), raw.lastIndexOf('.'));
            if(sep === -1) return parseFloat(raw);
            var dec = raw.slice(sep+1);
            var intp = raw.slice(0,sep).replace(/[.,]/g,'');
            return dec.length <= 2 ? parseFloat(intp + '.' + dec) : parseFloat(raw.replace(/[.,]/g,''));
          }
          function isArray(x){ return Object.prototype.toString.call(x) === '[object Array]'; }
          function offer(node){
            if(isArray(node)){ for(var i=0;i<node.length;i++){ var r=offer(node[i]); if(r) return r; } return null; }
            if(node && typeof node === 'object'){
              var t = node['@type'];
              var ts = isArray(t) ? t : (t ? [t] : []);
              if(ts.indexOf('Offer')>=0 || ts.indexOf('AggregateOffer')>=0){
                var raw = (node.price != null) ? node.price : node.lowPrice;
                if(raw != null){
                  var v = parseFloat(String(raw).replace(',','.'));
                  if(!isNaN(v) && v>0) return { price: v, currency: String(node.priceCurrency || 'EUR') };
                }
              }
              for(var k in node){ if(Object.prototype.hasOwnProperty.call(node,k)){ var r2=offer(node[k]); if(r2) return r2; } }
            }
            return null;
          }
          function pageName(){
            var t = document.getElementById('productTitle') || document.querySelector('meta[property="og:title"]');
            if(t){ var v = (t.getAttribute && t.getAttribute('content')) || t.textContent || ''; if(trim(v)!=='') return trim(v).slice(0,120); }
            var h = document.querySelector('h1');
            if(h && h.textContent && trim(h.textContent)!=='') return trim(h.textContent).slice(0,120);
            return (document.title || 'Product').slice(0,120);
          }
          function result(){
            try{
              if(site === 'amazon'){
                if(document.querySelector('form[action*="validateCaptcha"]')) return {error:'amazon served a captcha page; retry later'};
                var ids = ['corePriceDisplay_desktop_feature_div','corePrice_feature_div'];
                for(var i=0;i<ids.length;i++){
                  var c = document.getElementById(ids[i]); if(!c) continue;
                  var off = c.querySelector('.a-price .a-offscreen');
                  if(off && off.textContent){ var v1=parsePrice(off.textContent); if(v1) return {price:v1,currency:'EUR',name:pageName()}; }
                  var whole = c.querySelector('.a-price-whole');
                  if(whole){ var text = whole.textContent.replace(/[.,]${'$'}/,''); var fr=c.querySelector('.a-price-fraction'); if(fr) text += '.'+fr.textContent; var v2=parsePrice(text); if(v2) return {price:v2,currency:'EUR',name:pageName()}; }
                }
                var apex = document.querySelector('#apex_desktop .a-price .a-offscreen');
                if(apex && apex.textContent){ var v3=parsePrice(apex.textContent); if(v3) return {price:v3,currency:'EUR',name:pageName()}; }
              }
              var scripts = document.querySelectorAll('script[type="application/ld+json"]');
              for(var s=0;s<scripts.length;s++){
                var rawJson = scripts[s].textContent; if(!rawJson) continue;
                var data; try{ data = JSON.parse(rawJson); }catch(e){ continue; }
                var r = offer(data); if(r) return {price:r.price, currency:r.currency, name:pageName()};
              }
              var sels = ['[data-testid="product-price"]','meta[property="product:price:amount"]','meta[property="og:price:amount"]','meta[itemprop="price"]','[itemprop="price"]','.price-current','.product-price .price','.product-price .value','.prices .price'];
              for(var j=0;j<sels.length;j++){
                var el = document.querySelector(sels[j]); if(!el) continue;
                var rawv = (el.getAttribute && el.getAttribute('content')) || el.textContent || ''; if(!rawv) continue;
                var v4 = parsePrice(rawv); if(v4) return {price:v4,currency:'EUR',name:pageName()};
              }
              return {error:'no price'};
            }catch(e){ return {error:String((e && e.message) || e)}; }
          }
          return JSON.stringify(result());
        })();
        """.trimIndent()
    }

    companion object {
        private const val PAGE_LOAD_TIMEOUT_MS = 25000L
        private const val RENDER_RETRIES = 6
        private const val RENDER_WAIT_MS = 1500L
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}
