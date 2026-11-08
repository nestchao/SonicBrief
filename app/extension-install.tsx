"use client";

import { useEffect, useState } from "react";
import { Check, Copy, ExternalLink, FolderOpen, Puzzle } from "lucide-react";

import { Button } from "@/components/ui/button";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://127.0.0.1:7860";

type BrowserTarget = { name: string; url: string };

type ExtensionInfo = {
  path: string;
  exists: boolean;
  browsers: BrowserTarget[];
};

type Props = {
  /** Rendered inside the sidebar: fewer words, no heading. */
  compact?: boolean;
  onDismiss?: () => void;
};

export function ExtensionInstall({ compact = false, onDismiss }: Props) {
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

  async function call(action: "open-browser" | "open-folder") {
    setBusy(action);
    setError(null);
    try {
      const response = await fetch(`${API_BASE}/api/extension/${action}`, { method: "POST" });
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

  const browser = info?.browsers?.[0];

  return (
    <div className={"sonic-extension" + (compact ? " is-compact" : "")}>
      {!compact && (
        <div className="sonic-extension-head">
          <Puzzle />
          <div>
            <h3>Send videos from Chrome</h3>
            <p>Install the SonicBrief extension so the video you are watching becomes one click.</p>
          </div>
        </div>
      )}

      <div className="sonic-extension-drop">
        <Puzzle />
        <span>{browser ? `Install the extension in ${browser.name.replace(".exe", "")}` : "Install the Chrome extension"}</span>
      </div>

      <ol className="sonic-extension-steps">
        <li>
          <strong>Copy this folder path</strong>
          <code title={info?.path || ""}>{info?.exists ? info.path : info ? info.path + " (not found)" : "locating…"}</code>
          <Button variant="outline" size="sm" onClick={() => void copyPath()} disabled={!info?.exists}>
            {copied ? <Check /> : <Copy />}
            {copied ? "Copied" : "Copy path"}
          </Button>
        </li>
        <li>
          <strong>Open the extensions page</strong>
          <Button variant="outline" size="sm" onClick={() => void call("open-browser")} disabled={!browser || busy === "open-browser"}>
            <ExternalLink />
            {browser ? `Open ${browser.url.replace("://extensions/", "")}://extensions` : "No Chrome found"}
          </Button>
        </li>
        <li>
          <strong>Turn on Developer mode, click “Load unpacked”, and paste the path</strong>
          <Button variant="ghost" size="sm" onClick={() => void call("open-folder")} disabled={!info?.exists || busy === "open-folder"}>
            <FolderOpen />
            Show the folder
          </Button>
        </li>
      </ol>

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
