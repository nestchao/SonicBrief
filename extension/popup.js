import { detectVideoPage } from "./detect.js";
import {
  SUMMARY_LANGUAGES, getHealth, getSettings, openUi, recentJobs, saveSettings,
} from "./lib.js";

const $ = (id) => document.getElementById(id);
let health = null;
let activeTab = null;
let detected = null;

const STATUS_LABELS = { queued: "Queued", processing: "Working", completed: "Done", failed: "Failed", cancelled: "Cancelled" };

function setResult(text, kind = "") {
  $("result").textContent = text;
  $("result").className = `result ${kind}`;
}

/** The video to transcribe: the detected one, or a link the user pasted. */
function currentUrl() {
  return $("manualUrl").value.trim() || detected?.url || "";
}

function updatePrimary() {
  const valid = Boolean(detectVideoPage(currentUrl()));
  const summarize = Boolean($("summary").checked && health?.gemini_configured);
  $("submit").disabled = !valid;
  $("submit").textContent = summarize ? "Transcribe + summarize" : "Transcribe";
}

function renderDetection() {
  detected = detectVideoPage(activeTab?.url ?? "");
  $("detected").hidden = !detected;
  $("notDetected").hidden = Boolean(detected);
  if (detected) {
    $("platform").textContent = detected.platformLabel;
    $("pageTitle").textContent = activeTab?.title?.trim() || detected.url;
    $("pageUrl").textContent = detected.url;
  }
  updatePrimary();
}

function renderStatus() {
  const pill = $("status");
  $("offline").hidden = Boolean(health);
  if (health) {
    pill.textContent = health.cuda_available ? "Ready · GPU" : "Ready · CPU";
    pill.className = "pill ok";
  } else {
    pill.textContent = "Offline";
    pill.className = "pill bad";
  }
  const gemini = Boolean(health?.gemini_configured);
  $("geminiHint").hidden = !health || gemini;
  // Unknown while offline: leave the control alone.
  $("summary").disabled = Boolean(health) && !gemini;
  $("summaryStyle").disabled = Boolean(health) && !gemini;
  updatePrimary();
}

async function renderJobs() {
  if (!health) {
    $("recent").hidden = true;
    return;
  }
  let jobs = [];
  try {
    jobs = await recentJobs(5);
  } catch {
    return;
  }
  const list = $("jobs");
  list.replaceChildren(...jobs.map((job) => {
    const item = document.createElement("li");
    const title = document.createElement("span");
    title.className = "title";
    title.textContent = job.title || job.source_url || "Untitled";
    title.title = title.textContent;
    const state = document.createElement("span");
    state.className = `state ${job.status}`;
    state.textContent = job.status === "processing"
      ? `${Math.round(job.progress || 0)}%`
      : (STATUS_LABELS[job.status] ?? job.status);
    item.append(title, state);
    item.addEventListener("click", () => openUi());
    return item;
  }));
  $("recent").hidden = jobs.length === 0;
}

async function refresh() {
  health = await getHealth();
  renderStatus();
  await renderJobs();
}

function readSettings() {
  return {
    model: $("model").value,
    language: $("language").value,
    summary: $("summary").checked,
    summaryLanguage: $("summaryLanguage").value,
    summaryStyle: $("summaryStyle").value,
  };
}

async function persistSettings() {
  await saveSettings(readSettings());
  updatePrimary();
}

async function init() {
  for (const [code, label] of Object.entries(SUMMARY_LANGUAGES)) {
    const option = document.createElement("option");
    option.value = code;
    option.textContent = label;
    $("summaryLanguage").append(option);
  }

  const settings = await getSettings();
  $("model").value = settings.model;
  $("language").value = settings.language;
  $("summary").checked = settings.summary;
  $("summaryLanguage").value = settings.summaryLanguage ?? "zh-CN";
  $("summaryStyle").value = settings.summaryStyle;

  for (const id of ["model", "language", "summary", "summaryLanguage", "summaryStyle"]) {
    $(id).addEventListener("change", persistSettings);
  }
  $("manualUrl").addEventListener("input", updatePrimary);

  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  activeTab = tab ?? null;
  renderDetection();

  $("form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const found = detectVideoPage(currentUrl());
    if (!found) return;
    $("submit").disabled = true;
    setResult(health ? "Sending…" : "Starting SonicBrief…");
    const response = await chrome.runtime.sendMessage({ type: "transcribe", url: found.url });
    if (response?.ok && response.pending) {
      setResult("SonicBrief is starting. Your link will be sent when it's ready.", "ok");
    } else if (response?.ok) {
      setResult("Queued. You'll get a notification when it's done.", "ok");
    } else if (response?.error === "unsupported") {
      setResult("That link isn't supported.", "bad");
    } else {
      setResult(response?.error || "Something went wrong.", "bad");
    }
    $("submit").disabled = false;
    updatePrimary();
    await refresh();
  });

  $("launch").addEventListener("click", async () => {
    await chrome.runtime.sendMessage({ type: "launch" });
    $("status").textContent = "Starting…";
  });
  $("openUi").addEventListener("click", () => openUi());

  await refresh();
  if (!$("submit").disabled) $("submit").focus();
  setInterval(refresh, 3000);
}

init();
