import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("..", import.meta.url));

test("Gemini settings load model choices dynamically", async () => {
  const source = await readFile(path.join(root, "app", "gemini-settings.tsx"), "utf8");

  assert.match(source, /Google AI Studio API key/);
  assert.match(source, /Gemini model/);
  assert.match(source, /\/api\/settings\/gemini\/models/);
  assert.match(source, /Refresh models/);
  assert.match(source, /models\.map/);
  assert.match(source, /position="popper"/);
  assert.match(source, /createPortal/);
  assert.match(source, /document\.body/);
  assert.match(source, /sonic-settings-model-detail/);
  assert.match(source, /Test connection/);
  assert.match(source, /Save changes/);
  assert.doesNotMatch(source, /FALLBACK_MODELS/);
  assert.doesNotMatch(source, /gemini-3\.5-flash-lite/);
});

test("backend discovers generation-capable Gemini models for the user's key", async () => {
  const settings = await readFile(path.join(root, "backend", "gemini_settings.py"), "utf8");
  const app = await readFile(path.join(root, "backend", "app.py"), "utf8");
  const pipeline = await readFile(path.join(root, "backend", "pipeline.py"), "utf8");
  const select = await readFile(path.join(root, "components", "ui", "select.tsx"), "utf8");

  assert.match(settings, /def list_gemini_models\(api_key:/);
  assert.match(settings, /client\.models\.list\(\)/);
  assert.match(settings, /supported_actions/);
  assert.match(settings, /"generateContent"/);
  assert.match(settings, /"-transcribe"/);
  assert.match(settings, /"embedding"/);
  assert.match(settings, /"native-audio"/);
  assert.match(settings, /"-tts"/);
  assert.match(settings, /"-image"/);
  assert.match(settings, /"omni"/);
  assert.match(settings, /"customtools"/);
  assert.match(settings, /DEFAULT_GEMINI_MODEL = "gemini-flash-lite-latest"/);
  assert.match(settings, /_LATEST_MODEL_ORDER/);
  assert.match(settings, /_VERSION_PREFIX_PATTERN/);
  assert.match(settings, /-parts\[0\]/);
  assert.match(settings, /preview_rank/);
  assert.match(settings, /def normalize_gemini_model_id/);
  assert.match(app, /@app\.post\("\/api\/settings\/gemini\/models"\)/);
  assert.match(app, /asyncio\.to_thread\(gemini_settings\.list_gemini_models, key\)/);
  assert.match(app, /payload\.get\("model", gemini_settings\.get_gemini_model\(\)\)/);
  assert.match(app, /storage\.save_summary\([^\n]*gemini_settings\.get_gemini_model\(\)\)/);
  assert.match(pipeline, /gemini_settings\.get_gemini_api_key\(\)/);
  assert.match(pipeline, /gemini_settings\.get_gemini_model\(\)/);
  assert.doesNotMatch(app, /"api_key"\s*:/);
  assert.match(select, /z-\[150\]/);
});
