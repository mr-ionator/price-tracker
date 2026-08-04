// Price extraction shared across the extension. Mirrors the Android app's
// scrapers: site-specific selectors first, then schema.org JSON-LD, then
// price metadata. Pure functions — no chrome.* APIs — so they run in the
// service worker, the dashboard page, and (a self-contained copy) in the popup.

const PRICE_RE = /(\d{1,3}(?:[.,]\d{3})*(?:[.,]\d{1,2})?|\d+)/;

/** Parse a localized price like "€1.299,99", "1,299.99" or "149.00". */
export function parsePriceText(text) {
  const cleaned = String(text).replace(/\u00a0/g, " ");
  const match = cleaned.match(PRICE_RE);
  if (!match) throw new Error("no price found in: " + text);
  const raw = match[1];
  // The decimal separator is the last one, if followed by <= 2 digits.
  const lastSep = Math.max(raw.lastIndexOf(","), raw.lastIndexOf("."));
  if (lastSep === -1) return parseFloat(raw);
  const decimals = raw.slice(lastSep + 1);
  const integer = raw.slice(0, lastSep).replace(/[.,]/g, "");
  if (decimals.length <= 2) return parseFloat(integer + "." + decimals);
  return parseFloat(raw.replace(/[.,]/g, ""));
}

export function siteFromUrl(url) {
  let host = "";
  try {
    host = new URL(url).host.toLowerCase();
  } catch (_) {
    return "generic";
  }
  if (host.includes("amazon.")) return "amazon";
  if (host.includes("paradigit.")) return "paradigit";
  if (host.includes("currys.")) return "currys";
  return "generic";
}

/** Walk arbitrary JSON-LD looking for an Offer/AggregateOffer price. */
export function offerPriceFromJson(node) {
  if (Array.isArray(node)) {
    for (const item of node) {
      const found = offerPriceFromJson(item);
      if (found) return found;
    }
    return null;
  }
  if (node && typeof node === "object") {
    const type = node["@type"];
    const types = Array.isArray(type) ? type : type ? [type] : [];
    if (types.includes("Offer") || types.includes("AggregateOffer")) {
      const raw = node.price ?? node.lowPrice;
      if (raw !== undefined && raw !== null) {
        const value = parseFloat(String(raw).replace(",", "."));
        if (!isNaN(value) && value > 0) {
          const currency = String(node.priceCurrency || "EUR") || "EUR";
          return { price: value, currency };
        }
      }
    }
    for (const key of Object.keys(node)) {
      const found = offerPriceFromJson(node[key]);
      if (found) return found;
    }
  }
  return null;
}

/**
 * Extract a price from a raw HTML string using regexes only (no DOM).
 * Used by the background service worker, which has no DOMParser.
 */
export function extractFromHtmlString(html) {
  const scriptRe =
    /<script[^>]*type=["']application\/ld\+json["'][^>]*>([\s\S]*?)<\/script>/gi;
  let match;
  while ((match = scriptRe.exec(html))) {
    let raw = match[1].trim();
    raw = raw.replace(/^<!\[CDATA\[/, "").replace(/\]\]>$/, "").trim();
    if (!raw) continue;
    let data;
    try {
      data = JSON.parse(raw);
    } catch (_) {
      continue;
    }
    const found = offerPriceFromJson(data);
    if (found) return found;
  }
  // Price metadata (content attribute may sit before or after the key).
  const key = "(?:product:price:amount|og:price:amount|price)";
  const metaPatterns = [
    new RegExp(
      '<meta[^>]+(?:property|itemprop|name)=["\']' + key + '["\'][^>]*content=["\']([^"\']+)["\']',
      "i",
    ),
    new RegExp(
      '<meta[^>]+content=["\']([^"\']+)["\'][^>]*(?:property|itemprop|name)=["\']' + key + '["\']',
      "i",
    ),
  ];
  for (const pattern of metaPatterns) {
    const m = html.match(pattern);
    if (m) {
      try {
        return { price: parsePriceText(m[1]), currency: "EUR" };
      } catch (_) {
        /* keep trying */
      }
    }
  }
  return null;
}

/**
 * Extract a price from a parsed DOM. Used by the dashboard, which can use
 * DOMParser on fetched HTML and therefore supports site-specific selectors.
 */
export function extractFromDom(doc, site) {
  if (site === "amazon") {
    if (doc.querySelector('form[action*="validateCaptcha"]')) {
      throw new Error("amazon served a captcha page; retry later");
    }
    for (const id of ["corePriceDisplay_desktop_feature_div", "corePrice_feature_div"]) {
      const container = doc.getElementById(id);
      if (!container) continue;
      const offscreen = container.querySelector(".a-price .a-offscreen");
      if (offscreen && offscreen.textContent.trim()) {
        return { price: parsePriceText(offscreen.textContent), currency: "EUR" };
      }
      const whole = container.querySelector(".a-price-whole");
      if (whole) {
        let text = whole.textContent.trim().replace(/[.,]$/, "");
        const fraction = container.querySelector(".a-price-fraction");
        if (fraction) text += "." + fraction.textContent.trim();
        return { price: parsePriceText(text), currency: "EUR" };
      }
    }
    const apex = doc.querySelector("#apex_desktop .a-price .a-offscreen");
    if (apex && apex.textContent.trim()) {
      return { price: parsePriceText(apex.textContent), currency: "EUR" };
    }
  }
  if (site === "currys" || site === "paradigit") {
    for (const sel of [
      '[data-testid="product-price"]',
      ".product-price .price",
      ".product-price .value",
      ".prices .price",
      ".price-current",
    ]) {
      const el = doc.querySelector(sel);
      if (el && el.textContent.trim()) {
        try {
          return { price: parsePriceText(el.textContent), currency: "EUR" };
        } catch (_) {
          /* keep trying */
        }
      }
    }
  }
  for (const script of doc.querySelectorAll('script[type="application/ld+json"]')) {
    const raw = script.textContent.trim();
    if (!raw) continue;
    let data;
    try {
      data = JSON.parse(raw);
    } catch (_) {
      continue;
    }
    const found = offerPriceFromJson(data);
    if (found) return found;
  }
  for (const sel of [
    'meta[itemprop="price"]',
    'meta[property="product:price:amount"]',
    'meta[property="og:price:amount"]',
    '[itemprop="price"]',
  ]) {
    const el = doc.querySelector(sel);
    if (!el) continue;
    const raw = el.getAttribute("content") || el.textContent || "";
    if (!raw.trim()) continue;
    try {
      return { price: parsePriceText(raw), currency: "EUR" };
    } catch (_) {
      /* keep trying */
    }
  }
  return null;
}

export function nameFromDom(doc) {
  const tagged =
    doc.getElementById("productTitle") || doc.querySelector('meta[property="og:title"]');
  if (tagged) {
    const value = (tagged.getAttribute && tagged.getAttribute("content")) || tagged.textContent || "";
    if (value.trim()) return value.trim().slice(0, 120);
  }
  const h1 = doc.querySelector("h1");
  if (h1 && h1.textContent.trim()) return h1.textContent.trim().slice(0, 120);
  return (doc.title || "Product").trim().slice(0, 120);
}
