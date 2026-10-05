"use client";

import { type FormEvent, type ReactNode, useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  AudioLines, Check, CirclePlay, FileAudio, FileText, FileVideo, MoreVertical,
  Languages, LoaderCircle, Mic2, MonitorDot, RefreshCw,
  Copy, History, Pencil, RotateCcw, Search, Sparkles, Trash2, UploadCloud, Video, X,
} from "lucide-react";

import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Progress } from "@/components/ui/progress";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Textarea } from "@/components/ui/textarea";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { GeminiSettings } from "@/app/gemini-settings";

type InputSource = "url" | "upload";
type SummaryStyle = "brief" | "standard" | "detailed" | "study_notes" | "key_points" | "custom";
type JobSource = "youtube" | "bilibili" | "vimeo" | "tiktok" | "twitter" | "soundcloud" | "twitch" | "upload" | "audio_upload" | "video_upload";
type JobStatus = "queued" | "processing" | "completed" | "failed" | "cancelled";
type TranscriptSegment = { start: number; end: number; text: string; speaker?: string | null };
type Job = {
  id: string; title: string; source_type: JobSource; source_url?: string | null; creator_name?: string | null;
  status: JobStatus; progress: number; stage: string; created_at: string; updated_at: string;
  duration?: number | null; language?: string | null; engine?: string | null;
  error?: string | null; warnings?: string[]; transcript?: TranscriptSegment[]; summary?: string | null;
  stage_detail?: string | null; stage_progress?: number; processed_duration?: number | null; model_name?: string | null; diarization_enabled?: boolean;
};
type Health = {
  ok: boolean; cuda_available: boolean; device: string;
  gemini_configured: boolean; diarization_configured: boolean;
};

const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://127.0.0.1:7860";
const stages = [
  { key: "acquire", label: "Get audio", icon: FileAudio },
  { key: "transcribe", label: "Local Whisper", icon: AudioLines },
  { key: "diarize", label: "Identify speakers", icon: Mic2 },
  { key: "summarize", label: "Create summary", icon: Sparkles },
];

const SUMMARY_STYLE_OPTIONS: { value: SummaryStyle; label: string; description: string }[] = [
  { value: "brief", label: "Brief", description: "Quick overview with only the most important points." },
  { value: "standard", label: "Standard", description: "Balanced overview, main ideas, important details, and conclusion." },
  { value: "detailed", label: "Detailed", description: "Thorough explanation with context, examples, details, and timestamps." },
  { value: "study_notes", label: "Study Notes", description: "Concepts, definitions, examples, facts, and review-ready notes." },
  { value: "key_points", label: "Key Points", description: "Takeaways, decisions, recommendations, and action items." },
  { value: "custom", label: "Custom", description: "Use your own summary instructions." },
];

function summaryStyleLabel(style: SummaryStyle) {
  return SUMMARY_STYLE_OPTIONS.find((item) => item.value === style)?.label ?? "Standard";
}

function summaryStyleDescription(style: SummaryStyle) {
  return SUMMARY_STYLE_OPTIONS.find((item) => item.value === style)?.description ?? "";
}

function formatTime(seconds = 0) {
  const total = Math.max(0, Math.floor(seconds));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  return h > 0 ? `${h}:${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}` : `${m}:${String(s).padStart(2, "0")}`;
}

function formatDate(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  return new Intl.DateTimeFormat("en", { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" }).format(date);
}

function SourceIcon({ source }: { source: JobSource }) {
  if (source === "youtube") return <CirclePlay aria-hidden="true" />;
  if (["bilibili", "vimeo", "tiktok", "twitter", "twitch"].includes(source)) return <Video aria-hidden="true" />;
  if (source === "video_upload") return <FileVideo aria-hidden="true" />;
  return <FileAudio aria-hidden="true" />;
}

function sourceLabel(source: JobSource) {
  const labels: Partial<Record<JobSource, string>> = {
    youtube: "YouTube",
    bilibili: "Bilibili",
    vimeo: "Vimeo",
    tiktok: "TikTok",
    twitter: "X / Twitter",
    soundcloud: "SoundCloud",
    twitch: "Twitch",
    video_upload: "Video upload",
    audio_upload: "Audio upload",
    upload: "Media upload",
  };
  return labels[source] ?? "Online media";
}

function renderInlineMarkdown(text: string): ReactNode[] {
  const tokens = text.split(/(\*\*[^*]+\*\*|__[^_]+__|`[^`]+`|\*[^*]+\*|_[^_]+_|\[\d+(?:\.\d+)?s\])/g);
  return tokens.map((token, index) => {
    if (/^\*\*[^*]+\*\*$/.test(token) || /^__[^_]+__$/.test(token)) return <strong key={token + "-" + index}>{token.slice(2, -2)}</strong>;
    if (/^`[^`]+`$/.test(token)) return <code key={token + "-" + index}>{token.slice(1, -1)}</code>;
    if (/^\*[^*]+\*$/.test(token) || /^_[^_]+_$/.test(token)) return <em key={token + "-" + index}>{token.slice(1, -1)}</em>;
    if (/^\[\d+(?:\.\d+)?s\]$/.test(token)) return <span className="sonic-timestamp-chip" key={token + "-" + index}>{token.slice(1, -1)}</span>;
    return token;
  });
}

function joinMarkdownLines(lines: string[]) {
  return lines.join(" ").replace(/([\u3400-\u9fff])\s+([\u3400-\u9fff])/g, "$1$2");
}

function isMarkdownBlockStart(line: string) {
  return /^\s*(#{1,6})\s+/.test(line)
    || /^\s*(?:[-*_]\s*){3,}$/.test(line)
    || /^\s*[-*+]\s+/.test(line)
    || /^\s*\d+[.)]\s+/.test(line)
    || /^\s*>\s?/.test(line);
}

function SummaryMarkdown({ content }: { content: string }) {
  const lines = content.replace(/\r\n?/g, "\n").trim().split("\n");
  const blocks: ReactNode[] = [];
  let index = 0;

  while (index < lines.length) {
    const line = lines[index].trim();
    if (!line) { index += 1; continue; }

    const heading = line.match(/^(#{1,6})\s+(.+)$/);
    if (heading) {
      const level = Math.min(heading[1].length, 4);
      const Heading = level <= 2 ? "h3" : "h4";
      blocks.push(<Heading className={"sonic-summary-heading is-level-" + level} key={"heading-" + index}>{renderInlineMarkdown(heading[2])}</Heading>);
      index += 1;
      continue;
    }

    if (/^(?:[-*_]\s*){3,}$/.test(line)) {
      blocks.push(<hr key={"rule-" + index} />);
      index += 1;
      continue;
    }

    const unordered = line.match(/^[-*+]\s+(.+)$/);
    const ordered = line.match(/^\d+[.)]\s+(.+)$/);
    if (unordered || ordered) {
      const items: string[] = [];
      const orderedList = Boolean(ordered);
      while (index < lines.length) {
        const current = lines[index].trim();
        const match = orderedList ? current.match(/^\d+[.)]\s+(.+)$/) : current.match(/^[-*+]\s+(.+)$/);
        if (!match) break;
        items.push(match[1]);
        index += 1;
      }
      const List = orderedList ? "ol" : "ul";
      blocks.push(<List key={"list-" + index}>{items.map((item, itemIndex) => <li key={item + "-" + itemIndex}>{renderInlineMarkdown(item)}</li>)}</List>);
      continue;
    }

    if (/^>\s?/.test(line)) {
      const quoteLines: string[] = [];
      while (index < lines.length && /^>\s?/.test(lines[index].trim())) {
        quoteLines.push(lines[index].trim().replace(/^>\s?/, ""));
        index += 1;
      }
      blocks.push(<blockquote key={"quote-" + index}>{renderInlineMarkdown(joinMarkdownLines(quoteLines))}</blockquote>);
      continue;
    }

    const paragraphLines = [line];
    index += 1;
    while (index < lines.length && lines[index].trim() && !isMarkdownBlockStart(lines[index].trim())) {
      paragraphLines.push(lines[index].trim());
      index += 1;
    }
    blocks.push(<p key={"paragraph-" + index}>{renderInlineMarkdown(joinMarkdownLines(paragraphLines))}</p>);
  }

  return <div className="sonic-summary-content">{blocks}</div>;
}

export function SonicBriefApp() {
  const [source, setSource] = useState<InputSource>("url");
  const [url, setUrl] = useState(""); const [files, setFiles] = useState<File[]>([]); const [language, setLanguage] = useState("auto"); const [model, setModel] = useState("turbo"); const [diarize, setDiarize] = useState(false); const [diarizeTouched, setDiarizeTouched] = useState(false);
  const [jobs, setJobs] = useState<Job[]>([]); const [activeJob, setActiveJob] = useState<Job | null>(null); const [health, setHealth] = useState<Health | null>(null); const [loading, setLoading] = useState(false); const [message, setMessage] = useState<string | null>(null); const [messageType, setMessageType] = useState<"error" | "success">("error"); const [transcriptCopied, setTranscriptCopied] = useState(false); const [dragging, setDragging] = useState(false);
  const [newTaskOpen, setNewTaskOpen] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [historyQuery, setHistoryQuery] = useState("");
  const [creatorFilter, setCreatorFilter] = useState("");
  const [transcriptQuery, setTranscriptQuery] = useState("");
  const [resultTab, setResultTab] = useState<"summary" | "transcript" | "details">("summary");
  const [showPipelineDetails, setShowPipelineDetails] = useState(false);
  const [summaryStyle, setSummaryStyle] = useState<SummaryStyle>("standard");
  const [summaryCustomInstructions, setSummaryCustomInstructions] = useState("");
  const fileInput = useRef<HTMLInputElement>(null);
  const hasPendingJobs = jobs.some((job) => ["queued", "processing"].includes(job.status));
  const loadHealth = useCallback(async () => { try { const response = await fetch(`${API_BASE}/api/health`); if (!response.ok) throw new Error("Backend unavailable"); setHealth(await response.json()); } catch { setHealth(null); } }, []);
  const loadJobs = useCallback(async () => { try { const response = await fetch(`${API_BASE}/api/jobs?limit=200`); if (!response.ok) return; const data = (await response.json()) as Job[]; setJobs(data); setActiveJob((current) => current ?? data[0] ?? null); } catch {} }, []);
  const refreshJob = useCallback(async (jobId: string) => { try { const response = await fetch(`${API_BASE}/api/jobs/${jobId}`); if (!response.ok) return null; const job = (await response.json()) as Job; setActiveJob(job); setJobs((current) => [job, ...current.filter((item) => item.id !== job.id)].sort((a, b) => b.updated_at.localeCompare(a.updated_at))); return job; } catch { return null; } }, []);
  useEffect(() => { void loadHealth(); void loadJobs(); }, [loadHealth, loadJobs]);
  useEffect(() => { if (health) return; const timer = window.setInterval(() => void loadHealth(), 3000); return () => window.clearInterval(timer); }, [health, loadHealth]);
  useEffect(() => { if (activeJob?.status === "completed" && activeJob.transcript === undefined) void refreshJob(activeJob.id); }, [activeJob?.id, activeJob?.status, activeJob?.transcript, refreshJob]);
  useEffect(() => { if (!activeJob || !["queued", "processing"].includes(activeJob.status)) return; const timer = window.setInterval(async () => { const job = await refreshJob(activeJob.id); if (job && ["completed", "failed", "cancelled"].includes(job.status)) window.clearInterval(timer); }, 1200); return () => window.clearInterval(timer); }, [activeJob?.id, activeJob?.status, refreshJob]);
  useEffect(() => { if (!hasPendingJobs) return; const timer = window.setInterval(() => void loadJobs(), 2000); return () => window.clearInterval(timer); }, [hasPendingJobs, loadJobs]);
  useEffect(() => {
    if (!health || diarizeTouched) return;
    setDiarize(Boolean(health.diarization_configured));
  }, [health?.diarization_configured, diarizeTouched]);
  useEffect(() => { setResultTab("summary"); setTranscriptQuery(""); setShowPipelineDetails(false); }, [activeJob?.id]);
  useEffect(() => {
    const savedStyle = window.localStorage.getItem("sonicbrief-summary-style") as SummaryStyle | null;
    if (savedStyle && SUMMARY_STYLE_OPTIONS.some((item) => item.value === savedStyle)) setSummaryStyle(savedStyle);
  }, []);
  useEffect(() => {
    window.localStorage.setItem("sonicbrief-summary-style", summaryStyle);
  }, [summaryStyle]);

  const creators = useMemo(() => [...new Set(jobs.map((job) => job.creator_name?.trim()).filter((value): value is string => Boolean(value)))].sort((a, b) => a.localeCompare(b)), [jobs]);
  const filteredHistory = useMemo(() => {
    const query = historyQuery.trim().toLowerCase();
    return jobs.filter((job) => {
      const creator = job.creator_name?.trim() ?? "";
      const matchesCreator = !creatorFilter || creator === creatorFilter;
      const haystack = [job.title, creator, sourceLabel(job.source_type), formatDate(job.created_at)].join(" ").toLowerCase();
      return matchesCreator && (!query || haystack.includes(query));
    });
  }, [jobs, historyQuery, creatorFilter]);
  const filteredTranscript = useMemo(() => {
    const query = transcriptQuery.trim().toLowerCase();
    const segments = activeJob?.transcript ?? [];
    if (!query) return segments;
    return segments.filter((segment) => [segment.text, segment.speaker ?? "", formatTime(segment.start)].join(" ").toLowerCase().includes(query));
  }, [activeJob?.transcript, transcriptQuery]);
  const recentJobs = jobs.slice(0, 4);
  const visibleWarnings = (activeJob?.warnings ?? []).filter(
    (warning) => !warning.startsWith("Speaker identification was skipped: Speaker identification needs HF_TOKEN"),
  );

  const currentStageIndex = useMemo(() => { const index = stages.findIndex((item) => item.key === activeJob?.stage); return index < 0 ? 0 : index; }, [activeJob?.stage]);
  const timestampedTranscript = useMemo(() => (activeJob?.transcript ?? []).map((segment) => `[${formatTime(segment.start)}]${segment.speaker ? ` ${segment.speaker}:` : ""} ${segment.text}`).join("\n"), [activeJob?.transcript]);

  function chooseFiles(nextFiles?: FileList | File[] | null) {
    if (!nextFiles?.length) return; const combined = [...files, ...Array.from(nextFiles)]; const unique = combined.filter((item, index) => combined.findIndex((candidate) => candidate.name === item.name && candidate.size === item.size && candidate.lastModified === item.lastModified) === index); setFiles(unique.slice(0, 20)); setMessage(unique.length > 20 ? "A batch can contain at most 20 files; extra files were not added." : null);
  }

  function validateSummarySelection() {
    if (summaryStyle === "custom" && !summaryCustomInstructions.trim()) {
      setMessageType("error");
      setMessage("Custom summary style needs instructions.");
      return false;
    }
    return true;
  }

  async function submit(event: FormEvent) {
    event.preventDefault(); setMessageType("error"); setMessage(null);
    if (!validateSummarySelection()) return;
    if (source === "upload" && !files.length) { setMessage("Choose at least one audio or video file."); return; }
    if (source === "url" && !url.trim()) { setMessage("Paste at least one supported media link."); return; }
    const extractUrl = (value: string) => {
      const match = value.match(/https?:\/\/[^\s<>]+/i);
      return match ? match[0].replace(/[，。！？、）】》)\]}>"'”’]+$/u, "") : "";
    };
    const urls = source === "upload" ? [] : [...new Set(url.split(/\r?\n/).map(extractUrl).filter(Boolean))].slice(0, 20);
    if (source === "url" && !urls.length) { setMessage("Paste at least one valid media URL."); return; }
    if (source === "url" && url.split(/\r?\n/).map(extractUrl).filter(Boolean).length > 20) setMessage("A batch can contain at most 20 URLs; extra URLs were not added.");
    setLoading(true);
    try {
      const createRequest = async (file?: File, sourceUrl?: string) => {
        const body = new FormData(); body.set("model_name", model); body.set("language", language); body.set("diarize", String(diarize)); body.set("summary_language", "zh-CN"); body.set("summary_style", summaryStyle); body.set("summary_custom_instructions", summaryCustomInstructions.trim());
        let endpoint = `${API_BASE}/api/jobs/upload`; if (file) body.set("file", file); else { endpoint = `${API_BASE}/api/jobs/url`; body.set("url", sourceUrl ?? ""); }
        const response = await fetch(endpoint, { method: "POST", body }); const data = await response.json().catch(() => ({})); if (!response.ok) throw new Error(data.detail ?? "Unable to create this task."); return data as Job;
      };
      const created: Job[] = [];
      if (source === "upload") for (const file of files) created.push(await createRequest(file)); else for (const sourceUrl of urls) created.push(await createRequest(undefined, sourceUrl));
      if (!created.length) throw new Error("No tasks were created.");
      setActiveJob(created[0]); setJobs((current) => [...created, ...current.filter((job) => !created.some((item) => item.id === job.id))]); setMessageType("success"); setMessage(`${created.length} ${created.length === 1 ? "task" : "tasks"} added to the processing queue.`); setNewTaskOpen(false); setResultTab("summary");
      if (source === "upload") { setFiles([]); if (fileInput.current) fileInput.current.value = ""; } else setUrl("");
    } catch (error) { setMessage(error instanceof Error ? error.message : "Unable to create this task."); } finally { setLoading(false); }
  }

  async function cancelJob() {
    if (!activeJob || !["queued", "processing"].includes(activeJob.status)) return;
    if (!window.confirm("Cancel this processing task? The transcript already produced will be kept, but the final summary will not be saved.")) return;
    setLoading(true); setMessage(null);
    try { const response = await fetch(API_BASE + "/api/jobs/" + activeJob.id + "/cancel", { method: "POST" }); const data = await response.json(); if (!response.ok) throw new Error(data.detail ?? "Could not cancel this task."); setActiveJob(data as Job); setJobs((current) => current.map((job) => job.id === data.id ? data : job)); setMessageType("success"); setMessage("Processing cancelled. The transcript already produced was kept."); }
    catch (error) { setMessage(error instanceof Error ? error.message : "Could not cancel this task."); } finally { setLoading(false); }
  }

  async function regenerateSummary() {
    if (!activeJob || !validateSummarySelection()) return;
    setLoading(true); setMessage(null);
    try {
      const body = new FormData();
      body.set("summary_language", "zh-CN");
      body.set("summary_style", summaryStyle);
      body.set("summary_custom_instructions", summaryCustomInstructions.trim());
      const response = await fetch(`${API_BASE}/api/jobs/${activeJob.id}/summaries`, { method: "POST", body });
      const data = await response.json();
      if (!response.ok) throw new Error(data.detail ?? "Could not regenerate the summary.");
      setActiveJob(data as Job);
      setJobs((current) => current.map((job) => job.id === data.id ? data : job));
      setMessageType("success");
      setMessage(`Summary regenerated using ${summaryStyleLabel(summaryStyle)}.`);
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Could not regenerate the summary.");
    } finally {
      setLoading(false);
    }
  }

  async function renameJob(job: Job) {
    const nextTitle = window.prompt("Rename task", job.title)?.trim(); if (!nextTitle || nextTitle === job.title) return; setLoading(true); setMessageType("error"); setMessage(null);
    try { const body = new FormData(); body.set("title", nextTitle); const response = await fetch(`${API_BASE}/api/jobs/${job.id}/rename`, { method: "POST", body }); const data = await response.json(); if (!response.ok) throw new Error(data.detail ?? "Could not rename this task."); if (activeJob?.id === data.id) setActiveJob(data as Job); setJobs((current) => current.map((job) => job.id === data.id ? data : job)); setMessageType("success"); setMessage("Task renamed."); }
    catch (error) { setMessage(error instanceof Error ? error.message : "Could not rename this task."); } finally { setLoading(false); }
  }

  async function deleteJob(job: Job) {
    if (!window.confirm(`Delete “${job.title}”? This cannot be undone.`)) return; setLoading(true); setMessageType("error"); setMessage(null);
    try { const response = await fetch(`${API_BASE}/api/jobs/${job.id}`, { method: "DELETE" }); if (!response.ok) { const data = await response.json().catch(() => ({})); throw new Error(data.detail ?? "Could not delete this task."); } const remaining = jobs.filter((item) => item.id !== job.id); setJobs(remaining); if (activeJob?.id === job.id) setActiveJob(remaining[0] ?? null); setMessageType("success"); setMessage("Task deleted."); }
    catch (error) { setMessage(error instanceof Error ? error.message : "Could not delete this task."); } finally { setLoading(false); }
  }

  async function copyTranscript() {
    if (!timestampedTranscript) return;
    try { if (navigator.clipboard?.writeText) await navigator.clipboard.writeText(timestampedTranscript); else { const textarea = document.createElement("textarea"); textarea.value = timestampedTranscript; textarea.style.position = "fixed"; textarea.style.opacity = "0"; document.body.appendChild(textarea); textarea.select(); const copied = document.execCommand("copy"); textarea.remove(); if (!copied) throw new Error("Clipboard access was unavailable."); } setTranscriptCopied(true); window.setTimeout(() => setTranscriptCopied(false), 1800); }
    catch { setMessage("Could not copy the transcript. Check your browser clipboard permissions."); }
  }

  async function openJob(job: Job) {
    setHistoryOpen(false);
    setResultTab("summary");
    await refreshJob(job.id);
  }

  return (
    <main className="sonic-app-shell">
      <aside className="sonic-sidebar">
        <div className="sonic-sidebar-brand">
          <div className="sonic-brand-mark">S</div>
          <div><strong>SonicBrief</strong><span>Audio workspace</span></div>
        </div>

        <Button className="sonic-new-task-button" onClick={() => { setMessage(null); setNewTaskOpen(true); }}>
          <AudioLines />New transcription
        </Button>

        <section className="sonic-sidebar-section">
          <p className="sonic-sidebar-label">RECENT</p>
          <div className="sonic-recent-list">
            {recentJobs.map((job) => (
              <button
                key={job.id}
                type="button"
                className={"sonic-recent-job " + (activeJob?.id === job.id ? "is-active" : "")}
                onClick={() => void openJob(job)}
              >
                <span className="sonic-recent-icon"><SourceIcon source={job.source_type} /></span>
                <span className="sonic-recent-copy">
                  <strong>{job.title}</strong>
                  <small>{job.creator_name || sourceLabel(job.source_type)} · {job.status}</small>
                </span>
              </button>
            ))}
          </div>
          <button type="button" className="sonic-view-history" onClick={() => setHistoryOpen(true)}>
            <History />View all history
          </button>
        </section>

        <div className="sonic-sidebar-bottom">
          <GeminiSettings onConfiguredChange={(configured) => setHealth((current) => current ? { ...current, gemini_configured: configured } : current)} />
          <div className="sonic-local-status"><MonitorDot /><span>{health ? "Local API connected" : "Backend offline"}</span></div>
        </div>
      </aside>

      <section className="sonic-main-workspace">
        {message && <div className={messageType === "success" ? "sonic-global-message is-success" : "sonic-global-message is-error"} role={messageType === "success" ? "status" : "alert"}>{message}</div>}

        {!activeJob ? (
          <div className="sonic-workspace-empty">
            <AudioLines />
            <h1>Start your first transcription</h1>
            <p>Add a supported public media link or upload local media. SonicBrief will keep the transcript, summary, and history together.</p>
            <Button size="lg" onClick={() => setNewTaskOpen(true)}>New transcription</Button>
          </div>
        ) : (
          <>
            <header className="sonic-job-header">
              <div className="sonic-job-heading">
                <p className="sonic-result-eyebrow">{activeJob.status === "completed" ? "COMPLETED TRANSCRIPTION" : "SONICBRIEF JOB"}</p>
                <h1>{activeJob.title}</h1>
                <div className="sonic-job-meta">
                  <span>{sourceLabel(activeJob.source_type)}</span>
                  {activeJob.creator_name && <span>Creator: {activeJob.creator_name}</span>}
                  {activeJob.duration != null && <span>{formatTime(activeJob.duration)}</span>}
                  {activeJob.language && <span>{activeJob.language}</span>}
                  {activeJob.engine && <span>{activeJob.engine}</span>}
                </div>
              </div>

              <div className="sonic-job-header-actions">
                {activeJob.status === "completed" && (
                  <>
                    <Button variant="outline" size="sm" onClick={regenerateSummary} disabled={loading}>
                      <RefreshCw className={loading ? "animate-spin" : ""} />Regenerate
                    </Button>
                    <Button variant="outline" size="sm" onClick={() => window.open(API_BASE + "/api/jobs/" + activeJob.id + "/export/txt", "_blank")}>
                      <FileText />Export TXT
                    </Button>
                  </>
                )}
              </div>
            </header>

            {["queued", "processing"].includes(activeJob.status) ? (
              <section className="sonic-processing-card">
                <div className="sonic-processing-topline">
                  <div>
                    <p className="sonic-result-eyebrow"><LoaderCircle className="animate-spin" />PROCESSING</p>
                    <strong>{activeJob.stage_detail || stages[currentStageIndex]?.label || "Working…"}</strong>
                  </div>
                  <span>{activeJob.progress}%</span>
                </div>
                <Progress value={activeJob.progress} aria-label="Task progress" />
                <div className="sonic-processing-actions">
                  <button type="button" onClick={() => setShowPipelineDetails((value) => !value)}>{showPipelineDetails ? "Hide processing details" : "Show processing details"}</button>
                  <Button variant="ghost" size="sm" onClick={() => void cancelJob()} disabled={loading}><X />Cancel</Button>
                </div>
                {showPipelineDetails && (
                  <div className="sonic-processing-details">
                    <div className="sonic-stage-list">
                      {stages.map((item, index) => {
                        const Icon = item.icon;
                        const done = index < currentStageIndex;
                        const active = index === currentStageIndex;
                        return <div className={"sonic-stage " + (done ? "is-done " : "") + (active ? "is-current" : "")} key={item.key}><span className="sonic-stage-icon">{done ? <Check /> : <Icon />}</span><span>{item.label}</span><small>{done ? "Done" : active ? "Running" : "Waiting"}</small></div>;
                      })}
                    </div>
                    {activeJob.stage === "transcribe" && (
                      <div className="sonic-processing-meta">
                        <span>Whisper model <strong>{activeJob.model_name}</strong></span>
                        <span>Language <strong>{activeJob.language || "detecting…"}</strong></span>
                        {activeJob.processed_duration != null && activeJob.duration ? <span>Audio <strong>{formatTime(activeJob.processed_duration)} / {formatTime(activeJob.duration)}</strong></span> : null}
                      </div>
                    )}
                  </div>
                )}
              </section>
            ) : activeJob.status === "completed" ? (
              <>
                {visibleWarnings.length > 0 && <div className="sonic-warning" role="status">{visibleWarnings.map((warning) => <p key={warning}>{warning}</p>)}</div>}

                <section className="sonic-result-workspace">
                  <div className="sonic-result-tabs" role="tablist" aria-label="Job result views">
                    <button type="button" className={resultTab === "summary" ? "is-active" : ""} onClick={() => setResultTab("summary")}>Summary</button>
                    <button type="button" className={resultTab === "transcript" ? "is-active" : ""} onClick={() => setResultTab("transcript")}>Transcript</button>
                    <button type="button" className={resultTab === "details" ? "is-active" : ""} onClick={() => setResultTab("details")}>Details</button>
                  </div>

                  {resultTab === "summary" && (
                    <div className="sonic-document-view">
                      <article className="sonic-summary-document">
                        <div className="sonic-summary-document-head">
                          <div><p className="sonic-result-eyebrow"><Languages />简体中文</p><h2>Summary</h2></div>
                          <Button variant="outline" size="sm" onClick={regenerateSummary} disabled={loading}><RefreshCw className={loading ? "animate-spin" : ""} />Regenerate</Button>
                        </div>
                        <details className="sonic-summary-options">
                          <summary>Summary options · {summaryStyleLabel(summaryStyle)}</summary>
                          <div className="sonic-summary-options-grid">
                            <div className="sonic-summary-preset-field">
                              <Label htmlFor="result-summary-style">Summary style</Label>
                              <Select value={summaryStyle} onValueChange={(value) => setSummaryStyle(value as SummaryStyle)}>
                                <SelectTrigger id="result-summary-style" className="w-full"><SelectValue /></SelectTrigger>
                                <SelectContent>
                                  {SUMMARY_STYLE_OPTIONS.map((item) => <SelectItem key={item.value} value={item.value}>{item.label}</SelectItem>)}
                                </SelectContent>
                              </Select>
                              <p>{summaryStyleDescription(summaryStyle)}</p>
                            </div>
                            <div className="sonic-summary-instructions-field">
                              <Label htmlFor="result-summary-instructions">{summaryStyle === "custom" ? "Custom summary instructions" : "Additional instructions · optional"}</Label>
                              <Textarea
                                id="result-summary-instructions"
                                rows={3}
                                maxLength={4000}
                                value={summaryCustomInstructions}
                                onChange={(event) => setSummaryCustomInstructions(event.target.value)}
                                placeholder={summaryStyle === "custom" ? "Describe exactly how you want this summary written." : "Optional instructions for this regeneration."}
                              />
                            </div>
                          </div>
                        </details>
                        <div className="sonic-summary-copy">
                          {activeJob.summary ? <SummaryMarkdown content={activeJob.summary} /> : <p>No summary was generated. Add a Gemini API key, then regenerate.</p>}
                        </div>
                      </article>
                    </div>
                  )}

                  {resultTab === "transcript" && (
                    <div className="sonic-transcript-view">
                      <div className="sonic-transcript-toolbar">
                        <div>
                          <h2>Transcript</h2>
                          <p>{(activeJob.transcript ?? []).length} timestamped segments</p>
                        </div>
                        <div className="sonic-transcript-tools">
                          <label className="sonic-search-field"><Search /><input value={transcriptQuery} onChange={(event) => setTranscriptQuery(event.target.value)} placeholder="Search transcript…" /></label>
                          <Button variant="outline" size="sm" onClick={copyTranscript} disabled={!timestampedTranscript}>{transcriptCopied ? <Check /> : <Copy />}{transcriptCopied ? "Copied" : "Copy entire transcript"}</Button>
                        </div>
                      </div>
                      <div className="sonic-transcript">
                        {filteredTranscript.length ? filteredTranscript.map((segment, index) => (
                          <article key={segment.start + "-" + index}>
                            <time>{formatTime(segment.start)}</time>
                            <div>{segment.speaker && <strong>{segment.speaker}</strong>}<p>{segment.text}</p></div>
                          </article>
                        )) : <div className="sonic-no-results">No transcript segments match your search.</div>}
                      </div>
                    </div>
                  )}

                  {resultTab === "details" && (
                    <div className="sonic-details-view">
                      <div className="sonic-details-grid">
                        <div><span>Source</span><strong>{sourceLabel(activeJob.source_type)}</strong></div>
                        <div><span>Creator / Channel</span><strong>{activeJob.creator_name || "Not available"}</strong></div>
                        <div><span>Duration</span><strong>{activeJob.duration ? formatTime(activeJob.duration) : "—"}</strong></div>
                        <div><span>Transcript language</span><strong>{activeJob.language || "—"}</strong></div>
                        <div><span>Whisper model</span><strong>{activeJob.model_name || "—"}</strong></div>
                        <div><span>Processing engine</span><strong>{activeJob.engine || "—"}</strong></div>
                        <div><span>Speaker detection</span><strong>{activeJob.diarization_enabled ? (health?.diarization_configured ? "Enabled" : "Requested · HF token unavailable") : "Disabled"}</strong></div>
                        <div><span>Created</span><strong>{formatDate(activeJob.created_at)}</strong></div>
                      </div>
                    </div>
                  )}
                </section>
              </>
            ) : (
              <section className="sonic-processing-card is-error-state">
                <RotateCcw />
                <div><strong>{activeJob.status === "cancelled" ? "Processing cancelled" : "Processing stopped"}</strong><p>{activeJob.error || "This job did not complete."}</p></div>
              </section>
            )}
          </>
        )}
      </section>

      {newTaskOpen && (
        <div className="sonic-modal-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) setNewTaskOpen(false); }}>
          <section className="sonic-new-task-dialog" role="dialog" aria-modal="true" aria-labelledby="new-task-title">
            <header className="sonic-dialog-header">
              <div><h2 id="new-task-title">New transcription</h2><p>Choose the source first. Advanced processing options can stay on their defaults.</p></div>
              <button type="button" onClick={() => setNewTaskOpen(false)} aria-label="Close new transcription"><X /></button>
            </header>
            <form className="sonic-new-task-form" onSubmit={submit}>
              <Tabs value={source} onValueChange={(value) => { setSource(value as InputSource); setMessage(null); }}>
                <TabsList className="sonic-tabs sonic-source-tabs"><TabsTrigger value="url"><Video />Media URL</TabsTrigger><TabsTrigger value="upload"><UploadCloud />Media files</TabsTrigger></TabsList>
                <TabsContent value="url" className="sonic-source-panel">
                  <Label htmlFor="media-url">Media URL</Label>
                  <Textarea id="media-url" rows={4} placeholder={"Paste one public media link per line\nYouTube, Bilibili, Vimeo, TikTok, X/Twitter, SoundCloud, or Twitch"} value={url} onChange={(event) => setUrl(event.target.value)} />
                  <p className="sonic-source-help">SonicBrief detects the site automatically. Public links only; availability can vary when a site changes its playback rules.</p>
                </TabsContent>
                <TabsContent value="upload" className="sonic-source-panel">
                  <input ref={fileInput} className="sr-only" type="file" multiple accept="audio/*,video/*,.mp3,.wav,.m4a,.aac,.flac,.ogg,.opus,.mp4,.mov,.mkv,.webm,.avi,.m4v,.mpeg,.mpg,.wmv" onChange={(event) => chooseFiles(event.target.files)} />
                  <button className={"sonic-drop-zone " + (dragging ? "is-dragging" : "")} type="button" onClick={() => fileInput.current?.click()} onDragEnter={(event) => { event.preventDefault(); setDragging(true); }} onDragOver={(event) => event.preventDefault()} onDragLeave={() => setDragging(false)} onDrop={(event) => { event.preventDefault(); setDragging(false); chooseFiles(event.dataTransfer.files); }}>
                    <UploadCloud /><span>{files.length ? "Add more files (" + files.length + " selected)" : "Drop audio or video files here"}</span><small>Up to 20 files · common audio and video formats</small>
                  </button>
                  {files.length > 0 && <div className="sonic-file-queue">{files.map((file, index) => <div key={file.name + "-" + file.lastModified}><span>{file.type.startsWith("video/") ? <FileVideo /> : <FileAudio />}</span><strong>{file.name}</strong><small>{(file.size / 1024 / 1024).toFixed(1)} MB</small><button type="button" onClick={() => setFiles((current) => current.filter((_, itemIndex) => itemIndex !== index))}><X /></button></div>)}</div>}
                </TabsContent>
              </Tabs>

              <details className="sonic-advanced-options">
                <summary>Advanced options</summary>
                <div className="sonic-config-grid">
                  <div><Label htmlFor="whisper-model">Whisper model</Label><Select value={model} onValueChange={setModel}><SelectTrigger id="whisper-model" className="w-full"><SelectValue /></SelectTrigger><SelectContent><SelectItem value="turbo">Turbo · recommended</SelectItem><SelectItem value="large-v3">Large v3 · best accuracy</SelectItem><SelectItem value="distil-large-v3">Distil large v3 · English</SelectItem><SelectItem value="small">Small · faster</SelectItem></SelectContent></Select></div>
                  <div><Label htmlFor="language">Transcript language</Label><Select value={language} onValueChange={setLanguage}><SelectTrigger id="language" className="w-full"><SelectValue /></SelectTrigger><SelectContent><SelectItem value="auto">Auto-detect</SelectItem><SelectItem value="en">English</SelectItem><SelectItem value="zh">Chinese</SelectItem><SelectItem value="ms">Malay</SelectItem><SelectItem value="ja">Japanese</SelectItem><SelectItem value="ko">Korean</SelectItem><SelectItem value="id">Indonesian</SelectItem></SelectContent></Select></div>
                </div>
                <div className="sonic-summary-preset-field">
                  <Label htmlFor="new-summary-style">Summary style</Label>
                  <Select value={summaryStyle} onValueChange={(value) => setSummaryStyle(value as SummaryStyle)}>
                    <SelectTrigger id="new-summary-style" className="w-full"><SelectValue /></SelectTrigger>
                    <SelectContent>
                      {SUMMARY_STYLE_OPTIONS.map((item) => <SelectItem key={item.value} value={item.value}>{item.label}</SelectItem>)}
                    </SelectContent>
                  </Select>
                  <p>{summaryStyleDescription(summaryStyle)}</p>
                </div>
                <div className="sonic-summary-instructions-field">
                  <Label htmlFor="new-summary-instructions">{summaryStyle === "custom" ? "Custom summary instructions" : "Additional instructions · optional"}</Label>
                  <Textarea
                    id="new-summary-instructions"
                    rows={3}
                    maxLength={4000}
                    value={summaryCustomInstructions}
                    onChange={(event) => setSummaryCustomInstructions(event.target.value)}
                    placeholder={summaryStyle === "custom" ? "Example: Focus on the technical explanation, compare the approaches, and end with a practical checklist." : "Example: Focus on the technical sections and skip the introduction."}
                  />
                  <p>{summaryCustomInstructions.length.toLocaleString()} / 4,000 characters</p>
                </div>
                <div className={"sonic-speaker-option " + (!health?.diarization_configured ? "is-unavailable" : "")}><div><Label htmlFor="speaker-switch">Identify speakers</Label><p>{health?.diarization_configured ? "Label Speaker 1, Speaker 2, and others." : "Requires a Hugging Face token in backend/.env."}</p></div><Switch id="speaker-switch" checked={diarize} disabled={!health?.diarization_configured} onCheckedChange={(checked) => { setDiarizeTouched(true); setDiarize(checked); }} /></div>
              </details>

              {message && <p className={messageType === "success" ? "sonic-message" : "sonic-error"}>{message}</p>}
              <div className="sonic-dialog-actions"><Button type="button" variant="outline" onClick={() => setNewTaskOpen(false)}>Cancel</Button><Button disabled={loading || !health}>{loading ? <LoaderCircle className="animate-spin" /> : <AudioLines />}{loading ? "Adding…" : "Start transcription"}</Button></div>
            </form>
          </section>
        </div>
      )}

      {historyOpen && (
        <div className="sonic-modal-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) setHistoryOpen(false); }}>
          <section className="sonic-history-dialog" role="dialog" aria-modal="true" aria-labelledby="history-title">
            <header className="sonic-dialog-header">
              <div><h2 id="history-title">History</h2><p>Search by title, creator, source, or date.</p></div>
              <button type="button" onClick={() => setHistoryOpen(false)} aria-label="Close history"><X /></button>
            </header>

            <div className="sonic-history-toolbar">
              <label className="sonic-search-field"><Search /><input value={historyQuery} onChange={(event) => setHistoryQuery(event.target.value)} placeholder="Search history…" autoFocus /></label>
              <Select value={creatorFilter || "all"} onValueChange={(value) => setCreatorFilter(value === "all" ? "" : value)}>
                <SelectTrigger className="sonic-creator-filter"><SelectValue placeholder="All creators" /></SelectTrigger>
                <SelectContent><SelectItem value="all">All creators</SelectItem>{creators.map((creator) => <SelectItem key={creator} value={creator}>{creator}</SelectItem>)}</SelectContent>
              </Select>
            </div>

            <div className="sonic-history-results-meta">
              <span>{filteredHistory.length} {filteredHistory.length === 1 ? "result" : "results"}</span>
              {(historyQuery || creatorFilter) && <button type="button" onClick={() => { setHistoryQuery(""); setCreatorFilter(""); }}>Clear filters</button>}
            </div>

            <div className="sonic-history-browser">
              {filteredHistory.length ? filteredHistory.map((job) => (
                <div className="sonic-history-browser-row" key={job.id}>
                  <button type="button" className="sonic-history-browser-select" onClick={() => void openJob(job)}>
                    <span className="sonic-source-icon"><SourceIcon source={job.source_type} /></span>
                    <span className="sonic-history-main"><strong>{job.title}</strong><small>{job.creator_name || sourceLabel(job.source_type)} · {formatDate(job.created_at)}</small></span>
                    <span className={"sonic-job-state is-" + job.status}>{job.status === "processing" && <LoaderCircle className="animate-spin" />}{job.status}</span>
                    <span className="sonic-history-duration">{job.duration ? formatTime(job.duration) : "—"}</span>
                  </button>
                  <DropdownMenu>
                    <DropdownMenuTrigger asChild><button className="sonic-history-menu" type="button" aria-label={"Actions for " + job.title}><MoreVertical /></button></DropdownMenuTrigger>
                    <DropdownMenuContent align="end"><DropdownMenuItem onSelect={() => void renameJob(job)}><Pencil />Rename</DropdownMenuItem><DropdownMenuItem variant="destructive" onSelect={() => void deleteJob(job)}><Trash2 />Delete</DropdownMenuItem></DropdownMenuContent>
                  </DropdownMenu>
                </div>
              )) : <div className="sonic-history-empty"><Search /><div><strong>No matching history</strong><p>Try another title, creator, or filter.</p></div></div>}
            </div>
          </section>
        </div>
      )}
    </main>
  );
}
