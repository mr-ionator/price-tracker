// Background price checking: fetch each tracked URL, record changes, and
// evaluate alerts. Runs in the service worker (no DOM), so it relies on the
// regex-based extractor. Returns the events produced, for notifications.

import { extractFromHtmlString } from "./pricing.js";
import { addLogs, latestPrice, listProducts, saveProducts } from "./store.js";

const SCRAPE_DELAY_MS = 1200;
const PRICE_EPSILON = 0.005;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function makeEvent(product, title, body, kind) {
  return { id: crypto.randomUUID(), title, body, kind, productId: product.id, at: Date.now() };
}

async function fetchHtml(url) {
  const response = await fetch(url, { redirect: "follow", credentials: "omit" });
  if (!response.ok) throw new Error("HTTP " + response.status);
  return await response.text();
}

async function checkUrl(product, url, events) {
  try {
    const html = await fetchHtml(url.url);
    const result = extractFromHtmlString(html);
    url.lastCheckedAt = Date.now();
    if (!result) {
      url.lastError = "could not extract a price";
      return;
    }
    url.lastError = null;
    const previous = latestPrice(url);
    if (!previous || Math.abs(previous.price - result.price) >= PRICE_EPSILON) {
      url.prices.push({ price: result.price, currency: result.currency || "EUR", at: Date.now() });
      if (previous) {
        const direction = result.price < previous.price ? "dropped" : "increased";
        events.push(
          makeEvent(
            product,
            `${product.name}: price ${direction}`,
            `${product.name} on ${url.site} ${direction} from €${previous.price.toFixed(2)} ` +
              `to €${result.price.toFixed(2)}`,
            "price_change",
          ),
        );
      }
    }
  } catch (error) {
    url.lastCheckedAt = Date.now();
    url.lastError = String(error.message || error).slice(0, 300);
  }
}

function evaluateAlerts(product, events) {
  const priced = product.urls
    .map((url) => ({ url, point: latestPrice(url) }))
    .filter((entry) => entry.point);
  if (!priced.length) return;
  const best = priced.reduce((min, e) => (e.point.price < min.point.price ? e : min));

  for (const alert of product.alerts) {
    if (!alert.active) continue;
    if (best.point.price <= alert.targetPrice) {
      if (!alert.triggered) {
        alert.triggered = true;
        events.push(
          makeEvent(
            product,
            `${product.name} hit your target price!`,
            `${product.name} is now €${best.point.price.toFixed(2)} on ${best.url.site} ` +
              `(target €${alert.targetPrice.toFixed(2)})`,
            "alert",
          ),
        );
      }
    } else if (alert.triggered) {
      alert.triggered = false;
    }
  }
}

async function checkProductObj(product, events) {
  for (let i = 0; i < product.urls.length; i++) {
    if (i > 0) await sleep(SCRAPE_DELAY_MS);
    await checkUrl(product, product.urls[i], events);
  }
  evaluateAlerts(product, events);
}

export async function checkAllProducts() {
  const products = await listProducts();
  const events = [];
  for (let i = 0; i < products.length; i++) {
    if (i > 0) await sleep(SCRAPE_DELAY_MS);
    await checkProductObj(products[i], events);
  }
  await saveProducts(products);
  await addLogs(events);
  return events;
}

export async function checkProduct(id) {
  const products = await listProducts();
  const product = products.find((p) => p.id === id);
  const events = [];
  if (product) {
    await checkProductObj(product, events);
    await saveProducts(products);
    await addLogs(events);
  }
  return events;
}

/** Re-evaluate alerts without re-fetching (used right after adding an alert). */
export async function evaluateProduct(id) {
  const products = await listProducts();
  const product = products.find((p) => p.id === id);
  const events = [];
  if (product) {
    evaluateAlerts(product, events);
    await saveProducts(products);
    await addLogs(events);
  }
  return events;
}
