// Background price checking. Amazon/Currys don't expose prices to a headless
// fetch (JS-rendered and/or bot-walled), so scheduled checks render each URL in
// a real, hidden browser tab and scrape the live DOM — the same way the popup's
// "Track this page" works. Paradigit/generic try a fast fetch first and only
// fall back to rendering if that fails. Returns events for notifications.

import { extractFromHtmlString, normalizeUrl } from "./pricing.js";
import { addLogs, latestPrice, listProducts, saveProducts } from "./store.js";

const SCRAPE_DELAY_MS = 1200;
const PRICE_EPSILON = 0.005;
const RENDER_RETRIES = 6;
const RENDER_WAIT_MS = 1500;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function makeEvent(product, title, body, kind) {
  return { id: crypto.randomUUID(), title, body, kind, productId: product.id, at: Date.now() };
}

async function fetchHtml(url) {
  const response = await fetch(url, { redirect: "follow", credentials: "omit" });
  if (!response.ok) throw new Error("HTTP " + response.status);
  return await response.text();
}

function waitForTabComplete(tabId, timeoutMs = 15000) {
  return new Promise((resolve) => {
    let done = false;
    const finish = () => {
      if (done) return;
      done = true;
      chrome.tabs.onUpdated.removeListener(listener);
      resolve();
    };
    const listener = (id, info) => {
      if (id === tabId && info.status === "complete") finish();
    };
    chrome.tabs.onUpdated.addListener(listener);
    setTimeout(finish, timeoutMs);
  });
}

/**
 * Renders shop pages in one reusable, minimized background window. A real tab
 * runs the page's JS and carries the user's session, so Amazon/Currys behave
 * as they would for a normal visit. Call close() when the run is done.
 */
function createRenderer() {
  let winId = null;
  let tabId = null;
  return {
    async render(url, site) {
      if (tabId == null) {
        const win = await chrome.windows.create({
          url,
          focused: false,
          state: "minimized",
          type: "popup",
          width: 500,
          height: 640,
        });
        winId = win.id;
        tabId = win.tabs[0].id;
      } else {
        await chrome.tabs.update(tabId, { url });
      }
      await waitForTabComplete(tabId);
      let result = null;
      for (let i = 0; i < RENDER_RETRIES; i++) {
        try {
          await chrome.scripting.executeScript({ target: { tabId }, files: ["extract-core.js"] });
          const injections = await chrome.scripting.executeScript({
            target: { tabId },
            func: (s) => globalThis.ptExtractPrice(s),
            args: [site],
          });
          result = injections && injections[0] && injections[0].result;
        } catch (e) {
          result = { error: String((e && e.message) || e) };
        }
        if (result && result.price) break;
        await sleep(RENDER_WAIT_MS);
      }
      return result;
    },
    async close() {
      if (winId != null) {
        try {
          await chrome.windows.remove(winId);
        } catch (_) {
          /* already closed */
        }
        winId = null;
        tabId = null;
      }
    },
  };
}

async function priceForUrl(url, renderer) {
  // Fast path: sites that ship the price in HTML don't need a rendered tab.
  if (url.site === "paradigit" || url.site === "generic") {
    try {
      const found = extractFromHtmlString(await fetchHtml(url.url));
      if (found) return found;
    } catch (_) {
      /* fall through to rendering */
    }
  }
  const rendered = await renderer.render(url.url, url.site);
  if (rendered && rendered.price) {
    return { price: rendered.price, currency: rendered.currency || "EUR" };
  }
  if (rendered && rendered.error) throw new Error(rendered.error);
  throw new Error("could not extract a price");
}

function recordPrice(product, url, result, events) {
  url.lastCheckedAt = Date.now();
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
}

async function checkUrl(product, url, events, renderer) {
  try {
    const result = await priceForUrl(url, renderer);
    recordPrice(product, url, result, events);
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

async function checkProductObj(product, events, renderer) {
  for (let i = 0; i < product.urls.length; i++) {
    if (i > 0) await sleep(SCRAPE_DELAY_MS);
    await checkUrl(product, product.urls[i], events, renderer);
  }
  evaluateAlerts(product, events);
}

export async function checkAllProducts() {
  const products = await listProducts();
  const events = [];
  const renderer = createRenderer();
  try {
    for (let i = 0; i < products.length; i++) {
      if (i > 0) await sleep(SCRAPE_DELAY_MS);
      await checkProductObj(products[i], events, renderer);
    }
  } finally {
    await renderer.close();
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
    const renderer = createRenderer();
    try {
      await checkProductObj(product, events, renderer);
    } finally {
      await renderer.close();
    }
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

/** Apply a price captured by the content script while the user browsed. */
export async function applyCapturedPrice({ url, price, currency }) {
  if (typeof price !== "number" || !(price > 0)) return [];
  const products = await listProducts();
  const target = normalizeUrl(url);
  let match = null;
  for (const product of products) {
    for (const trackedUrl of product.urls) {
      if (normalizeUrl(trackedUrl.url) === target) {
        match = { product, trackedUrl };
        break;
      }
    }
    if (match) break;
  }
  if (!match) return [];
  const events = [];
  recordPrice(match.product, match.trackedUrl, { price, currency }, events);
  evaluateAlerts(match.product, events);
  await saveProducts(products);
  await addLogs(events);
  return events;
}
