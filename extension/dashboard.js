import {
  INTERVAL_OPTIONS,
  addAlert,
  addProduct,
  deleteAlert,
  deleteProduct,
  getSettings,
  latestPrice,
  listLog,
  listProducts,
  setSettings,
  toggleAlert,
} from "./lib/store.js";
import { extractFromDom, nameFromDom, siteFromUrl } from "./lib/pricing.js";

// --- tiny DOM helper --------------------------------------------------------

function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (value == null) continue;
    if (key === "class") node.className = value;
    else if (key === "text") node.textContent = value;
    else if (key === "onclick") node.addEventListener("click", value);
    else if (key === "onchange") node.addEventListener("change", value);
    else node.setAttribute(key, value);
  }
  for (const child of children.flat()) {
    if (child == null || child === false) continue;
    node.append(child.nodeType ? child : document.createTextNode(String(child)));
  }
  return node;
}

const euro = (n) => "€" + n.toFixed(2);
const fmtTime = (ms) => new Date(ms).toLocaleString();

const statusEl = document.getElementById("status");
function flash(text) {
  statusEl.textContent = text;
  if (text) setTimeout(() => (statusEl.textContent = ""), 4000);
}

// --- add-product form -------------------------------------------------------

const urlFieldsEl = document.getElementById("urlFields");
function addUrlField() {
  urlFieldsEl.append(
    el("input", {
      type: "url",
      class: "field-input url-input",
      placeholder: "https://www.paradigit.ie/…/product",
    }),
  );
}
addUrlField();
addUrlField();
addUrlField();
document.getElementById("addUrlField").addEventListener("click", addUrlField);

/** Fetch a URL in this page (extension pages bypass CORS for granted hosts). */
async function fetchInitialPrice(url, site) {
  try {
    const response = await fetch(url, { redirect: "follow", credentials: "omit" });
    if (!response.ok) throw new Error("HTTP " + response.status);
    const html = await response.text();
    const doc = new DOMParser().parseFromString(html, "text/html");
    const result = extractFromDom(doc, site);
    return { result, doc };
  } catch (error) {
    return { error: String(error.message || error), doc: null };
  }
}

document.getElementById("addProduct").addEventListener("click", async () => {
  const addStatus = document.getElementById("addStatus");
  const nameInput = document.getElementById("newName");
  const urls = [...urlFieldsEl.querySelectorAll(".url-input")]
    .map((i) => i.value.trim())
    .filter(Boolean);

  if (!urls.length) {
    addStatus.textContent = "Add at least one product URL.";
    return;
  }
  if (urls.some((u) => !/^https?:\/\//.test(u))) {
    addStatus.textContent = "URLs must start with http:// or https://";
    return;
  }

  addStatus.textContent = "Fetching current prices…";
  const specs = [];
  let derivedName = nameInput.value.trim();
  for (const url of urls) {
    const site = siteFromUrl(url);
    const { result, error, doc } = await fetchInitialPrice(url, site);
    if (result) {
      specs.push({ url, site, price: result.price, currency: result.currency });
      if (!derivedName && doc) derivedName = nameFromDom(doc);
    } else {
      specs.push({ url, site, error: error || "could not extract a price" });
    }
  }

  await addProduct(derivedName || "Product", specs);
  nameInput.value = "";
  urlFieldsEl.replaceChildren();
  addUrlField();
  addUrlField();
  addUrlField();
  const got = specs.filter((s) => s.price != null).length;
  addStatus.textContent = `Added. Got a price from ${got} of ${specs.length} URL(s).`;
  await renderProducts();
});

// --- products ---------------------------------------------------------------

const productsEl = document.getElementById("products");

function priceChart(points) {
  const canvas = el("canvas", { class: "chart", height: "120" });
  // Draw after it is laid out so clientWidth is known.
  requestAnimationFrame(() => {
    const width = canvas.clientWidth || 600;
    const dpr = window.devicePixelRatio || 1;
    canvas.width = width * dpr;
    canvas.height = 120 * dpr;
    const ctx = canvas.getContext("2d");
    ctx.scale(dpr, dpr);
    const prices = points.map((p) => p.price);
    const min = Math.min(...prices);
    const max = Math.max(...prices);
    const range = max - min || 1;
    const h = 120;
    const stepX = width / Math.max(points.length - 1, 1);
    const accent = getComputedStyle(document.body).getPropertyValue("--accent") || "#1b7f4b";
    ctx.strokeStyle = accent.trim();
    ctx.fillStyle = accent.trim();
    ctx.lineWidth = 2;
    ctx.beginPath();
    points.forEach((p, i) => {
      const x = i * stepX;
      const y = h - 12 - ((p.price - min) / range) * (h - 24);
      if (i === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    });
    ctx.stroke();
    points.forEach((p, i) => {
      const x = i * stepX;
      const y = h - 12 - ((p.price - min) / range) * (h - 24);
      ctx.beginPath();
      ctx.arc(x, y, 3, 0, Math.PI * 2);
      ctx.fill();
    });
  });
  return canvas;
}

function urlBlock(product, url) {
  const points = url.prices;
  const latest = latestPrice(url);
  const header = el(
    "div",
    { class: "url-head" },
    el("span", { class: "site" }, url.site),
    el("span", { class: "price" }, latest ? euro(latest.price) : "—"),
  );
  const meta = el("div", { class: "url-meta muted" });
  if (url.lastCheckedAt) meta.append("Checked " + fmtTime(url.lastCheckedAt));
  const block = el("div", { class: "url-block" }, header, meta);
  if (url.lastError) {
    block.append(el("div", { class: "err" }, "Last check failed: " + url.lastError));
  }
  if (points.length >= 2) block.append(priceChart(points));
  else block.append(el("div", { class: "muted small" }, "Price history appears once a price changes."));
  return block;
}

function alertsBlock(product) {
  const wrap = el("div", { class: "alerts" }, el("h4", {}, "Alerts"));
  if (!product.alerts.length) {
    wrap.append(el("p", { class: "muted small" }, "No alerts yet. Set a target price below."));
  }
  for (const alert of product.alerts) {
    wrap.append(
      el(
        "div",
        { class: "alert-row" },
        el(
          "span",
          {},
          "Below " + euro(alert.targetPrice) + (alert.triggered ? "  ✓ reached" : ""),
        ),
        el(
          "span",
          { class: "alert-actions" },
          el("label", { class: "switch" },
            el("input", {
              type: "checkbox",
              ...(alert.active ? { checked: "checked" } : {}),
              onchange: async () => {
                await toggleAlert(product.id, alert.id);
                await renderProducts();
              },
            }),
            el("span", { class: "slider" }),
          ),
          el("button", {
            class: "btn ghost tiny",
            onclick: async () => {
              await deleteAlert(product.id, alert.id);
              await renderProducts();
            },
          }, "Delete"),
        ),
      ),
    );
  }
  const input = el("input", { type: "number", step: "0.01", min: "0", class: "field-input", placeholder: "Target €" });
  wrap.append(
    el(
      "div",
      { class: "row" },
      input,
      el("button", {
        class: "btn",
        onclick: async () => {
          const target = parseFloat(String(input.value).replace(",", "."));
          if (!target || target <= 0) {
            flash("Enter a valid target price.");
            return;
          }
          await addAlert(product.id, target);
          // Fire immediately if the current price already meets the target.
          await chrome.runtime.sendMessage({ type: "evaluateProduct", productId: product.id });
          input.value = "";
          await renderProducts();
        },
      }, "Set alert"),
    ),
  );
  return wrap;
}

function productCard(product) {
  const card = el(
    "div",
    { class: "card product" },
    el(
      "div",
      { class: "product-head" },
      el("h3", {}, product.name),
      el(
        "span",
        { class: "product-actions" },
        el("button", {
          class: "btn ghost tiny",
          onclick: async () => {
            flash("Checking " + product.name + "…");
            const res = await chrome.runtime.sendMessage({ type: "checkProduct", productId: product.id });
            flash(res && res.ok ? "Checked." : "Check failed.");
            await renderProducts();
          },
        }, "Refresh"),
        el("button", {
          class: "btn ghost tiny danger",
          onclick: async () => {
            await deleteProduct(product.id);
            await renderProducts();
          },
        }, "Delete"),
      ),
    ),
  );
  for (const url of product.urls) card.append(urlBlock(product, url));
  card.append(alertsBlock(product));
  return card;
}

async function renderProducts() {
  const products = await listProducts();
  productsEl.replaceChildren();
  if (!products.length) {
    productsEl.append(
      el("div", { class: "card muted" }, "No products tracked yet. Add one above, or use the popup’s “Track this page” on a shop."),
    );
  } else {
    for (const product of products) productsEl.append(productCard(product));
  }
  await renderLog();
}

// --- interval + log ---------------------------------------------------------

const intervalOptionsEl = document.getElementById("intervalOptions");
async function renderIntervals() {
  const { intervalMinutes } = await getSettings();
  intervalOptionsEl.replaceChildren();
  for (const option of INTERVAL_OPTIONS) {
    const id = "iv-" + option.minutes;
    const radio = el("input", {
      type: "radio",
      name: "interval",
      id,
      ...(option.minutes === intervalMinutes ? { checked: "checked" } : {}),
      onchange: async () => {
        await setSettings({ intervalMinutes: option.minutes });
        flash("Check frequency updated.");
      },
    });
    intervalOptionsEl.append(
      el("label", { class: "option", for: id }, radio, el("span", {}, option.label)),
    );
  }
}

const logEl = document.getElementById("log");
async function renderLog() {
  const log = await listLog();
  logEl.replaceChildren();
  if (!log.length) {
    logEl.append(el("p", { class: "muted small" }, "No price changes or alerts yet."));
    return;
  }
  for (const entry of log.slice(0, 30)) {
    logEl.append(
      el(
        "div",
        { class: "log-row" },
        el("div", { class: "log-title" }, entry.title),
        el("div", { class: "muted small" }, entry.body),
        el("div", { class: "muted tiny" }, fmtTime(entry.at)),
      ),
    );
  }
}

document.getElementById("checkAll").addEventListener("click", async () => {
  flash("Checking all prices…");
  const res = await chrome.runtime.sendMessage({ type: "checkNow" });
  flash(res && res.ok ? `Done — ${res.count} update(s).` : "Check failed.");
  await renderProducts();
});

renderProducts();
renderIntervals();
