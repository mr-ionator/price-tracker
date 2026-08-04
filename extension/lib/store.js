// Data layer over chrome.storage.local. Everything lives in the browser:
// products (with per-URL price history), alerts, settings, and an event log.

const DEFAULT_SETTINGS = { intervalMinutes: 180 };
const LOG_LIMIT = 200;

export const INTERVAL_OPTIONS = [
  { label: "Every 15 minutes", minutes: 15 },
  { label: "Every 30 minutes", minutes: 30 },
  { label: "Every hour", minutes: 60 },
  { label: "Every 3 hours", minutes: 180 },
  { label: "Every 6 hours", minutes: 360 },
  { label: "Every 12 hours", minutes: 720 },
  { label: "Once a day", minutes: 1440 },
];

async function getAll() {
  const data = await chrome.storage.local.get(["products", "settings", "log"]);
  return {
    products: data.products ?? [],
    settings: { ...DEFAULT_SETTINGS, ...(data.settings ?? {}) },
    log: data.log ?? [],
  };
}

export async function listProducts() {
  return (await getAll()).products;
}

export async function saveProducts(products) {
  await chrome.storage.local.set({ products });
}

export async function getSettings() {
  return (await getAll()).settings;
}

export async function setSettings(patch) {
  const settings = { ...(await getSettings()), ...patch };
  await chrome.storage.local.set({ settings });
  return settings;
}

export async function listLog() {
  return (await getAll()).log;
}

export async function addLogs(events) {
  if (!events.length) return;
  const { log } = await getAll();
  await chrome.storage.local.set({ log: [...events, ...log].slice(0, LOG_LIMIT) });
}

/** urlSpecs: [{ url, site, price?, currency?, error? }] */
export async function addProduct(name, urlSpecs) {
  const products = await listProducts();
  const now = Date.now();
  const product = {
    id: crypto.randomUUID(),
    name: name || "Product",
    createdAt: now,
    urls: urlSpecs.map((spec) => ({
      id: crypto.randomUUID(),
      site: spec.site,
      url: spec.url,
      lastCheckedAt: spec.price != null || spec.error ? now : null,
      lastError: spec.error || null,
      prices:
        spec.price != null
          ? [{ price: spec.price, currency: spec.currency || "EUR", at: now }]
          : [],
    })),
    alerts: [],
  };
  products.push(product);
  await saveProducts(products);
  return product;
}

export async function deleteProduct(id) {
  await saveProducts((await listProducts()).filter((p) => p.id !== id));
}

export async function addAlert(productId, targetPrice) {
  const products = await listProducts();
  const product = products.find((p) => p.id === productId);
  if (!product) return null;
  const alert = { id: crypto.randomUUID(), targetPrice, active: true, triggered: false };
  product.alerts.push(alert);
  await saveProducts(products);
  return alert;
}

export async function deleteAlert(productId, alertId) {
  const products = await listProducts();
  const product = products.find((p) => p.id === productId);
  if (!product) return;
  product.alerts = product.alerts.filter((a) => a.id !== alertId);
  await saveProducts(products);
}

export async function toggleAlert(productId, alertId) {
  const products = await listProducts();
  const product = products.find((p) => p.id === productId);
  if (!product) return;
  const alert = product.alerts.find((a) => a.id === alertId);
  if (!alert) return;
  alert.active = !alert.active;
  if (!alert.active) alert.triggered = false;
  await saveProducts(products);
}

// --- helpers shared by UI code ---

export function latestPrice(url) {
  return url.prices.length ? url.prices[url.prices.length - 1] : null;
}

export function lowestPrice(product) {
  const points = product.urls.map(latestPrice).filter(Boolean);
  if (!points.length) return null;
  return points.reduce((min, p) => (p.price < min.price ? p : min));
}
