import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("..", import.meta.url));

test("workspace redesign keeps summary Markdown semantics intact", async () => {
  const source = await readFile(path.join(root, "app", "sonicbrief.tsx"), "utf8");

  assert.ok(source.includes('content.replace(/\\r\\n?/g, "\\n").trim().split("\\n")'));
  assert.ok(source.includes('line.match(/^(#{1,6})\\s+(.+)$/)'));
  assert.ok(source.includes('current.match(/^\\d+[.)]\\s+(.+)$/)'));
  assert.match(source, /sonic-summary-heading/);
  assert.match(source, /sonic-timestamp-chip/);
  assert.ok(!source.includes('split("\\\\n")'));
});

test("workspace includes searchable history, creator filters, and transcript copy", async () => {
  const source = await readFile(path.join(root, "app", "sonicbrief.tsx"), "utf8");

  assert.match(source, /creator_name\?: string \| null/);
  assert.match(source, /historyQuery/);
  assert.match(source, /creatorFilter/);
  assert.match(source, /filteredHistory/);
  assert.match(source, /Search history…/);
  assert.match(source, /Copy entire transcript/);
  assert.match(source, /transcriptQuery/);
  assert.match(source, /\/api\/jobs\?limit=200/);
  assert.ok(source.includes('.join("\\n")'));
  assert.ok(source.includes('url.split(/\\r?\\n/)'));
});

test("backend persists creator metadata from downloaded media", async () => {
  const storage = await readFile(path.join(root, "backend", "storage.py"), "utf8");
  const pipeline = await readFile(path.join(root, "backend", "pipeline.py"), "utf8");

  assert.match(storage, /creator_name TEXT/);
  assert.match(storage, /ALTER TABLE jobs ADD COLUMN creator_name TEXT/);
  assert.match(storage, /"creator_name"/);
  assert.match(pipeline, /info\.get\("channel"\) or info\.get\("uploader"\) or info\.get\("uploader_id"\)/);
  assert.match(pipeline, /creator_name=metadata\.get\("creator_name"\)/);
  assert.match(pipeline, /storage\.update_job\([\s\S]*?creator_name=metadata\.get\("creator_name"\)[\s\S]*?progress=18/);
});

test("summary prompt requests structured Markdown", async () => {
  const pipeline = await readFile(path.join(root, "backend", "pipeline.py"), "utf8");

  assert.match(pipeline, /Return clean Markdown only/);
  assert.match(pipeline, /Use ## headings/);
  assert.match(pipeline, /\*\*bold\*\*/);
  assert.match(pipeline, /\[123\.4s\]/);
});


test("speaker detection respects backend capability", async () => {
  const source = await readFile(path.join(root, "app", "sonicbrief.tsx"), "utf8");

  assert.ok(source.includes("const [diarize, setDiarize] = useState(false)"));
  assert.match(source, /diarizeTouched/);
  assert.match(source, /health\?\.diarization_configured/);
  assert.ok(source.includes("disabled={!health?.diarization_configured}"));
  assert.ok(source.includes("Requires a Hugging Face token in backend/.env."));
  assert.ok(source.includes("Speaker identification was skipped: Speaker identification needs HF_TOKEN"));
  assert.ok(source.includes("Requested · HF token unavailable"));
});


test("media URL input auto-detects an explicit supported-site allowlist", async () => {
  const source = await readFile(path.join(root, "app", "sonicbrief.tsx"), "utf8");
  const config = await readFile(path.join(root, "backend", "config.py"), "utf8");
  const pipeline = await readFile(path.join(root, "backend", "pipeline.py"), "utf8");
  const app = await readFile(path.join(root, "backend", "app.py"), "utf8");

  assert.ok(source.includes('type InputSource = "url" | "upload"'));
  assert.ok(source.includes('TabsTrigger value="url"'));
  assert.ok(source.includes("Media URL"));
  assert.ok(source.includes("YouTube, Bilibili, Vimeo, TikTok, X/Twitter, SoundCloud, or Twitch"));
  assert.ok(!source.includes('TabsTrigger value="youtube"'));
  assert.ok(!source.includes('TabsTrigger value="bilibili"'));

  for (const domain of ["youtube.com", "bilibili.com", "vimeo.com", "tiktok.com", "x.com", "twitter.com", "soundcloud.com", "twitch.tv"]) {
    assert.ok(config.includes('"' + domain + '"'));
  }
  assert.match(pipeline, /host == domain or host\.endswith\("\." \+ domain\)/);
  assert.match(pipeline, /Supported media links: YouTube, Bilibili, Vimeo, TikTok, X\/Twitter, SoundCloud, and Twitch/);
  assert.match(app, /pipeline\.source_label\(source_type\)/);
  assert.doesNotMatch(pipeline, /Only YouTube and Bilibili links are supported/);
});


test("summary presets and custom instructions are wired end to end", async () => {
  const source = await readFile(path.join(root, "app", "sonicbrief.tsx"), "utf8");
  const pipeline = await readFile(path.join(root, "backend", "pipeline.py"), "utf8");
  const app = await readFile(path.join(root, "backend", "app.py"), "utf8");

  for (const style of ["brief", "standard", "detailed", "study_notes", "key_points", "custom"]) {
    assert.ok(pipeline.includes('"' + style + '"'));
    assert.ok(source.includes('"' + style + '"'));
  }

  assert.ok(source.includes('useState<SummaryStyle>("standard")'));
  assert.ok(source.includes('sonicbrief-summary-style'));
  assert.ok(source.includes('body.set("summary_style", summaryStyle)'));
  assert.ok(source.includes('body.set("summary_custom_instructions", summaryCustomInstructions.trim())'));
  assert.ok(source.includes("Custom summary style needs instructions."));
  assert.ok(source.includes('maxLength={4000}'));
  assert.ok(source.includes("Summary options ·"));
  assert.ok(source.includes("Additional instructions · optional"));

  assert.match(app, /summary_style: str = Form\("standard"\)/);
  assert.match(app, /summary_custom_instructions: str = Form\(""\)/);
  assert.match(app, /generate_summary: bool = Form\(False\)/);
  assert.match(app, /allow_gemini_fallback: bool = Form\(False\)/);
  assert.ok(source.includes('body.set("generate_summary", "true")'));
  assert.ok(source.includes('body.set("allow_gemini_fallback", "true")'));
  assert.match(app, /validate_summary_options/);
  assert.match(pipeline, /Custom summary style requires custom instructions/);
  assert.match(pipeline, /Custom summary instructions must be 4,000 characters or fewer/);
  assert.match(pipeline, /USER CUSTOM INSTRUCTIONS/);
});


test("media URL extraction accepts copied share text and strips closing punctuation", async () => {
  const source = await readFile(path.join(root, "app", "sonicbrief.tsx"), "utf8");

  assert.match(source, /value\.match\(\/https\?:\\\/\\\/\[\^\\s<>\]\+\/i\)/);
  assert.ok(source.includes('replace(/[，。！？、）】》)\\]}>"\'”’]+$/u, "")'));
});


test("Bilibili downloads retry interrupted transfers and report clean errors", async () => {
  const pipeline = await readFile(path.join(root, "backend", "pipeline.py"), "utf8");

  assert.match(pipeline, /"continuedl": True/);
  assert.match(pipeline, /"retries": 10/);
  assert.match(pipeline, /"fragment_retries": 10/);
  assert.match(pipeline, /"extractor_retries": 3/);
  assert.match(pipeline, /source_type == "bilibili"/);
  assert.match(pipeline, /"http_chunk_size".*5 \* 1024 \* 1024/);
  assert.match(pipeline, /for attempt in range\(2\)/);
  assert.match(pipeline, /Media download was interrupted before the source finished sending the file/);
  assert.match(pipeline, /_ANSI_ESCAPE_PATTERN/);
});


test("local MCP bridge exposes SonicBrief jobs without loading Whisper itself", async () => {
  const mcpServer = await readFile(path.join(root, "sonicbrief_mcp.py"), "utf8");
  const requirements = await readFile(path.join(root, "backend", "requirements.txt"), "utf8");

  assert.match(mcpServer, /from mcp\.server import MCPServer/);
  assert.match(mcpServer, /MCPServer\("SonicBrief"\)/);
  assert.match(mcpServer, /SONICBRIEF_API_BASE/);
  assert.match(mcpServer, /127\.0\.0\.1:7860/);
  assert.match(mcpServer, /processing_location": "local computer"/);
  assert.match(mcpServer, /whisper_location": "local SonicBrief backend"/);
  assert.doesNotMatch(mcpServer, /from faster_whisper|WhisperModel/);

  for (const tool of [
    "submit_media_url",
    "submit_media_urls",
    "submit_local_file",
    "get_job",
    "list_jobs",
    "search_jobs",
    "get_summary",
    "get_transcript",
    "generate_summary_with_gemini",
    "cancel_job",
    "delete_job",
  ]) {
    assert.ok(mcpServer.includes("def " + tool + "("));
  }

  assert.match(mcpServer, /SONICBRIEF_AUTO_START_BACKEND/);
  assert.match(mcpServer, /subprocess\.Popen/);
  assert.match(mcpServer, /def _ensure_backend\(\)/);
  assert.match(mcpServer, /generate_gemini_summary: bool = False/);
  assert.match(mcpServer, /allow_gemini_fallback: bool = False/);
  assert.match(mcpServer, /default_mcp_mode": "local transcript only"/);
  assert.match(mcpServer, /mcp\.run\(\)/);
  assert.match(requirements, /mcp>=2,<3/);
  assert.match(requirements, /httpx>=0\.28,<1/);
});


test("Docker MCP image keeps secrets out and persists STT data and model cache", async () => {
  const dockerfile = await readFile(path.join(root, "Dockerfile"), "utf8");
  const dockerignore = await readFile(path.join(root, ".dockerignore"), "utf8");
  const compose = await readFile(path.join(root, "compose.yaml"), "utf8");

  assert.match(dockerfile, /FROM python:3\.11-slim-bookworm/);
  assert.match(dockerfile, /ffmpeg/);
  assert.match(dockerfile, /libgomp1/);
  assert.match(dockerfile, /SONICBRIEF_DATA_DIR=\/data/);
  assert.match(dockerfile, /HF_HOME=\/data\/huggingface/);
  assert.match(dockerfile, /VOLUME \["\/data"\]/);
  assert.match(dockerfile, /ENTRYPOINT \["python", "\/app\/sonicbrief_mcp\.py"\]/);

  assert.match(dockerignore, /backend\/\.env/);
  assert.match(dockerignore, /^data$/m);
  assert.match(dockerignore, /^\.venv$/m);

  assert.match(compose, /sonicbrief-data:\/data/);
  assert.match(compose, /GEMINI_API_KEY/);
  assert.doesNotMatch(compose, /ports:/);
});
