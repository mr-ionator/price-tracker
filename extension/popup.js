import { addProduct, latestPrice, listProducts, lowestPrice } from "./lib/store.js";
import { siteFromUrl } from "./lib/pricing.js";

const listEl = document.getElementById("list");
const statusEl = document.getElementById("status");

function setStatus(text, isError = false) {
  statusEl.hidden = !text;
  statusEl.textContent = text || "";
  statusEl.classList.toggle("error", isError);
}

async function render() {
  const products = await listProducts();
  listEl.replaceChildren();
  if (!products.length) {
    const empty = document.createElement("p");
    empty.className = "muted";
    empty.textContent = "No products yet. Open a product page on a supported shop and tap “Track this page”.";
    listEl.append(empty);
    return;
  }
  for (const product of products) {
    const row = document.createElement("div");
    row.className = "item";

    const name = document.createElement("span");
    name.className = "name";
    name.textContent = product.name;

    const price = document.createElement("span");
    price.className = "price";
    const low = lowestPrice(product);
    price.textContent = low ? "€" + low.price.toFixed(2) : "—";

    row.append(name, price);
    listEl.append(row);
  }
}

/**
 * Runs inside the active shop tab (via chrome.scripting). Self-contained: it
 * cannot import, so it carries its own compact price/JSON-LD parsing.
 */
function extractInPage(site) {
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
    const t = document.getElementById("productTitle") || document.querySelector('meta[property="og:title"]');
    if (t) {
      const v = (t.getAttribute && t.getAttribute("content")) || t.textContent || "";
      if (v.trim()) return v.trim().slice(0, 120);
    }
    const h = document.querySelector("h1");
    if (h && h.textContent.trim()) return h.textContent.trim().slice(0, 120);
    return (document.title || "Product").slice(0, 120);
  }
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
}

async function trackThisPage() {
  setStatus("Reading this page…");
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (!tab || !tab.url || !/^https?:/.test(tab.url)) {
    setStatus("Open a product page first.", true);
    return;
  }
  const site = siteFromUrl(tab.url);
  let result;
  try {
    const [injection] = await chrome.scripting.executeScript({
      target: { tabId: tab.id },
      func: extractInPage,
      args: [site],
    });
    result = injection && injection.result;
  } catch (_) {
    setStatus("This page can’t be read (try reloading it).", true);
    return;
  }
  if (!result || result.error) {
    setStatus(result ? result.error : "Could not read a price.", true);
    return;
  }
  await addProduct(result.name || tab.title || "Product", [
    { url: tab.url, site, price: result.price, currency: result.currency },
  ]);
  setStatus(`Tracking “${result.name}” at €${result.price.toFixed(2)}.`);
  await render();
}

document.getElementById("track").addEventListener("click", trackThisPage);

document.getElementById("openDashboard").addEventListener("click", () => {
  chrome.runtime.openOptionsPage();
  window.close();
});

document.getElementById("checkNow").addEventListener("click", async () => {
  setStatus("Checking all prices…");
  const response = await chrome.runtime.sendMessage({ type: "checkNow" });
  if (response && response.ok) {
    setStatus(response.count ? `Done — ${response.count} update(s).` : "Done — no changes.");
    await render();
  } else {
    setStatus("Check failed.", true);
  }
});

render();
