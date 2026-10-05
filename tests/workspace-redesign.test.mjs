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
