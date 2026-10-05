import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("..", import.meta.url));

test("workspace redesign keeps summary Markdown semantics intact", async () => {
  const source = await readFile(path.join(root, "app", "sonicbrief.tsx"), "utf8");

  assert.match(source, /content\.replace\(\/\\r\\n\?\/g, "\\n"\)\.trim\(\)\.split\("\\n"\)/);
  assert.match(source, /\^\\s\*\(#{1,6}\)\\s\+\(\.\+\)\$/);
  assert.match(source, /sonic-summary-heading/);
  assert.match(source, /sonic-timestamp-chip/);
  assert.doesNotMatch(source, /split\("\\\\n"\)/);
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
});

test("backend persists creator metadata from downloaded media", async () => {
  const storage = await readFile(path.join(root, "backend", "storage.py"), "utf8");
  const pipeline = await readFile(path.join(root, "backend", "pipeline.py"), "utf8");

  assert.match(storage, /creator_name TEXT/);
  assert.match(storage, /ALTER TABLE jobs ADD COLUMN creator_name TEXT/);
  assert.match(storage, /"creator_name"/);
  assert.match(pipeline, /info\.get\("channel"\) or info\.get\("uploader"\) or info\.get\("uploader_id"\)/);
  assert.match(pipeline, /creator_name=metadata\.get\("creator_name"\)/);
});

test("summary prompt requests structured Markdown", async () => {
  const pipeline = await readFile(path.join(root, "backend", "pipeline.py"), "utf8");

  assert.match(pipeline, /Return clean Markdown only/);
  assert.match(pipeline, /Use ## headings/);
  assert.match(pipeline, /\*\*bold\*\*/);
  assert.match(pipeline, /\[123\.4s\]/);
});
