// Shared, self-contained price extractor that runs INSIDE a shop page (real,
// rendered DOM). Used three ways, all in the extension's isolated world:
//   - as a content script (manifest content_scripts, with capture.js)
//   - injected into a background render tab by the service worker
//   - injected into the active tab by the popup's "Track this page"
// It defines a global so injected code can call it after the file is injected.
(function () {
  function detectSite() {
    const h = (location.hostname || "").toLowerCase();
    if (h.includes("amazon.")) return "amazon";
    if (h.includes("paradigit.")) return "paradigit";
    if (h.includes("currys.")) return "currys";
    return "generic";
  }

  function parsePrice(text) {
    const cleaned = String(text).replace(/\u00a0/g, " ");
    const m = cleaned.match(/(\d{1,3}(?:[.,]\d{3})*(?:[.,]\d{1,2})?|\d+)/);
    if (!m) return null;
    const raw = m[1];
    const sep = Math.max(raw.lastIndexOf(","), raw.lastIndexOf("."));
    if (sep === -1) return parseFloat(raw);
    const dec = raw.slice(sep + 1);
    const intp = raw.slice(0, sep).replace(/[.,]/g, "");
    return dec.length <= 2 ? parseFloat(intp + "." + dec) : parseFloat(raw.replace(/[.,]/g, ""));
  }

  function offer(node) {
    if (Array.isArray(node)) {
      for (const it of node) {
        const r = offer(it);
        if (r) return r;
      }
      return null;
    }
    if (node && typeof node === "object") {
      const t = node["@type"];
      const ts = Array.isArray(t) ? t : t ? [t] : [];
      if (ts.includes("Offer") || ts.includes("AggregateOffer")) {
        const raw = node.price ?? node.lowPrice;
        if (raw != null) {
          const v = parseFloat(String(raw).replace(",", "."));
          if (!isNaN(v) && v > 0) return { price: v, currency: String(node.priceCurrency || "EUR") };
        }
      }
      for (const k of Object.keys(node)) {
        const r = offer(node[k]);
        if (r) return r;
      }
    }
    return null;
  }

  function pageName() {
    const t =
      document.getElementById("productTitle") || document.querySelector('meta[property="og:title"]');
    if (t) {
      const v = (t.getAttribute && t.getAttribute("content")) || t.textContent || "";
      if (v.trim()) return v.trim().slice(0, 120);
    }
    const h = document.querySelector("h1");
    if (h && h.textContent.trim()) return h.textContent.trim().slice(0, 120);
    return (document.title || "Product").slice(0, 120);
  }

  globalThis.ptExtractPrice = function (site) {
    site = site || detectSite();
    try {
      if (site === "amazon") {
        if (document.querySelector('form[action*="validateCaptcha"]')) {
          return { error: "Amazon showed a captcha page. Reload the product page and try again." };
        }
        for (const id of ["corePriceDisplay_desktop_feature_div", "corePrice_feature_div"]) {
          const c = document.getElementById(id);
          if (!c) continue;
          const off = c.querySelector(".a-price .a-offscreen");
          if (off && off.textContent.trim()) {
            const v = parsePrice(off.textContent);
            if (v) return { price: v, currency: "EUR", name: pageName() };
          }
          const whole = c.querySelector(".a-price-whole");
          if (whole) {
            let text = whole.textContent.trim().replace(/[.,]$/, "");
            const fr = c.querySelector(".a-price-fraction");
            if (fr) text += "." + fr.textContent.trim();
            const v = parsePrice(text);
            if (v) return { price: v, currency: "EUR", name: pageName() };
          }
        }
        const apex = document.querySelector("#apex_desktop .a-price .a-offscreen");
        if (apex && apex.textContent.trim()) {
          const v = parsePrice(apex.textContent);
          if (v) return { price: v, currency: "EUR", name: pageName() };
        }
      }
      for (const s of document.querySelectorAll('script[type="application/ld+json"]')) {
        const raw = s.textContent.trim();
        if (!raw) continue;
        let data;
        try {
          data = JSON.parse(raw);
        } catch (_) {
          continue;
        }
        const r = offer(data);
        if (r) return { price: r.price, currency: r.currency, name: pageName() };
      }
      for (const sel of [
        '[data-testid="product-price"]',
        'meta[property="product:price:amount"]',
        'meta[property="og:price:amount"]',
        'meta[itemprop="price"]',
        '[itemprop="price"]',
        ".price-current",
        ".product-price .price",
        ".product-price .value",
        ".prices .price",
      ]) {
        const el = document.querySelector(sel);
        if (!el) continue;
        const raw = (el.getAttribute && el.getAttribute("content")) || el.textContent || "";
        if (!raw.trim()) continue;
        const v = parsePrice(raw);
        if (v) return { price: v, currency: "EUR", name: pageName() };
      }
      return { error: "Could not find a price on this page." };
    } catch (e) {
      return { error: String((e && e.message) || e) };
    }
  };
})();
