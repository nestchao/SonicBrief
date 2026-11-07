import test from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { PLATFORM_LABELS } from "../extension/detect.js";
import { SUPPORTED_DOMAINS, URL_PATTERNS } from "../extension/lib.js";

const extensionDir = join(dirname(fileURLToPath(import.meta.url)), "..", "extension");
const read = (name) => readFileSync(join(extensionDir, name), "utf8");

test("popup.js only touches elements that exist in popup.html", () => {
  const html = read("popup.html");
  const script = read("popup.js");
  const ids = new Set([...html.matchAll(/\bid="([^"]+)"/g)].map((match) => match[1]));
  const used = [...script.matchAll(/\$\("([^"]+)"\)/g)].map((match) => match[1]);
  assert.ok(used.length > 5, "expected the popup to look up several elements");
  for (const id of new Set(used)) {
    assert.ok(ids.has(id), `popup.js uses #${id}, but popup.html defines no such id`);
  }
});

test("popup.html loads every file it references", () => {
  const html = read("popup.html");
  const refs = [...html.matchAll(/(?:src|href)="([^"]+)"/g)].map((match) => match[1]);
  for (const ref of refs) {
    assert.ok(existsSync(join(extensionDir, ref)), `popup.html references missing file: ${ref}`);
  }
});

test("manifest is MV3 and every declared file exists", () => {
  const manifest = JSON.parse(read("manifest.json"));
  assert.equal(manifest.manifest_version, 3);
  const files = [
    manifest.action?.default_popup,
    manifest.background?.service_worker,
    ...Object.values(manifest.icons ?? {}),
    ...Object.values(manifest.action?.default_icon ?? {}),
  ].filter(Boolean);
  for (const file of files) {
    assert.ok(existsSync(join(extensionDir, file)), `manifest references missing file: ${file}`);
  }
});

test("every supported domain has a context-menu pattern", () => {
  assert.equal(URL_PATTERNS.length, SUPPORTED_DOMAINS.length * 2);
  for (const domain of SUPPORTED_DOMAINS) {
    assert.ok(URL_PATTERNS.includes(`*://*.${domain}/*`), `no wildcard pattern for ${domain}`);
  }
});

test("the three newly supported platforms have labels and domains", () => {
  for (const platform of ["douyin", "instagram", "facebook"]) {
    assert.ok(PLATFORM_LABELS[platform], `${platform} needs a label`);
  }
  for (const domain of ["douyin.com", "instagram.com", "facebook.com", "fb.watch"]) {
    assert.ok(SUPPORTED_DOMAINS.includes(domain), `${domain} must be in SUPPORTED_DOMAINS`);
  }
});

test("background and lib use the detector rather than domain alone", () => {
  assert.match(read("lib.js"), /import \{ detectVideoPage \} from "\.\/detect\.js"/);
  assert.match(read("background.js"), /import \{ detectVideoPage \} from "\.\/detect\.js"/);
  assert.doesNotMatch(read("background.js"), /isSupported/, "background should not fall back to domain-only checks");
});
