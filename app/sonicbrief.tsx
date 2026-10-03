"use client";

import { type FormEvent, type ReactNode, useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  AudioLines, Check, ChevronRight, CirclePlay, Clock3, FileAudio, FileText, FileVideo, MoreVertical,
  Languages, LoaderCircle, Mic2, MonitorDot, RefreshCw,
  Copy, Pencil, RotateCcw, Sparkles, Trash2, UploadCloud, Video, X,
} from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Label } from "@/components/ui/label";
import { Progress } from "@/components/ui/progress";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Textarea } from "@/components/ui/textarea";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";

type InputSource = "youtube" | "bilibili" | "upload";
type JobSource = "youtube" | "bilibili" | "upload" | "audio_upload" | "video_upload";
type JobStatus = "queued" | "processing" | "completed" | "failed" | "cancelled";
type TranscriptSegment = { start: number; end: number; text: string; speaker?: string | null };
type Job = {
  id: string; title: string; source_type: JobSource; source_url?: string | null;
  status: JobStatus; progress: number; stage: string; created_at: string; updated_at: string;
  duration?: number | null; language?: string | null; engine?: string | null;
  error?: string | null; warnings?: string[]; transcript?: TranscriptSegment[]; summary?: string | null;
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
  if (source === "bilibili") return <Video aria-hidden="true" />;
  if (source === "video_upload") return <FileVideo aria-hidden="true" />;
  return <FileAudio aria-hidden="true" />;
}

function sourceLabel(source: JobSource) {
  if (source === "youtube") return "YouTube";
  if (source === "bilibili") return "Bilibili";
  if (source === "video_upload") return "Video upload";
  return "Audio upload";
}

function renderInlineMarkdown(text: string): ReactNode[] {
  const tokens = text.split(/(\*\*[^*]+\*\*|__[^_]+__|`[^`]+`|\*[^*]+\*|_[^_]+_)/g);
  return tokens.map((token, index) => {
    if (/^\*\*[^*]+\*\*$/.test(token) || /^__[^_]+__$/.test(token)) {
      return <strong key={`${token}-${index}`}>{token.slice(2, -2)}</strong>;
    }
    if (/^`[^`]+`$/.test(token)) {
      return <code key={`${token}-${index}`}>{token.slice(1, -1)}</code>;
    }
    if (/^\*[^*]+\*$/.test(token) || /^_[^_]+_$/.test(token)) {
      return <em key={`${token}-${index}`}>{token.slice(1, -1)}</em>;
    }
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
    if (!line) {
      index += 1;
      continue;
    }

    const heading = line.match(/^(#{1,6})\s+(.+)$/);
    if (heading) {
      const level = Math.min(heading[1].length, 4);
      const Heading = level <= 2 ? "h3" : "h4";
      blocks.push(<Heading className={`sonic-summary-heading is-level-${level}`} key={`heading-${index}`}>{renderInlineMarkdown(heading[2])}</Heading>);
      index += 1;
      continue;
    }

    if (/^(?:[-*_]\s*){3,}$/.test(line)) {
      blocks.push(<hr key={`rule-${index}`} />);
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
      blocks.push(<List key={`list-${index}`}>{items.map((item, itemIndex) => <li key={`${item}-${itemIndex}`}>{renderInlineMarkdown(item)}</li>)}</List>);
      continue;
    }

    if (/^>\s?/.test(line)) {
      const quoteLines: string[] = [];
      while (index < lines.length && /^>\s?/.test(lines[index].trim())) {
        quoteLines.push(lines[index].trim().replace(/^>\s?/, ""));
        index += 1;
      }
      blocks.push(<blockquote key={`quote-${index}`}>{renderInlineMarkdown(joinMarkdownLines(quoteLines))}</blockquote>);
      continue;
    }

    const paragraphLines = [line];
    index += 1;
    while (index < lines.length && lines[index].trim() && !isMarkdownBlockStart(lines[index].trim())) {
      paragraphLines.push(lines[index].trim());
      index += 1;
    }
    blocks.push(<p key={`paragraph-${index}`}>{renderInlineMarkdown(joinMarkdownLines(paragraphLines))}</p>);
  }

  return <div className="sonic-summary-content">{blocks}</div>;
}

export function SonicBriefApp() {
  const [source, setSource] = useState<InputSource>("youtube");
  const [url, setUrl] = useState("");
  const [files, setFiles] = useState<File[]>([]);
  const [language, setLanguage] = useState("auto");
  const [model, setModel] = useState("turbo");
  const [diarize, setDiarize] = useState(true);
  const [jobs, setJobs] = useState<Job[]>([]);
  const [activeJob, setActiveJob] = useState<Job | null>(null);
  const [health, setHealth] = useState<Health | null>(null);
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [messageType, setMessageType] = useState<"error" | "success">("error");
  const [transcriptCopied, setTranscriptCopied] = useState(false);
  const [dragging, setDragging] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);
  const hasPendingJobs = jobs.some((job) => ["queued", "processing"].includes(job.status));

  const loadHealth = useCallback(async () => {
    try {
      const response = await fetch(`${API_BASE}/api/health`);
      if (!response.ok) throw new Error("Backend unavailable");
      setHealth(await response.json());
    } catch { setHealth(null); }
  }, []);

  const loadJobs = useCallback(async () => {
    try {
      const response = await fetch(`${API_BASE}/api/jobs`);
      if (!response.ok) return;
      const data = (await response.json()) as Job[];
      setJobs(data);
      setActiveJob((current) => current ?? data[0] ?? null);
    } catch { /* The local-service indicator already explains this state. */ }
  }, []);

  const refreshJob = useCallback(async (jobId: string) => {
    try {
      const response = await fetch(`${API_BASE}/api/jobs/${jobId}`);
      if (!response.ok) return null;
      const job = (await response.json()) as Job;
      setActiveJob(job);
      setJobs((current) => [job, ...current.filter((item) => item.id !== job.id)].sort((a, b) => b.updated_at.localeCompare(a.updated_at)));
      return job;
    } catch { return null; }
  }, []);

  useEffect(() => { void loadHealth(); void loadJobs(); }, [loadHealth, loadJobs]);
  useEffect(() => {
    if (health) return;
    const timer = window.setInterval(() => void loadHealth(), 3000);
    return () => window.clearInterval(timer);
  }, [health, loadHealth]);
  useEffect(() => {
    if (activeJob?.status === "completed" && activeJob.transcript === undefined) {
      void refreshJob(activeJob.id);
    }
  }, [activeJob?.id, activeJob?.status, activeJob?.transcript, refreshJob]);
  useEffect(() => {
    if (!activeJob || !["queued", "processing"].includes(activeJob.status)) return;
    const timer = window.setInterval(async () => {
      const job = await refreshJob(activeJob.id);
      if (job && ["completed", "failed", "cancelled"].includes(job.status)) window.clearInterval(timer);
    }, 1200);
    return () => window.clearInterval(timer);
  }, [activeJob?.id, activeJob?.status, refreshJob]);
  useEffect(() => {
    if (!hasPendingJobs) return;
    const timer = window.setInterval(() => void loadJobs(), 2000);
    return () => window.clearInterval(timer);
  }, [hasPendingJobs, loadJobs]);

  const currentStageIndex = useMemo(() => {
    const index = stages.findIndex((item) => item.key === activeJob?.stage);
    return index < 0 ? 0 : index;
  }, [activeJob?.stage]);

  const timestampedTranscript = useMemo(() => (activeJob?.transcript ?? [])
    .map((segment) => `[${formatTime(segment.start)}]${segment.speaker ? ` ${segment.speaker}:` : ""} ${segment.text}`)
    .join("\n"), [activeJob?.transcript]);

  function chooseFiles(nextFiles?: FileList | File[] | null) {
    if (!nextFiles?.length) return;
    const combined = [...files, ...Array.from(nextFiles)];
    const unique = combined.filter((item, index) => combined.findIndex((candidate) =>
      candidate.name === item.name && candidate.size === item.size && candidate.lastModified === item.lastModified) === index);
    setFiles(unique.slice(0, 20));
    setMessage(unique.length > 20 ? "A batch can contain at most 20 files; extra files were not added." : null);
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setMessageType("error");
    setMessage(null);
    if (source === "upload" && !files.length) { setMessage("Choose at least one audio or video file."); return; }
    if (source !== "upload" && !url.trim()) { setMessage(`Paste at least one ${source === "youtube" ? "YouTube" : "Bilibili"} link.`); return; }

    const urls = source === "upload"
      ? []
      : [...new Set(url.split(/\r?\n/).map((item) => item.trim()).filter(Boolean))].slice(0, 20);

    if (source !== "upload" && !urls.length) {
      setMessage("Paste at least one valid URL.");
      return;
    }

    if (source !== "upload" && url.split(/\r?\n/).map((item) => item.trim()).filter(Boolean).length > 20) {
      setMessage("A batch can contain at most 20 URLs; extra URLs were not added.");
    }

    setLoading(true);
    try {
      const createRequest = async (file?: File, sourceUrl?: string) => {
        const body = new FormData();
        body.set("model_name", model);
        body.set("language", language);
        body.set("diarize", String(diarize));
        body.set("summary_language", "zh-CN");
        body.set("summary_style", "detailed");

        let endpoint = `${API_BASE}/api/jobs/upload`;
        if (file) {
          body.set("file", file);
        } else {
          endpoint = `${API_BASE}/api/jobs/url`;
          body.set("url", sourceUrl ?? "");
        }

        const response = await fetch(endpoint, { method: "POST", body });
        const data = await response.json().catch(() => ({}));
        if (!response.ok) throw new Error(data.detail ?? "Unable to create this task.");
        return data as Job;
      };

      const created: Job[] = [];
      if (source === "upload") {
        for (const file of files) {
          created.push(await createRequest(file));
        }
      } else {
        for (const sourceUrl of urls) {
          created.push(await createRequest(undefined, sourceUrl));
        }
      }

      if (!created.length) throw new Error("No tasks were created.");
      setActiveJob(created[0]);
      setJobs((current) => [...created, ...current.filter((job) => !created.some((item) => item.id === job.id))]);
      setMessageType("success");
      setMessage(`${created.length} ${created.length === 1 ? "task" : "tasks"} added to the processing queue.`);
      if (source === "upload") { setFiles([]); if (fileInput.current) fileInput.current.value = ""; } else setUrl("");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Unable to create this task.");
    } finally {
      setLoading(false);
    }
  }

  async function cancelJob() {
    if (!activeJob || !["queued", "processing"].includes(activeJob.status)) return;
    if (!window.confirm("Cancel this processing task? The transcript already produced will be kept, but the final summary will not be saved.")) return;
    setLoading(true);
    setMessage(null);
    try {
      const response = await fetch(API_BASE + "/api/jobs/" + activeJob.id + "/cancel", { method: "POST" });
      const data = await response.json();
      if (!response.ok) throw new Error(data.detail ?? "Could not cancel this task.");
      setActiveJob(data as Job);
      setJobs((current) => current.map((job) => job.id === data.id ? data : job));
      setMessageType("success");
      setMessage("Processing cancelled. The transcript already produced was kept.");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Could not cancel this task.");
    } finally {
      setLoading(false);
    }
  }

  async function regenerateSummary() {
    if (!activeJob) return;
    setLoading(true);
    setMessage(null);
    try {
      const body = new FormData();
      body.set("summary_language", "zh-CN");
      body.set("summary_style", "detailed");
      const response = await fetch(`${API_BASE}/api/jobs/${activeJob.id}/summaries`, { method: "POST", body });
      const data = await response.json();
      if (!response.ok) throw new Error(data.detail ?? "Could not regenerate the summary.");
      setActiveJob(data as Job);
      setJobs((current) => current.map((job) => job.id === data.id ? data : job));
    } catch (error) { setMessage(error instanceof Error ? error.message : "Could not regenerate the summary."); }
    finally { setLoading(false); }
  }

  async function renameJob(job: Job) {
    const nextTitle = window.prompt("Rename task", job.title)?.trim();
    if (!nextTitle || nextTitle === job.title) return;
    setLoading(true);
    setMessageType("error");
    setMessage(null);
    try {
      const body = new FormData();
      body.set("title", nextTitle);
      const response = await fetch(`${API_BASE}/api/jobs/${job.id}/rename`, { method: "POST", body });
      const data = await response.json();
      if (!response.ok) throw new Error(data.detail ?? "Could not rename this task.");
      if (activeJob?.id === data.id) setActiveJob(data as Job);
      setJobs((current) => current.map((job) => job.id === data.id ? data : job));
      setMessageType("success");
      setMessage("Task renamed.");
    } catch (error) { setMessage(error instanceof Error ? error.message : "Could not rename this task."); }
    finally { setLoading(false); }
  }

  async function deleteJob(job: Job) {
    if (!window.confirm(`Delete “${job.title}”? This cannot be undone.`)) return;
    setLoading(true);
    setMessageType("error");
    setMessage(null);
    try {
      const response = await fetch(`${API_BASE}/api/jobs/${job.id}`, { method: "DELETE" });
      if (!response.ok) {
        const data = await response.json().catch(() => ({}));
        throw new Error(data.detail ?? "Could not delete this task.");
      }
      const remaining = jobs.filter((item) => item.id !== job.id);
      setJobs(remaining);
      if (activeJob?.id === job.id) setActiveJob(remaining[0] ?? null);
      setMessageType("success");
      setMessage("Task deleted.");
    } catch (error) { setMessage(error instanceof Error ? error.message : "Could not delete this task."); }
    finally { setLoading(false); }
  }

  async function copyTranscript() {
    if (!timestampedTranscript) return;
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(timestampedTranscript);
      } else {
        const textarea = document.createElement("textarea");
        textarea.value = timestampedTranscript;
        textarea.style.position = "fixed";
        textarea.style.opacity = "0";
        document.body.appendChild(textarea);
        textarea.select();
        const copied = document.execCommand("copy");
        textarea.remove();
        if (!copied) throw new Error("Clipboard access was unavailable.");
      }
      setTranscriptCopied(true);
      window.setTimeout(() => setTranscriptCopied(false), 1800);
    } catch {
      setMessage("Could not copy the transcript. Check your browser clipboard permissions.");
    }
  }

  return (
    <main className="sonic-shell">
      <section className="sonic-workspace">
        <header className="sonic-header">
          <div><p className="sonic-kicker">LOCAL MEDIA WORKSPACE</p><h1>Transcribe once. Understand faster.</h1></div>
          <Badge variant="outline" className="sonic-mode"><MonitorDot />Localhost only</Badge>
        </header>

        <div className="sonic-primary-grid">
          <Card className="sonic-input-card">
            <CardHeader><CardTitle>New transcript</CardTitle></CardHeader>
            <CardContent>
              <form onSubmit={submit}>
                <Tabs value={source} onValueChange={(value) => { setSource(value as InputSource); setMessage(null); }}>
                  <TabsList className="sonic-tabs">
                    <TabsTrigger value="youtube"><CirclePlay />YouTube</TabsTrigger>
                    <TabsTrigger value="bilibili"><Video />Bilibili</TabsTrigger>
                    <TabsTrigger value="upload"><UploadCloud />Media files</TabsTrigger>
                  </TabsList>
                  <TabsContent value="youtube" className="sonic-source-panel">
                    <Label htmlFor="youtube-url">YouTube link</Label>
                    <Textarea id="youtube-url" rows={4} placeholder={"Paste one YouTube link per line\nhttps://www.youtube.com/watch?v=..."} value={url} onChange={(event) => setUrl(event.target.value)} />
                  </TabsContent>
                  <TabsContent value="bilibili" className="sonic-source-panel">
                    <Label htmlFor="bilibili-url">Bilibili link</Label>
                    <Textarea id="bilibili-url" rows={4} placeholder={"Paste one Bilibili link per line\nhttps://www.bilibili.com/video/BV..."} value={url} onChange={(event) => setUrl(event.target.value)} />
                  </TabsContent>
                  <TabsContent value="upload" className="sonic-source-panel">
                    <input ref={fileInput} className="sr-only" type="file" multiple accept="audio/*,video/*,.mp3,.wav,.m4a,.aac,.flac,.ogg,.opus,.mp4,.mov,.mkv,.webm,.avi,.m4v,.mpeg,.mpg,.wmv" onChange={(event) => chooseFiles(event.target.files)} />
                    <button className={`sonic-drop-zone ${dragging ? "is-dragging" : ""}`} type="button"
                      onClick={() => fileInput.current?.click()}
                      onDragEnter={(event) => { event.preventDefault(); setDragging(true); }}
                      onDragOver={(event) => event.preventDefault()} onDragLeave={() => setDragging(false)}
                      onDrop={(event) => { event.preventDefault(); setDragging(false); chooseFiles(event.dataTransfer.files); }}>
                      <UploadCloud aria-hidden="true" /><span>{files.length ? `Add more files (${files.length} selected)` : "Drop audio or video files here"}</span>
                      <small>Up to 20 files · MP3, WAV, M4A, MP4, MOV, MKV, WebM and more</small>
                    </button>
                    {files.length > 0 && <div className="sonic-file-queue" aria-label="Selected media files">{files.map((file, index) => <div key={`${file.name}-${file.lastModified}`}>
                      <span>{file.type.startsWith("video/") ? <FileVideo /> : <FileAudio />}</span><strong>{file.name}</strong><small>{(file.size / 1024 / 1024).toFixed(1)} MB</small>
                      <button type="button" aria-label={`Remove ${file.name}`} onClick={() => setFiles((current) => current.filter((_, itemIndex) => itemIndex !== index))}><X /></button>
                    </div>)}</div>}
                  </TabsContent>
                </Tabs>

                <div className="sonic-config-grid">
                  <div><Label htmlFor="whisper-model">Whisper model</Label>
                    <Select value={model} onValueChange={setModel}><SelectTrigger id="whisper-model" className="w-full"><SelectValue /></SelectTrigger>
                      <SelectContent><SelectItem value="turbo">Turbo · recommended</SelectItem><SelectItem value="large-v3">Large v3 · best accuracy</SelectItem><SelectItem value="distil-large-v3">Distil large v3 · English</SelectItem></SelectContent>
                    </Select>
                  </div>
                  <div><Label htmlFor="language">Transcript language</Label>
                    <Select value={language} onValueChange={setLanguage}><SelectTrigger id="language" className="w-full"><SelectValue /></SelectTrigger>
                      <SelectContent><SelectItem value="auto">Auto-detect</SelectItem><SelectItem value="en">English</SelectItem><SelectItem value="zh">Chinese</SelectItem><SelectItem value="ms">Malay</SelectItem><SelectItem value="ja">Japanese</SelectItem><SelectItem value="ko">Korean</SelectItem><SelectItem value="id">Indonesian</SelectItem></SelectContent>
                    </Select>
                  </div>
                </div>

                <div className="sonic-speaker-option">
                  <div><Label htmlFor="speaker-switch">Identify speakers</Label><p>Label Speaker 1, Speaker 2, and others.</p></div>
                  <Switch id="speaker-switch" checked={diarize} onCheckedChange={setDiarize} aria-label="Identify speakers" />
                </div>
                {message && <p className={messageType === "success" ? "sonic-message" : "sonic-error"} role={messageType === "success" ? "status" : "alert"}>{message}</p>}
                <Button className="sonic-submit" size="lg" disabled={loading || !health}>{loading ? <LoaderCircle className="animate-spin" /> : <AudioLines />}{loading ? "Adding batch…" : "Transcribe and summarize"}</Button>
              </form>
            </CardContent>
          </Card>

          <Card className="sonic-pipeline-card">
            <CardHeader>
              <div className="sonic-card-heading">
                <CardTitle>Processing pipeline</CardTitle>
                {activeJob && <div className="sonic-pipeline-header-actions">
                  <span>{activeJob.progress}%</span>
                  {["queued", "processing"].includes(activeJob.status) && (
                    <Button
                      variant="ghost"
                      size="sm"
                      className="sonic-cancel-button"
                      onClick={() => void cancelJob()}
                      disabled={loading}
                      aria-label="Cancel processing"
                    >
                      <X />Cancel
                    </Button>
                  )}
                </div>}
              </div>
            </CardHeader>
            <CardContent>
              {activeJob && ["queued", "processing"].includes(activeJob.status) ? <>
                <div className="sonic-stage-list">{stages.map((item, index) => { const Icon = item.icon; const done = index < currentStageIndex; const active = index === currentStageIndex; return (
                  <div className={`sonic-stage ${done ? "is-done" : ""} ${active ? "is-current" : ""}`} key={item.key}>
                    <span className="sonic-stage-icon">{done ? <Check /> : <Icon />}</span><span>{item.label}</span><small>{done ? "Done" : active ? "Running" : "Waiting"}</small>
                  </div>); })}</div>
                <div className="sonic-progress-actions">
                  <Progress value={activeJob.progress} aria-label="Task progress" />
                </div>
                <p className="sonic-pipeline-note">Canceling during summary generation stops the result from being saved. The in-flight Gemini request may take a moment to return.</p>
              </> : activeJob?.status === "failed" ? <div className="sonic-empty-state is-error"><RotateCcw /><strong>Processing stopped</strong><p>{activeJob.error ?? "An unexpected error occurred."}</p></div>
                : activeJob?.status === "cancelled" ? <div className="sonic-empty-state"><X /><strong>Processing cancelled</strong><p>The task was stopped by you. Any transcript already produced remains saved.</p></div>
                : <div className="sonic-empty-state"><AudioLines /><strong>Ready for media</strong><p>Your progress will appear here after you start a task.</p></div>}
            </CardContent>
          </Card>
        </div>

        {activeJob?.status === "completed" && <>
        {(activeJob.warnings?.length ?? 0) > 0 && <div className="sonic-warning" role="status">{activeJob.warnings?.map((warning) => <p key={warning}>{warning}</p>)}</div>}
        <section className="sonic-result-grid" aria-label="Transcript result">
          <Card className="sonic-result-card"><CardHeader><div className="sonic-card-heading"><div><p className="sonic-result-eyebrow"><Languages />简体中文</p><CardTitle>Summary</CardTitle></div>
            <Button variant="outline" size="sm" onClick={regenerateSummary} disabled={loading}><RefreshCw className={loading ? "animate-spin" : ""} />Regenerate</Button></div></CardHeader>
            <CardContent><div className="sonic-summary-copy">{activeJob.summary ? <SummaryMarkdown content={activeJob.summary} /> : <p>No summary was generated. Add a Gemini API key, then regenerate.</p>}</div></CardContent>
          </Card>
          <Card className="sonic-result-card"><CardHeader><div className="sonic-card-heading"><div><p className="sonic-result-eyebrow"><Clock3 />{formatTime(activeJob.duration ?? 0)}</p><CardTitle>Timestamped transcript</CardTitle></div><div className="sonic-card-actions"><Button variant="outline" size="sm" onClick={copyTranscript} disabled={!timestampedTranscript}>{transcriptCopied ? <Check /> : <Copy />}{transcriptCopied ? "Copied" : "Copy transcript"}</Button><Badge variant="secondary">{activeJob.engine ?? "local"}</Badge></div></div></CardHeader>
            <CardContent><div className="sonic-transcript">{(activeJob.transcript ?? []).map((segment, index) => <article key={`${segment.start}-${index}`}><time>{formatTime(segment.start)}</time><div>{segment.speaker && <strong>{segment.speaker}</strong>}<p>{segment.text}</p></div></article>)}</div></CardContent>
          </Card>
        </section></>}

        <section className="sonic-history" id="history">
          <div className="sonic-section-heading"><div><p className="sonic-kicker">SAVED LOCALLY</p><h2>Recent transcripts</h2></div><Button variant="ghost" size="sm" onClick={() => { void loadJobs(); void loadHealth(); }}><RefreshCw />Refresh</Button></div>
          {jobs.length ? <div className="sonic-history-list">{jobs.map((job) => <div className={`sonic-history-row ${activeJob?.id === job.id ? "is-selected" : ""}`} key={job.id}>
            <button className="sonic-history-select" type="button" onClick={() => void refreshJob(job.id)}>
              <span className="sonic-source-icon"><SourceIcon source={job.source_type} /></span><span className="sonic-history-main"><strong>{job.title}</strong><small>{sourceLabel(job.source_type)} · {formatDate(job.created_at)}</small></span>
              <span className={`sonic-job-state is-${job.status}`}>{job.status === "processing" && <LoaderCircle className="animate-spin" />}{job.status}</span><span className="sonic-history-duration">{job.duration ? formatTime(job.duration) : "—"}</span><ChevronRight className="sonic-chevron" aria-hidden="true" />
            </button>
            <DropdownMenu><DropdownMenuTrigger asChild><button className="sonic-history-menu" type="button" aria-label={`Actions for ${job.title}`}><MoreVertical /></button></DropdownMenuTrigger><DropdownMenuContent align="end">
              <DropdownMenuItem onSelect={() => void renameJob(job)}><Pencil />Rename</DropdownMenuItem>
              <DropdownMenuItem variant="destructive" onSelect={() => void deleteJob(job)}><Trash2 />Delete</DropdownMenuItem>
            </DropdownMenuContent></DropdownMenu>
          </div>)}</div> : <div className="sonic-history-empty"><FileText /><div><strong>No saved transcripts yet</strong><p>Your completed work will stay on this computer.</p></div></div>}
        </section>
      </section>
    </main>
  );
}
