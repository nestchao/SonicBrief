import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("..", import.meta.url));

test("Gemini settings expose API-key and model controls", async () => {
  const source = await readFile(path.join(root, "app", "gemini-settings.tsx"), "utf8");

  assert.match(source, /Google AI Studio API key/);
  assert.match(source, /Gemini model/);
  assert.match(source, /gemini-3\.8-flash/);
  assert.match(source, /gemini-3\.5-flash/);
  assert.match(source, /gemini-3\.5-flash-lite/);
  assert.match(source, /Leave this blank to keep the saved key/);
  assert.match(source, /position="popper"/);
  assert.match(source, /Test connection/);
  assert.match(source, /Save changes/);
});

test("backend keeps Gemini secrets private and uses one selected model", async () => {
  const settings = await readFile(path.join(root, "backend", "gemini_settings.py"), "utf8");
  const app = await readFile(path.join(root, "backend", "app.py"), "utf8");
  const pipeline = await readFile(path.join(root, "backend", "pipeline.py"), "utf8");
  const select = await readFile(path.join(root, "components", "ui", "select.tsx"), "utf8");

  assert.match(settings, /DEFAULT_GEMINI_MODEL = "gemini-3\.8-flash"/);
  assert.match(settings, /def save_gemini_model/);
  assert.match(app, /"models": gemini_settings\.list_gemini_models\(\)/);
  assert.match(app, /payload\.get\("model", gemini_settings\.get_gemini_model\(\)\)/);
  assert.match(app, /storage\.save_summary\([^\n]*gemini_settings\.get_gemini_model\(\)\)/);
  assert.match(pipeline, /gemini_settings\.get_gemini_api_key\(\)/);
  assert.match(pipeline, /gemini_settings\.get_gemini_model\(\)/);
  assert.doesNotMatch(app, /"api_key"\s*:/);
  assert.match(select, /z-\[150\]/);
});
