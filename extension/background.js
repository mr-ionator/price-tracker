// Service worker: schedules periodic price checks, handles requests from the
// popup/dashboard, and raises desktop notifications for price changes/alerts.

import { checkAllProducts, checkProduct, evaluateProduct } from "./lib/checker.js";
import { getSettings } from "./lib/store.js";

const ALARM = "priceCheck";

async function scheduleAlarm() {
  const { intervalMinutes } = await getSettings();
  await chrome.alarms.create(ALARM, { periodInMinutes: Math.max(intervalMinutes, 15) });
}

function raiseNotifications(events) {
  for (const event of events) {
    chrome.notifications.create("pt-" + event.id, {
      type: "basic",
      iconUrl: "icons/icon128.png",
      title: event.title,
      message: event.body,
      priority: event.kind === "alert" ? 2 : 0,
    });
  }
}

chrome.runtime.onInstalled.addListener(scheduleAlarm);
chrome.runtime.onStartup.addListener(scheduleAlarm);

chrome.alarms.onAlarm.addListener(async (alarm) => {
  if (alarm.name !== ALARM) return;
  try {
    raiseNotifications(await checkAllProducts());
  } catch (error) {
    console.error("scheduled price check failed", error);
  }
});

// Re-schedule whenever the user changes the check interval.
chrome.storage.onChanged.addListener((changes, area) => {
  if (area === "local" && changes.settings) scheduleAlarm();
});

// Requests from the popup and dashboard.
chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  (async () => {
    try {
      if (message.type === "checkNow") {
        const events = await checkAllProducts();
        raiseNotifications(events);
        sendResponse({ ok: true, count: events.length });
      } else if (message.type === "checkProduct") {
        const events = await checkProduct(message.productId);
        raiseNotifications(events);
        sendResponse({ ok: true, count: events.length });
      } else if (message.type === "evaluateProduct") {
        const events = await evaluateProduct(message.productId);
        raiseNotifications(events);
        sendResponse({ ok: true, count: events.length });
      } else {
        sendResponse({ ok: false, error: "unknown message" });
      }
    } catch (error) {
      sendResponse({ ok: false, error: String(error.message || error) });
    }
  })();
  return true; // keep the message channel open for the async response
});

// Tapping a notification opens the dashboard.
chrome.notifications.onClicked.addListener(() => {
  chrome.runtime.openOptionsPage();
});
