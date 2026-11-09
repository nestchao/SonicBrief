#!/usr/bin/env node
/**
 * Static checks for `site/`.
 *
 * Catches the failures that are invisible until production: a renamed image, a
 * dead anchor, a translation that never landed, an absolute path that breaks on
 * a GitHub Pages project URL, a page with two <h1>s.
 *
 * Run: node scripts/check_site.mjs
 */

import { readFileSync, existsSync, statSync, readdirSync } from "node:fs";
import path from "node:path";

const ROOT = path.resolve(import.meta.dirname, "..");
const SITE = path.join(ROOT, "site");

const PAGES = ["index.html", "licenses.html", "404.html"];
const SIZE_BUDGETS = {
  "assets/css/styles.css": 52 * 1024,
  "assets/js": 46 * 1024,
  "assets/fonts": 160 * 1024,
};
/** Keys translated by script rather than present in the markup. */
const SCRIPT_ONLY_KEYS = new Set(["hero.ctaAndroid", "hero.ctaDesktop", "code.copied"]);

const problems = [];
const notes = [];

const fail = (message) => problems.push(message);
const note = (message) => notes.push(message);
const rel = (file) => path.relative(ROOT, file).replaceAll("\\", "/");

/* ------------------------------------------------------------------ helpers */

function read(file) {
  return readFileSync(path.join(SITE, file), "utf8");
}

function tags(html) {
  const out = [];
  const tagPattern = /<([a-zA-Z][\w:-]*)((?:"[^"]*"|'[^']*'|[^>"'])*)>/g;
  let match;
  while ((match = tagPattern.exec(html))) {
    const attrs = {};
    const attrPattern = /([a-zA-Z_:][-a-zA-Z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)')/g;
    let attr;
    while ((attr = attrPattern.exec(match[2]))) {
      attrs[attr[1].toLowerCase()] = attr[2] ?? attr[3] ?? "";
    }
    out.push({ name: match[1].toLowerCase(), attrs, raw: match[0] });
  }
  return out;
}

function isExternal(reference) {
  return /^(https?:|mailto:|tel:|data:|javascript:|\/\/)/i.test(reference);
}

function localTarget(reference) {
  const clean = reference.split("#")[0].split("?")[0];
  return clean ? decodeURIComponent(clean) : "";
}

function checkFileReference(reference, page, what) {
  if (!reference || isExternal(reference) || reference.startsWith("#")) return;
  if (reference.startsWith("/")) {
    fail(`${page}: ${what} uses a root-absolute path "${reference}" — Pages serves this from /SonicBrief/`);
    return;
  }
  const target = path.join(SITE, localTarget(reference));
  if (!existsSync(target)) fail(`${page}: ${what} points at a missing file "${reference}"`);
}

/* ------------------------------------------------------------------- pages */

const idsByPage = new Map();
const keys = new Set();

for (const page of PAGES) {
  const file = path.join(SITE, page);
  if (!existsSync(file)) {
    fail(`${page}: missing`);
    continue;
  }

  const html = read(page);
  const list = tags(html);
  const ids = new Map();

  if (!/<html[^>]+lang="/.test(html)) fail(`${page}: <html> has no lang attribute`);

  for (const { name, attrs } of list) {
    if (attrs.id) {
      if (ids.has(attrs.id)) fail(`${page}: duplicate id "${attrs.id}"`);
      ids.set(attrs.id, true);
    }

    if (attrs["data-i18n"]) keys.add(attrs["data-i18n"]);

    for (const attribute of ["href", "src"]) {
      if (attrs[attribute]) checkFileReference(attrs[attribute], page, `<${name} ${attribute}>`);
    }

    if (attrs.srcset) {
      for (const candidate of attrs.srcset.split(",")) {
        const url = candidate.trim().split(/\s+/)[0];
        if (url) checkFileReference(url, page, `<${name} srcset>`);
      }
    }

    if (name === "img") {
      if (!("alt" in attrs)) fail(`${page}: <img> without alt (${attrs.src || "inline"})`);
      if (!attrs.width || !attrs.height) fail(`${page}: <img> without width/height (${attrs.src || "inline"})`);
    }
  }

  // In-page anchors.
  for (const { name, attrs } of list) {
    if (name !== "a" || !attrs.href || !attrs.href.startsWith("#")) continue;
    const id = attrs.href.slice(1);
    if (id && !ids.has(id)) fail(`${page}: anchor "${attrs.href}" has no matching id`);
  }

  // Exactly one h1.
  const h1s = list.filter((tag) => tag.name === "h1").length;
  if (h1s !== 1) fail(`${page}: expected exactly one <h1>, found ${h1s}`);

  // Title and description.
  const title = /<title[^>]*>([\s\S]*?)<\/title>/i.exec(html)?.[1]?.trim() ?? "";
  if (!title) fail(`${page}: missing <title>`);
  if (title.length > 70) note(`${page}: title is ${title.length} characters (over 60–65 reads as truncation)`);

  const description = /<meta[^>]+name="description"[^>]+content="([^"]*)"/i.exec(html)?.[1] ?? "";
  if (!description) fail(`${page}: missing meta description`);
  else if (description.length > 165) fail(`${page}: meta description is ${description.length} characters (max 165)`);

  // JSON-LD must parse.
  const ldPattern = /<script[^>]+type="application\/ld\+json"[^>]*>([\s\S]*?)<\/script>/gi;
  let ld;
  let ldCount = 0;
  while ((ld = ldPattern.exec(html))) {
    ldCount += 1;
    try {
      JSON.parse(ld[1]);
    } catch (error) {
      fail(`${page}: JSON-LD block ${ldCount} does not parse (${error.message})`);
    }
  }

  idsByPage.set(page, ids);
}

/* --------------------------------------------------------------- references */

for (const page of PAGES) {
  const file = path.join(SITE, page);
  if (!existsSync(file)) continue;
  const html = read(page);

  // Every id referenced by aria-controls / aria-labelledby / for must exist.
  const list = tags(html);
  const ids = idsByPage.get(page) || new Map();
  for (const { name, attrs } of list) {
    for (const attribute of ["aria-controls", "aria-labelledby", "for"]) {
      if (!attrs[attribute]) continue;
      for (const id of attrs[attribute].split(/\s+/)) {
        if (id && !ids.has(id)) fail(`${page}: <${name} ${attribute}="${attrs[attribute]}"> has no matching id`);
      }
    }
  }

  // Mixed content.
  for (const match of html.matchAll(/(?:href|src)="(http:\/\/[^"]+)"/g)) {
    fail(`${page}: insecure reference "${match[1]}"`);
  }
}

/* ---------------------------------------------------------------- translations */

const coverageFile = path.join(SITE, "assets/i18n/zh.json");
let dictionary = {};
if (!existsSync(coverageFile)) {
  fail("assets/i18n/zh.json: missing");
} else {
  try {
    dictionary = JSON.parse(readFileSync(coverageFile, "utf8"));
  } catch (error) {
    fail(`assets/i18n/zh.json: does not parse (${error.message})`);
  }
}

const translated = new Set(Object.keys(dictionary));
for (const key of keys) {
  if (!translated.has(key)) fail(`zh.json: missing translation for "${key}"`);
}
for (const key of translated) {
  if (!keys.has(key) && !SCRIPT_ONLY_KEYS.has(key)) fail(`zh.json: "${key}" is not used by any element`);
}

/* ---------------------------------------------------------------- html basics */

for (const key of translated) {
  if (typeof dictionary[key] !== "string" || dictionary[key].trim() === "") {
    fail(`zh.json: "${key}" is empty`);
  }
}

/* ------------------------------------------------------------ required files */

for (const file of ["robots.txt", "sitemap.xml", "site.webmanifest", "assets/img/og.png", "assets/img/favicon.svg"]) {
  if (!existsSync(path.join(SITE, file))) fail(`${file}: missing`);
}

// sitemap entries must be absolute and resolvable
if (existsSync(path.join(SITE, "sitemap.xml"))) {
  const sitemap = read("sitemap.xml");
  const locs = [...sitemap.matchAll(/<loc>([^<]+)<\/loc>/g)].map((match) => match[1]);
  if (!locs.length) fail("sitemap.xml: no <loc> entries");
  for (const loc of locs) {
    if (!loc.startsWith("https://")) fail(`sitemap.xml: "${loc}" is not absolute`);
    const slug = loc.replace("https://nestchao.github.io/SonicBrief/", "") || "index.html";
    const target = path.join(SITE, slug);
    if (!existsSync(target)) fail(`sitemap.xml: "${loc}" has no local file`);
  }
  if (!sitemap.includes('hreflang="zh-Hans"')) note("sitemap.xml: no Chinese alternate");
}

// manifest must parse and point at real icons
if (existsSync(path.join(SITE, "site.webmanifest"))) {
  const manifest = JSON.parse(read("site.webmanifest"));
  for (const icon of manifest.icons || []) {
    if (!existsSync(path.join(SITE, icon.src))) fail(`site.webmanifest: icon "${icon.src}" is missing`);
  }
}

/* -------------------------------------------------------------------- budget */

for (const [target, budget] of Object.entries(SIZE_BUDGETS)) {
  const absolute = path.join(SITE, target);
  if (!existsSync(absolute)) {
    fail(`${target}: missing`);
    continue;
  }
  let size = 0;
  if (statSync(absolute).isDirectory()) {
    for (const entry of readdirSync(absolute)) size += statSync(path.join(absolute, entry)).size;
  } else {
    size = statSync(absolute).size;
  }
  if (size > budget) fail(`${target}: ${(size / 1024).toFixed(1)} KB exceeds the ${(budget / 1024).toFixed(0)} KB budget`);
  else note(`${target}: ${(size / 1024).toFixed(1)} KB of ${(budget / 1024).toFixed(0)} KB`);
}

const imageBytes = readdirSync(path.join(SITE, "assets/img")).reduce(
  (total, entry) => total + statSync(path.join(SITE, "assets/img", entry)).size,
  0,
);
note(`assets/img: ${(imageBytes / 1024 / 1024).toFixed(2)} MB total`);

/* ------------------------------------------------------------------- report */

if (problems.length) {
  console.error(`\n✗ ${problems.length} problem${problems.length === 1 ? "" : "s"}\n`);
  for (const problem of problems) console.error(`  · ${problem}`);
  if (notes.length) {
    console.error("\n  notes");
    for (const message of notes) console.error(`  · ${message}`);
  }
  process.exit(1);
}

console.log(`✓ site/ checks passed — ${PAGES.length} pages, ${keys.size} translation keys, ${rel(SITE)}`);
for (const message of notes) console.log(`  · ${message}`);
