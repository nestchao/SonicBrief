/**
 * Bilingual overlay.
 *
 * English is authored directly in the HTML so it is indexable and works with
 * JavaScript disabled. This module keeps a snapshot of every English string and
 * swaps in Simplified Chinese from `assets/i18n/zh.json` on demand.
 *
 * Markup contract:
 *   <h2 data-i18n="section.key">English text</h2>
 *   <meta data-i18n="meta.description" data-i18n-attr="content" content="...">
 *
 * Values may contain inline markup (<em>, <strong>, <span class="cite">). They
 * are inserted through a template plus a scrubber that drops script-like nodes
 * and event-handler attributes.
 */

const STORAGE_KEY = "sb-lang";
const DICT_URL = new URL("../i18n/zh.json", import.meta.url);
const BLOCKED = "script,iframe,object,embed,style,link,meta,base";
const SUPPORTED = ["en", "zh"];

function setMarkup(target, html) {
  if (target.tagName === "TITLE") {
    target.textContent = html.replace(/<[^>]+>/g, "");
    return;
  }

  const template = document.createElement("template");
  template.innerHTML = html;
  template.content.querySelectorAll(BLOCKED).forEach((node) => node.remove());
  template.content.querySelectorAll("*").forEach((node) => {
    for (const attribute of [...node.attributes]) {
      if (/^on/i.test(attribute.name)) node.removeAttribute(attribute.name);
    }
  });
  target.replaceChildren(template.content);
}

export function initI18n() {
  const root = document.documentElement;
  const buttons = [...document.querySelectorAll("[data-lang-toggle] [data-lang]")];
  const snapshot = new WeakMap();

  let dictionary = null;
  let lang = "en";

  function collect() {
    for (const element of document.querySelectorAll("[data-i18n]")) {
      if (snapshot.has(element)) continue;
      const attr = element.getAttribute("data-i18n-attr");
      snapshot.set(element, { attr, en: attr ? element.getAttribute(attr) : element.innerHTML });
    }
  }

  function resolveInitial() {
    const requested = new URLSearchParams(location.search).get("lang");
    if (SUPPORTED.includes(requested)) return requested;
    try {
      const stored = localStorage.getItem(STORAGE_KEY);
      if (SUPPORTED.includes(stored)) return stored;
    } catch (error) {
      /* storage can be blocked; English is the safe default */
    }
    return "en";
  }

  async function load() {
    if (dictionary) return dictionary;
    try {
      const response = await fetch(DICT_URL, { credentials: "same-origin" });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      dictionary = await response.json();
    } catch (error) {
      console.warn("[SonicBrief] Chinese translations unavailable, staying in English.", error);
      dictionary = {};
    }
    return dictionary;
  }

  function syncRoot() {
    root.lang = lang === "zh" ? "zh-CN" : "en";
    root.dataset.lang = lang;
    root.classList.toggle("lang-zh", lang === "zh");
    buttons.forEach((button) => {
      const active = button.dataset.lang === lang;
      button.classList.toggle("is-active", active);
      button.setAttribute("aria-pressed", String(active));
    });
  }

  function apply(next, { write = true } = {}) {
    lang = next;
    syncRoot();
    if (!write) return;

    collect();
    const useChinese = next === "zh" && dictionary && Object.keys(dictionary).length > 0;

    for (const element of document.querySelectorAll("[data-i18n]")) {
      const record = snapshot.get(element);
      if (!record) continue;
      const key = element.getAttribute("data-i18n");
      const value = useChinese ? dictionary[key] : record.en;
      if (typeof value !== "string") continue;
      if (record.attr) element.setAttribute(record.attr, value);
      else setMarkup(element, value);
    }

    try {
      localStorage.setItem(STORAGE_KEY, next);
    } catch (error) {
      /* ignore */
    }

    document.dispatchEvent(new CustomEvent("sb:lang", { detail: { lang: next } }));
  }

  async function setLang(next) {
    if (!SUPPORTED.includes(next)) return;
    if (next === "zh" && !dictionary) await load();
    apply(next);

    const url = new URL(location.href);
    if (next === "zh") url.searchParams.set("lang", "zh");
    else url.searchParams.delete("lang");
    history.replaceState(null, "", url);
  }

  buttons.forEach((button) => {
    button.addEventListener("click", () => setLang(button.dataset.lang));
  });

  lang = resolveInitial();
  if (lang === "zh") {
    load().then(() => apply("zh"));
  } else {
    apply("en", { write: false });
  }

  return {
    get lang() {
      return lang;
    },
    setLang,
    /** Forget an element's English snapshot, e.g. after its copy changed. */
    reset(element) {
      snapshot.delete(element);
    },
    /** Re-apply the current language, e.g. after a label changed its key. */
    refresh() {
      return apply(lang);
    },
  };
}
