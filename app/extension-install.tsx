"use client";

import { useEffect, useState } from "react";
import { Check, Compass, Copy, ExternalLink, FolderOpen, Globe, Puzzle, X } from "lucide-react";

import { Button } from "@/components/ui/button";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://127.0.0.1:7860";

const BROWSER_LABELS: Record<string, string> = {
  "chrome.exe": "Google Chrome",
  "msedge.exe": "Microsoft Edge",
};

type BrowserTarget = { name: string; url: string };

type ExtensionInfo = {
  path: string;
  exists: boolean;
  browsers: BrowserTarget[];
};

type Props = {
  /** Rendered inside the sidebar: fewer words, no heading. */
  compact?: boolean;
  /** First-run panel dismissal ("don't show this again"). */
  onDismiss?: () => void;
  /** Corner ✕ for the compact card. */
  onClose?: () => void;
};

export function ExtensionInstall({ compact = false, onDismiss, onClose }: Props) {
  const [info, setInfo] = useState<ExtensionInfo | null>(null);
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    fetch(API_BASE + "/api/extension")
      .then((response) => response.json())
      .then((data: ExtensionInfo) => {
        if (!cancelled) setInfo(data);
      })
      .catch(() => {
        if (!cancelled) setError("Could not read the extension location from the local server.");
      });
    return () => {
      cancelled = true;
    };
  }, []);

  async function copyPath() {
    if (!info?.path) return;
    try {
      await navigator.clipboard.writeText(info.path);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      setError("Copying failed — select the path and copy it manually.");
    }
  }

  async function call(action: "open-browser" | "open-folder", browserName?: string) {
    setBusy(action);
    setError(null);
    try {
      const query = action === "open-browser" && browserName ? `?name=${encodeURIComponent(browserName)}` : "";
      const response = await fetch(`${API_BASE}/api/extension/${action}${query}`, { method: "POST" });
      if (!response.ok) {
        const detail = (await response.json().catch(() => null)) as { detail?: string } | null;
        throw new Error(detail?.detail || "Windows refused the request.");
      }
    } catch (problem) {
      setError(problem instanceof Error ? problem.message : "That did not work.");
    } finally {
      setBusy(null);
    }
  }

  /** One click a la XDM: put the path on the clipboard and open that browser's extensions page. */
  async function installFor(target: BrowserTarget) {
    await copyPath();
    await call("open-browser", target.name);
  }

  return (
    <div className={"sonic-extension" + (compact ? " is-compact" : "")}>
      {onClose && (
        <button
          type="button"
          className="sonic-extension-close"
          onClick={onClose}
          title="Hide this card (you can reopen it from “Chrome extension setup”)"
          aria-label="Hide the extension setup card"
        >
          <X />
        </button>
      )}
      {!compact && (
        <div className="sonic-extension-head">
          <Puzzle />
          <div>
            <h3>Install the browser extension</h3>
            <p>
              The extension sends the video you are watching to SonicBrief. Chrome needs it loaded once; these five
              steps take about a minute.
            </p>
          </div>
        </div>
      )}

      <ol className="sonic-guide-steps">
        <li>
          <span className="sonic-guide-number">1</span>
          <div className="sonic-guide-body">
            <strong>Open your browser’s extensions page</strong>
            <p>Pick your browser — the page opens and the folder path is copied for you.</p>
            <div className="sonic-browser-grid">
              {info?.browsers?.length ? (
                info.browsers.map((target) => (
                  <button
                    key={target.name}
                    type="button"
                    className="sonic-browser-button"
                    onClick={() => void installFor(target)}
                    disabled={!info.exists || busy === "open-browser"}
                  >
                    {target.name.startsWith("chrome") ? <Compass /> : <Globe />}
                    {BROWSER_LABELS[target.name] || target.name.replace(".exe", "")}
                  </button>
                ))
              ) : (
                <p className="sonic-extension-warning">Chrome or Edge was not found on this computer.</p>
              )}
            </div>
          </div>
        </li>

        <li>
          <span className="sonic-guide-number">2</span>
          <div className="sonic-guide-body">
            <strong>Turn on “Developer mode”</strong>
            <p>It sits in the top-right corner of that page. The switch turns blue.</p>
            <div className="sonic-guide-illustration" aria-hidden="true">
              <span>Developer mode</span>
              <svg viewBox="0 0 44 20" width="44" height="20">
                <rect x="1" y="1" width="42" height="18" rx="9" fill="#7c6cf0" />
                <circle cx="33" cy="10" r="7" fill="#fff" />
              </svg>
            </div>
          </div>
        </li>

        <li>
          <span className="sonic-guide-number">3</span>
          <div className="sonic-guide-body">
            <strong>Click “Load unpacked”</strong>
            <p>Three buttons appear once Developer mode is on — it is the first one, on the left.</p>
            <div className="sonic-guide-illustration" aria-hidden="true">
              <span className="sonic-guide-chip">Load unpacked</span>
              <span className="sonic-guide-chip is-muted">Pack extension</span>
              <span className="sonic-guide-chip is-muted">Update</span>
            </div>
          </div>
        </li>

        <li>
          <span className="sonic-guide-number">4</span>
          <div className="sonic-guide-body">
            <strong>Paste the path and open the folder</strong>
            <p>Chrome asks which folder to load — paste this, press Enter, then choose the folder itself.</p>
            <div className="sonic-extension-path">
              <code title={info?.path || ""}>{info?.exists ? info.path : info ? info.path + " (not found)" : "locating…"}</code>
              <Button variant="outline" size="sm" onClick={() => void copyPath()} disabled={!info?.exists}>
                {copied ? <Check /> : <Copy />}
                {copied ? "Copied" : "Copy"}
              </Button>
            </div>
          </div>
        </li>

        <li>
          <span className="sonic-guide-number">5</span>
          <div className="sonic-guide-body">
            <strong>Pin SonicBrief to the toolbar</strong>
            <p>
              The extension appears in the puzzle-piece menu. Pin it, then open a video and click the icon to
              transcribe.
            </p>
          </div>
        </li>
      </ol>

      <p className="sonic-extension-hint">
        Nothing appeared after step 4? Check that you selected the folder that contains <code>manifest.json</code> —
        not its parent — then reload the extensions page.
      </p>

      <div className="sonic-extension-actions">
        <Button variant="ghost" size="sm" onClick={() => void call("open-folder")} disabled={!info?.exists || busy === "open-folder"}>
          <FolderOpen />
          Show the folder
        </Button>
        {info?.browsers?.[0] && (
          <Button variant="ghost" size="sm" onClick={() => void call("open-browser")} disabled={busy === "open-browser"}>
            <ExternalLink />
            Open extensions page
          </Button>
        )}
      </div>

      {!info?.exists && info && (
        <p className="sonic-extension-warning">
          The extension files were not found in this installation. Reinstall SonicBrief, or point
          <code> SONICBRIEF_EXTENSION_DIR </code> at the <code>extension</code> folder.
        </p>
      )}
      {error && <p className="sonic-extension-warning" role="alert">{error}</p>}

      {onDismiss && (
        <button type="button" className="sonic-extension-dismiss" onClick={onDismiss}>
          Don&apos;t show this again
        </button>
      )}
    </div>
  );
}
