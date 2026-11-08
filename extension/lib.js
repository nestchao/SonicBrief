// Shared helpers for the popup and the background service worker.
// The extension only talks to the SonicBrief backend on this computer.

import { detectVideoPage } from "./detect.js";

export const API_BASE = "http://127.0.0.1:7860";
export const UI_URL = "http://127.0.0.1:3000/";
export const LAUNCH_URL = "sonicbrief-desktop://open";

// Mirrors ALLOWED_URL_SOURCES in backend/config.py.
export const SUPPORTED_DOMAINS = [
  "youtube.com", "youtu.be", "bilibili.com", "b23.tv", "vimeo.com",
  "tiktok.com", "x.com", "twitter.com", "soundcloud.com", "twitch.tv",
  "douyin.com", "instagram.com", "facebook.com", "fb.watch",
];
export const URL_PATTERNS = SUPPORTED_DOMAINS.flatMap((d) => [`*://${d}/*`, `*://*.${d}/*`]);

// The backend writes this name straight into the Gemini prompt ("Write the
// summary in <name>"), so these are display names, not locale codes.
export const SUMMARY_LANGUAGES = {
  "zh-CN": "Simplified Chinese",
  en: "English",
  ms: "Malay",
  ja: "Japanese",
  ko: "Korean",
  id: "Indonesian",
};

export const DEFAULT_SETTINGS = {
  model: "turbo",
  language: "auto",
  summary: true,
  summaryLanguage: "zh-CN",
  summaryStyle: "standard",
};

/** True only for a specific video page, not merely a supported host. */
export function isSupported(url) {
  return Boolean(detectVideoPage(url));
}

export async function getSettings() {
  const stored = await chrome.storage.sync.get("settings");
  return { ...DEFAULT_SETTINGS, ...(stored.settings ?? {}) };
}

export async function saveSettings(settings) {
  await chrome.storage.sync.set({ settings });
}

async function request(path, init = {}, timeoutMs = 5000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(API_BASE + path, { ...init, signal: controller.signal });
    const body = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(body.detail || `SonicBrief returned ${response.status}`);
    return body;
  } finally {
    clearTimeout(timer);
  }
}

/** Returns the backend health object, or null when SonicBrief is not running. */
export async function getHealth() {
  try {
    const health = await request("/api/health", {}, 2500);
    return health?.ok ? health : null;
  } catch {
    return null;
  }
}

export async function submitJob(url, settings, health) {
  const useGemini = Boolean(health?.gemini_configured);
  const body = new URLSearchParams({
    url,
    model_name: settings.model,
    language: settings.language,
    generate_summary: String(useGemini && settings.summary),
    allow_gemini_fallback: String(useGemini),
    summary_language: SUMMARY_LANGUAGES[settings.summaryLanguage] ?? SUMMARY_LANGUAGES["zh-CN"],
    summary_style: settings.summaryStyle,
  });
  const job = await request("/api/jobs/url", { method: "POST", body }, 15000);
  await trackJob(job);
  return job;
}

export async function getJob(id) {
  return request(`/api/jobs/${encodeURIComponent(id)}`);
}

export async function recentJobs(limit = 5) {
  return request(`/api/jobs?limit=${limit}`);
}

// ----- jobs this extension is waiting on (for completion notifications) -----
export async function trackJob(job) {
  const { tracked = {} } = await chrome.storage.local.get("tracked");
  tracked[job.id] = { title: job.title, status: job.status };
  await chrome.storage.local.set({ tracked });
}

// ----- links queued while SonicBrief was not running -----
export async function addPending(url) {
  const { pending = [] } = await chrome.storage.local.get("pending");
  if (!pending.includes(url)) pending.push(url);
  await chrome.storage.local.set({ pending });
}

/**
 * The native desktop app serves the UI from the API port; the Vite dev server
 * (Python launcher) is only a fallback. Probe `/` and use whichever answers.
 */
export async function resolveUiUrl() {
  try {
    const response = await fetch(API_BASE + "/", { signal: AbortSignal.timeout(1500) });
    const html = await response.text();
    if (response.ok && html.includes('id="root"')) return API_BASE + "/";
  } catch {
    // Fall through to the dev server.
  }
  return UI_URL;
}

export async function openUi() {
  const url = await resolveUiUrl();
  const tabs = await chrome.tabs.query({ url: url + "*" });
  if (tabs.length) {
    await chrome.tabs.update(tabs[0].id, { active: true });
    await chrome.windows.update(tabs[0].windowId, { focused: true });
  } else {
    await chrome.tabs.create({ url });
  }
}

export function notify(id, title, message) {
  chrome.notifications.create(id, {
    type: "basic", iconUrl: "icons/icon128.png", title, message: message || " ", priority: 1,
  });
}
