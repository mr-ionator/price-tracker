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
    empty.textContent =
      "No products yet. Open a product page on a supported shop and tap “Track this page”.";
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
    // Inject the shared extractor, then read the price from the rendered page.
    await chrome.scripting.executeScript({ target: { tabId: tab.id }, files: ["extract-core.js"] });
    const injections = await chrome.scripting.executeScript({
      target: { tabId: tab.id },
      func: (s) => globalThis.ptExtractPrice(s),
      args: [site],
    });
    result = injections && injections[0] && injections[0].result;
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
