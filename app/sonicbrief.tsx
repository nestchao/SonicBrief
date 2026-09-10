"use client";

import { type FormEvent, type ReactNode, useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  AudioLines, Check, ChevronRight, CirclePlay, Clock3, Cloud, FileAudio, FileText,
  History, Languages, LoaderCircle, Mic2, MonitorDot, RefreshCw,
  Copy, RotateCcw, Sparkles, UploadCloud, Video,
} from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Progress } from "@/components/ui/progress";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";

type SourceType = "youtube" | "bilibili" | "upload";
type JobStatus = "queued" | "processing" | "completed" | "failed";
type TranscriptSegment = { start: number; end: number; text: string; speaker?: string | null };
type Job = {
  id: string; title: string; source_type: SourceType; source_url?: string | null;
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

function SourceIcon({ source }: { source: SourceType }) {
  if (source === "youtube") return <CirclePlay aria-hidden="true" />;
  if (source === "bilibili") return <Video aria-hidden="true" />;
  return <FileAudio aria-hidden="true" />;
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
  const [source, setSource] = useState<SourceType>("youtube");
  const [url, setUrl] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [language, setLanguage] = useState("auto");
  const [model, setModel] = useState("turbo");
  const [diarize, setDiarize] = useState(true);
  const [jobs, setJobs] = useState<Job[]>([]);
  const [activeJob, setActiveJob] = useState<Job | null>(null);
  const [health, setHealth] = useState<Health | null>(null);
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [transcriptCopied, setTranscriptCopied] = useState(false);
  const [dragging, setDragging] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);

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
      if (job && ["completed", "failed"].includes(job.status)) window.clearInterval(timer);
    }, 1200);
    return () => window.clearInterval(timer);
  }, [activeJob?.id, activeJob?.status, refreshJob]);

  const currentStageIndex = useMemo(() => {
    const index = stages.findIndex((item) => item.key === activeJob?.stage);
    return index < 0 ? 0 : index;
  }, [activeJob?.stage]);

  const timestampedTranscript = useMemo(() => (activeJob?.transcript ?? [])
    .map((segment) => `[${formatTime(segment.start)}]${segment.speaker ? ` ${segment.speaker}:` : ""} ${segment.text}`)
    .join("\n"), [activeJob?.transcript]);

  function chooseFile(nextFile?: File | null) {
    if (!nextFile) return;
    setFile(nextFile);
    setMessage(null);
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setMessage(null);
    if (source === "upload" && !file) { setMessage("Choose an audio file first."); return; }
    if (source !== "upload" && !url.trim()) { setMessage(`Paste a ${source === "youtube" ? "YouTube" : "Bilibili"} link first.`); return; }
    setLoading(true);
    try {
      const body = new FormData();
      body.set("model_name", model);
      body.set("language", language);
      body.set("diarize", String(diarize));
      body.set("summary_language", "zh-CN");
      body.set("summary_style", "detailed");
      let endpoint = `${API_BASE}/api/jobs/url`;
      if (source === "upload") { body.set("file", file as File); endpoint = `${API_BASE}/api/jobs/upload`; }
      else body.set("url", url.trim());
      const response = await fetch(endpoint, { method: "POST", body });
      const data = await response.json();
      if (!response.ok) throw new Error(data.detail ?? "Unable to create this task.");
      setActiveJob(data as Job);
      setJobs((current) => [data as Job, ...current]);
      if (source === "upload") setFile(null); else setUrl("");
    } catch (error) { setMessage(error instanceof Error ? error.message : "Unable to create this task."); }
    finally { setLoading(false); }
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
      <aside className="sonic-sidebar">
        <div className="sonic-brand"><span className="sonic-mark"><AudioLines aria-hidden="true" /></span><span>SonicBrief</span></div>
        <nav className="sonic-nav" aria-label="Main navigation">
          <button className="is-active" type="button"><Sparkles aria-hidden="true" />New transcript</button>
          <button type="button" onClick={() => document.getElementById("history")?.scrollIntoView({ behavior: "smooth" })}><History aria-hidden="true" />History</button>
        </nav>
        <div className="sonic-device">
          <div className="sonic-device-title"><span className={health ? "sonic-status-dot is-online" : "sonic-status-dot"} />{health ? "Local engine ready" : "Local engine offline"}</div>
          <p>{health ? `${health.device}${health.cuda_available ? " · CUDA" : ""}` : "Start the Python service on port 7860."}</p>
          <div className="sonic-capabilities">
            <span className={health?.gemini_configured ? "is-ready" : ""}><Cloud />Gemini</span>
            <span className={health?.diarization_configured ? "is-ready" : ""}><Mic2 />Speakers</span>
          </div>
        </div>
      </aside>

      <section className="sonic-workspace">
        <header className="sonic-header">
          <div><p className="sonic-kicker">LOCAL AUDIO WORKSPACE</p><h1>Transcribe once. Understand faster.</h1></div>
          <Badge variant="outline" className="sonic-mode"><MonitorDot />Localhost only</Badge>
        </header>

        <div className="sonic-primary-grid">
          <Card className="sonic-input-card">
            <CardHeader><CardTitle>New transcript</CardTitle></CardHeader>
            <CardContent>
              <form onSubmit={submit}>
                <Tabs value={source} onValueChange={(value) => { setSource(value as SourceType); setMessage(null); }}>
                  <TabsList className="sonic-tabs">
                    <TabsTrigger value="youtube"><CirclePlay />YouTube</TabsTrigger>
                    <TabsTrigger value="bilibili"><Video />Bilibili</TabsTrigger>
                    <TabsTrigger value="upload"><UploadCloud />Audio file</TabsTrigger>
                  </TabsList>
                  <TabsContent value="youtube" className="sonic-source-panel">
                    <Label htmlFor="youtube-url">YouTube link</Label>
                    <Input id="youtube-url" type="url" placeholder="https://www.youtube.com/watch?v=..." value={url} onChange={(event) => setUrl(event.target.value)} />
                  </TabsContent>
                  <TabsContent value="bilibili" className="sonic-source-panel">
                    <Label htmlFor="bilibili-url">Bilibili link</Label>
                    <Input id="bilibili-url" type="url" placeholder="https://www.bilibili.com/video/BV..." value={url} onChange={(event) => setUrl(event.target.value)} />
                  </TabsContent>
                  <TabsContent value="upload" className="sonic-source-panel">
                    <input ref={fileInput} className="sr-only" type="file" accept="audio/*,.mp3,.wav,.m4a,.aac,.flac,.ogg,.opus" onChange={(event) => chooseFile(event.target.files?.[0])} />
                    <button className={`sonic-drop-zone ${dragging ? "is-dragging" : ""}`} type="button"
                      onClick={() => fileInput.current?.click()}
                      onDragEnter={(event) => { event.preventDefault(); setDragging(true); }}
                      onDragOver={(event) => event.preventDefault()} onDragLeave={() => setDragging(false)}
                      onDrop={(event) => { event.preventDefault(); setDragging(false); chooseFile(event.dataTransfer.files?.[0]); }}>
                      <UploadCloud aria-hidden="true" /><span>{file ? file.name : "Drop audio here or choose a file"}</span>
                      <small>{file ? `${(file.size / 1024 / 1024).toFixed(1)} MB` : "MP3, WAV, M4A, FLAC, AAC, OGG or OPUS"}</small>
                    </button>
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
                {message && <p className="sonic-error" role="alert">{message}</p>}
                <Button className="sonic-submit" size="lg" disabled={loading || !health}>{loading ? <LoaderCircle className="animate-spin" /> : <AudioLines />}Transcribe and summarize</Button>
              </form>
            </CardContent>
          </Card>

          <Card className="sonic-pipeline-card">
            <CardHeader><div className="sonic-card-heading"><CardTitle>Processing pipeline</CardTitle>{activeJob && <span>{activeJob.progress}%</span>}</div></CardHeader>
            <CardContent>
              {activeJob && ["queued", "processing"].includes(activeJob.status) ? <>
                <div className="sonic-stage-list">{stages.map((item, index) => { const Icon = item.icon; const done = index < currentStageIndex; const active = index === currentStageIndex; return (
                  <div className={`sonic-stage ${done ? "is-done" : ""} ${active ? "is-current" : ""}`} key={item.key}>
                    <span className="sonic-stage-icon">{done ? <Check /> : <Icon />}</span><span>{item.label}</span><small>{done ? "Done" : active ? "Running" : "Waiting"}</small>
                  </div>); })}</div>
                <Progress value={activeJob.progress} aria-label="Task progress" />
                <p className="sonic-pipeline-note">Gemini is used only when local transcription quality is too low.</p>
              </> : activeJob?.status === "failed" ? <div className="sonic-empty-state is-error"><RotateCcw /><strong>Processing stopped</strong><p>{activeJob.error ?? "An unexpected error occurred."}</p></div>
                : <div className="sonic-empty-state"><AudioLines /><strong>Ready for audio</strong><p>Your progress will appear here after you start a task.</p></div>}
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
          {jobs.length ? <div className="sonic-history-list">{jobs.map((job) => <button className={activeJob?.id === job.id ? "is-selected" : ""} type="button" key={job.id} onClick={() => void refreshJob(job.id)}>
            <span className="sonic-source-icon"><SourceIcon source={job.source_type} /></span><span className="sonic-history-main"><strong>{job.title}</strong><small>{job.source_type === "upload" ? "Audio upload" : job.source_type === "youtube" ? "YouTube" : "Bilibili"} · {formatDate(job.created_at)}</small></span>
            <span className={`sonic-job-state is-${job.status}`}>{job.status === "processing" && <LoaderCircle className="animate-spin" />}{job.status}</span><span className="sonic-history-duration">{job.duration ? formatTime(job.duration) : "—"}</span><ChevronRight className="sonic-chevron" aria-hidden="true" />
          </button>)}</div> : <div className="sonic-history-empty"><FileText /><div><strong>No saved transcripts yet</strong><p>Your completed work will stay on this computer.</p></div></div>}
        </section>
      </section>
    </main>
  );
}
