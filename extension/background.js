import { detectVideoPage } from "./detect.js";
import {
  LAUNCH_URL, URL_PATTERNS, addPending, getHealth, getJob, getSettings,
  notify, openUi, submitJob,
} from "./lib.js";

const TERMINAL = new Set(["completed", "failed", "cancelled"]);

function setup() {
  chrome.contextMenus.removeAll(() => {
    chrome.contextMenus.create({
      id: "sonicbrief-page", title: "Transcribe this video with SonicBrief",
      contexts: ["page", "video", "audio"], documentUrlPatterns: URL_PATTERNS,
    });
    chrome.contextMenus.create({
      id: "sonicbrief-link", title: "Transcribe linked video with SonicBrief",
      contexts: ["link"], targetUrlPatterns: URL_PATTERNS,
    });
  });
  chrome.alarms.create("poll", { periodInMinutes: 0.5 });
}

chrome.runtime.onInstalled.addListener(setup);
chrome.runtime.onStartup.addListener(setup);

/** Send a link to SonicBrief, starting the desktop app first if needed. */
async function transcribe(rawUrl) {
  const found = detectVideoPage(rawUrl);
  if (!found) {
    notify("", "Not a supported link", "Open a supported video page (YouTube, Bilibili, TikTok, Instagram, Facebook and more).");
    return { ok: false, error: "unsupported" };
  }
  const url = found.url;
  const health = await getHealth();
  if (!health) {
    await addPending(url);
    await launchDesktopApp();
    notify("", "Starting SonicBrief…", "Your link will be sent as soon as the app is ready.");
    waitAndFlush();
    return { ok: true, pending: true };
  }
  try {
    const job = await submitJob(url, await getSettings(), health);
    notify("", "Transcription queued", job.title || url);
    return { ok: true, job };
  } catch (error) {
    notify("", "Could not add that link", String(error.message || error));
    return { ok: false, error: String(error.message || error) };
  }
}

async function launchDesktopApp() {
  // Opening the sonicbrief-desktop:// URL in a throwaway background tab asks
  // Windows to start the app. Never navigate the tab the user is watching.
  let tab;
  try {
    tab = await chrome.tabs.create({ url: LAUNCH_URL, active: false });
  } catch (error) {
    console.warn("SonicBrief launch failed", error);
    return;
  }
  setTimeout(() => {
    if (tab?.id != null) chrome.tabs.remove(tab.id).catch(() => {});
  }, 2000);
}

let flushing = false;
async function waitAndFlush() {
  if (flushing) return;
  flushing = true;
  try {
    const deadline = Date.now() + 4 * 60 * 1000;
    while (Date.now() < deadline) {
      const { pending = [] } = await chrome.storage.local.get("pending");
      if (!pending.length) return;
      if (await flushPending()) return;
      await new Promise((resolve) => setTimeout(resolve, 2000));
    }
  } finally {
    flushing = false;
  }
}

/** Submits links queued while SonicBrief was offline. Returns true when done. */
async function flushPending() {
  const { pending = [] } = await chrome.storage.local.get("pending");
  if (!pending.length) return true;
  const health = await getHealth();
  if (!health) return false;
  await chrome.storage.local.set({ pending: [] });
  const settings = await getSettings();
  for (const url of pending) {
    try {
      const job = await submitJob(url, settings, health);
      notify("", "Transcription queued", job.title || url);
    } catch (error) {
      notify("", "Could not add that link", String(error.message || error));
    }
  }
  return true;
}

async function checkTrackedJobs() {
  const { tracked = {} } = await chrome.storage.local.get("tracked");
  const ids = Object.keys(tracked);
  if (!ids.length || !(await getHealth())) return;
  let changed = false;
  for (const id of ids) {
    let job;
    try {
      job = await getJob(id);
    } catch {
      delete tracked[id]; // Deleted in the app.
      changed = true;
      continue;
    }
    if (TERMINAL.has(job.status)) {
      if (job.status === "completed") notify(`job:${id}`, "Transcript ready", job.title || "Your media");
      if (job.status === "failed") notify(`job:${id}`, "Transcription failed", `${job.title || ""}\n${job.error || ""}`.trim());
      delete tracked[id];
      changed = true;
    }
  }
  if (changed) await chrome.storage.local.set({ tracked });
}

chrome.alarms.onAlarm.addListener(async (alarm) => {
  if (alarm.name !== "poll") return;
  await flushPending();
  await checkTrackedJobs();
});

chrome.contextMenus.onClicked.addListener((info, tab) => {
  const url = info.menuItemId === "sonicbrief-link" ? info.linkUrl : info.pageUrl;
  if (url) transcribe(url);
});

chrome.commands.onCommand.addListener((command, tab) => {
  if (command === "transcribe-current-tab" && tab?.url) transcribe(tab.url);
});

chrome.notifications.onClicked.addListener((id) => {
  chrome.notifications.clear(id);
  openUi();
});

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (message?.type === "transcribe") {
    transcribe(message.url).then(sendResponse);
    return true; // Keep the channel open for the async response.
  }
  if (message?.type === "launch") {
    launchDesktopApp().then(() => sendResponse({ ok: true }));
    return true;
  }
  return false;
});
