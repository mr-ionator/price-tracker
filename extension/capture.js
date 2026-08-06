// Passive capture: whenever you open a shop product page, read its price from
// the fully rendered DOM and hand it to the service worker, which updates any
// matching tracked product. No background fetch, no bot walls — it's your own
// real browsing. Prices for pages you don't visit are refreshed by the
// scheduled background-tab checks instead.
(function () {
  function attempt() {
    try {
      const result = globalThis.ptExtractPrice && globalThis.ptExtractPrice();
      if (result && result.price) {
        chrome.runtime.sendMessage({
          type: "capturedPrice",
          url: location.href,
          price: result.price,
          currency: result.currency,
          name: result.name,
        });
        return true;
      }
    } catch (_) {
      /* extension context may be reloading; ignore */
    }
    return false;
  }

  // Retry a few times to catch prices that render slightly after load.
  let tries = 0;
  const timer = setInterval(() => {
    tries += 1;
    if (attempt() || tries >= 5) clearInterval(timer);
  }, 1500);
})();
