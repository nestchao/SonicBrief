/**
 * Site behaviour. Everything here is an enhancement: the page is readable,
 * navigable and downloadable with this file blocked.
 */

import { DEMO } from "./demo-data.js";
import { initI18n } from "./i18n.js";
import { initGitHub } from "./github.js";

const $ = (selector, scope = document) => scope.querySelector(selector);
const $$ = (selector, scope = document) => [...scope.querySelectorAll(selector)];

/* ------------------------------------------------------------------ theme */

function initTheme() {
  const root = document.documentElement;
  const button = $("[data-theme-toggle]");
  const media = window.matchMedia("(prefers-color-scheme: dark)");

  const stored = () => {
    try {
      return localStorage.getItem("sb-theme");
    } catch (error) {
      return null;
    }
  };

  const set = (theme) => {
    root.dataset.theme = theme;
    const meta = document.querySelector('meta[name="theme-color"]:not([media])');
    if (meta) meta.setAttribute("content", theme === "dark" ? "#08080B" : "#FCFCFD");
  };

  button?.addEventListener("click", () => {
    const next = root.dataset.theme === "dark" ? "light" : "dark";
    set(next);
    try {
      localStorage.setItem("sb-theme", next);
    } catch (error) {
      /* ignore */
    }
  });

  media.addEventListener("change", (event) => {
    if (!stored()) set(event.matches ? "dark" : "light");
  });
}

/* ----------------------------------------------------------------- header */

function initHeader() {
  const header = $("[data-header]");
  if (!header) return;

  const update = () => header.classList.toggle("is-stuck", window.scrollY > 8);
  update();
  window.addEventListener("scroll", update, { passive: true });
}

function initNav() {
  const toggle = $("[data-nav-toggle]");
  const panel = $("[data-nav-panel]");
  if (!toggle || !panel) return;

  const close = () => {
    panel.hidden = true;
    toggle.setAttribute("aria-expanded", "false");
    toggle.setAttribute("aria-label", "Open menu");
  };

  toggle.addEventListener("click", () => {
    const open = panel.hidden;
    panel.hidden = !open;
    toggle.setAttribute("aria-expanded", String(open));
    toggle.setAttribute("aria-label", open ? "Close menu" : "Open menu");
  });

  panel.addEventListener("click", (event) => {
    if (event.target.closest("a")) close();
  });

  document.addEventListener("keydown", (event) => {
    if (event.key !== "Escape" || panel.hidden) return;
    close();
    toggle.focus();
  });

  window.matchMedia("(min-width: 861px)").addEventListener("change", (event) => {
    if (event.matches) close();
  });
}

function initScrollSpy() {
  const links = $$(".nav__link");
  const sections = links
    .map((link) => document.querySelector(link.getAttribute("href")))
    .filter(Boolean);
  if (!sections.length || !("IntersectionObserver" in window)) return;

  const setActive = (id) => {
    for (const link of links) {
      if (link.getAttribute("href") === `#${id}`) link.setAttribute("aria-current", "true");
      else link.removeAttribute("aria-current");
    }
  };

  const observer = new IntersectionObserver(
    (entries) => {
      const visible = entries
        .filter((entry) => entry.isIntersecting)
        .sort((a, b) => b.intersectionRatio - a.intersectionRatio)[0];
      if (visible) setActive(visible.target.id);
    },
    { rootMargin: "-30% 0px -55% 0px", threshold: [0.05, 0.3, 0.6] },
  );

  sections.forEach((section) => observer.observe(section));
}

/* ---------------------------------------------------------------- reveals */

function initReveal() {
  const items = $$("[data-reveal]");
  if (!items.length || !("IntersectionObserver" in window)) return;

  document.body.classList.add("reveal-on");

  const observer = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue;
        entry.target.classList.add("is-in");
        observer.unobserve(entry.target);
      }
    },
    { rootMargin: "0px 0px -6% 0px", threshold: 0.08 },
  );

  items.forEach((item, index) => {
    item.style.setProperty("--reveal-delay", `${Math.min(index * 70, 280)}ms`);
    observer.observe(item);
  });

  // Anything still hidden after load (e.g. restored mid-page) should not stay blank.
  window.setTimeout(() => {
    for (const item of items) {
      const box = item.getBoundingClientRect();
      if (box.top < window.innerHeight * 0.9) item.classList.add("is-in");
    }
  }, 2400);
}

/* ------------------------------------------------------------------- tabs */

function initTabs(root) {
  const tabs = $$('[role="tab"]', root);
  if (!tabs.length) return;

  const panels = tabs.map((tab) => document.getElementById(tab.getAttribute("aria-controls")));
  const captions = $$("[data-tour-caption]", root);

  // Cross-faded panels are stacked with opacity, so they cannot use the hidden
  // attribute; they are marked with aria-hidden instead.
  panels.forEach((panel) => {
    if (panel && panel.dataset.panel === "fade") panel.hidden = false;
  });

  const select = (index, focus = false) => {
    tabs.forEach((tab, i) => {
      const active = i === index;
      tab.classList.toggle("is-active", active);
      tab.setAttribute("aria-selected", String(active));
      tab.tabIndex = active ? 0 : -1;

      const panel = panels[i];
      if (!panel) return;
      panel.classList.toggle("is-active", active);
      if (panel.dataset.panel === "fade") panel.setAttribute("aria-hidden", String(!active));
      else panel.hidden = !active;
    });

    captions.forEach((caption, i) => {
      caption.hidden = i !== index;
    });

    if (focus) tabs[index].focus();
  };

  tabs.forEach((tab, index) => {
    tab.addEventListener("click", () => select(index));
    tab.addEventListener("keydown", (event) => {
      const last = tabs.length - 1;
      let next = null;
      if (event.key === "ArrowRight") next = index === last ? 0 : index + 1;
      if (event.key === "ArrowLeft") next = index === 0 ? last : index - 1;
      if (event.key === "Home") next = 0;
      if (event.key === "End") next = last;
      if (next === null) return;
      event.preventDefault();
      select(next, true);
    });
  });

  select(0);
}

/* ------------------------------------------------------------- copy + toast */

let toastTimer;

function showToast(message) {
  const toast = $("[data-toast]");
  if (!toast) return;
  toast.textContent = message;
  toast.classList.add("is-visible");
  window.clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => toast.classList.remove("is-visible"), 1900);
}

async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch (error) {
    const field = document.createElement("textarea");
    field.value = text;
    field.setAttribute("readonly", "");
    field.style.position = "fixed";
    field.style.opacity = "0";
    document.body.append(field);
    field.select();
    const ok = document.execCommand("copy");
    field.remove();
    return ok;
  }
}

function initCopy() {
  for (const button of $$("[data-copy]")) {
    button.addEventListener("click", async () => {
      const target = document.querySelector(button.dataset.copy);
      if (!target) return;
      const ok = await copyText(target.innerText);
      const label = $("[data-copied-label]");
      if (ok) showToast(label ? label.textContent : "Copied");
    });
  }
}

/* ----------------------------------------------------------------- sample */

function initSample() {
  const root = $("[data-sample]");
  if (!root) return;

  const body = $("[data-sample-body]", root);
  const buttons = $$("[data-sample-style] button", root);
  let style = "standard";

  const render = () => {
    const lang = document.documentElement.dataset.lang === "zh" ? "zh" : "en";
    const entry = DEMO[lang]?.[style];
    if (!entry || !body) return;
    body.innerHTML = entry.html;
  };

  for (const button of buttons) {
    button.addEventListener("click", () => {
      style = button.dataset.style;
      for (const other of buttons) {
        const active = other === button;
        other.classList.toggle("is-active", active);
        other.setAttribute("aria-pressed", String(active));
      }
      render();
    });
  }

  document.addEventListener("sb:lang", render);

  if (document.documentElement.dataset.lang === "zh") render();
}

/* -------------------------------------------------------- platform CTA */

function initPrimaryCta(i18n) {
  const button = $("[data-cta-primary]");
  const label = $("[data-cta-label]", button || document);
  if (!button || !label) return;

  const agent = navigator.userAgent;
  let key = null;
  let text = null;

  if (/Android/i.test(agent)) {
    button.dataset.download = "android";
    key = "hero.ctaAndroid";
    text = "Get it for Android";
  } else if (!/Windows/i.test(agent)) {
    button.dataset.download = "windows-gpu";
    key = "hero.ctaDesktop";
    text = "Download for desktop";
  }

  if (!key) return;

  label.setAttribute("data-i18n", key);
  label.textContent = text;
  i18n.reset(label);
  i18n.refresh();
}

/* ------------------------------------------------------------------- init */

function initYear() {
  const year = String(new Date().getFullYear());
  for (const node of $$("[data-year]")) node.textContent = year;
}

function boot() {
  const i18n = initI18n();

  initTheme();
  initHeader();
  initNav();
  initScrollSpy();
  initReveal();
  initCopy();
  initYear();

  for (const tablist of $$('[role="tablist"]')) {
    initTabs(tablist.closest("[data-tabs]") || tablist.parentElement || document);
  }

  initSample();
  initPrimaryCta(i18n);
  initGitHub();
}

if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", boot, { once: true });
} else {
  boot();
}
