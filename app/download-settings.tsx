"use client";

import { useEffect, useRef, useState } from "react";
import { Check, CloudDownload, FileKey, LoaderCircle, Trash2, TriangleAlert, Upload } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://127.0.0.1:7860";

type Choice = { value: string; label: string; note?: string };

type DownloadSettings = {
  mode: string;
  label: string;
  choices: Choice[];
  installed_browsers: string[];
  cookie_file_present: boolean;
  cookie_file_path: string;
  last_good_source: string | null;
  yt_dlp_version: string | null;
  kept_rows?: number;
  removed?: boolean;
};

type TestAttempt = { source: string; ok: boolean; error: string | null };
type TestResult = { ok: boolean; used: string | null; attempts: TestAttempt[] };

export function DownloadSettings() {
  const [settings, setSettings] = useState<DownloadSettings | null>(null);
  const [selected, setSelected] = useState("auto");
  const [busy, setBusy] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [test, setTest] = useState<TestResult | null>(null);
  const fileInput = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    fetch(API_BASE + "/api/settings/downloads")
      .then((response) => response.json())
      .then((data: DownloadSettings) => {
        setSettings(data);
        setSelected(data.mode);
      })
      .catch(() => setError("Could not read the download settings from the local server."));
  }, []);

  async function saveMode() {
    setBusy("save");
    setError(null);
    setMessage(null);
    try {
      const response = await fetch(API_BASE + "/api/settings/downloads", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ mode: selected }),
      });
      const data = (await response.json()) as DownloadSettings & { detail?: string };
      if (!response.ok) throw new Error(data.detail || "Saving failed.");
      setSettings(data);
      setSelected(data.mode);
      setMessage(`Saved. ${data.label}`);
    } catch (problem) {
      setError(problem instanceof Error ? problem.message : "Saving failed.");
    } finally {
      setBusy(null);
    }
  }

  async function uploadFile(file: File) {
    setBusy("upload");
    setError(null);
    setMessage(null);
    try {
      const body = new FormData();
      body.append("file", file);
      const response = await fetch(API_BASE + "/api/settings/downloads/cookies-file", { method: "POST", body });
      const data = (await response.json()) as DownloadSettings & { detail?: string; kept_rows?: number };
      if (!response.ok) throw new Error(data.detail || "That file could not be used.");
      setSettings(data);
      setMessage(`Imported ${data.kept_rows ?? 0} YouTube/Google cookies. They stay on this computer.`);
    } catch (problem) {
      setError(problem instanceof Error ? problem.message : "That file could not be used.");
    } finally {
      setBusy(null);
      if (fileInput.current) fileInput.current.value = "";
    }
  }

  async function removeFile() {
    setBusy("remove");
    setError(null);
    setMessage(null);
    try {
      const response = await fetch(API_BASE + "/api/settings/downloads/cookies-file", { method: "DELETE" });
      const data = (await response.json()) as DownloadSettings;
      setSettings(data);
      setMessage("Removed the imported cookies.");
    } catch {
      setError("Could not remove the cookies file.");
    } finally {
      setBusy(null);
    }
  }

  async function runTest() {
    setBusy("test");
    setError(null);
    setMessage(null);
    setTest(null);
    try {
      const response = await fetch(API_BASE + "/api/settings/downloads/test", { method: "POST" });
      const data = (await response.json()) as TestResult;
      setTest(data);
    } catch {
      setError("The test could not run.");
    } finally {
      setBusy(null);
    }
  }

  const current = settings?.choices.find((choice) => choice.value === selected);

  return (
    <div className="sonic-downloads">
      <div className="sonic-downloads-intro">
        <CloudDownload />
        <div>
          <h3>YouTube “confirm you’re not a bot”</h3>
          <p>
            YouTube challenges some networks and lets others through. When it does, SonicBrief needs the cookies of a
            browser that is signed in to YouTube. Automatic mode tries no cookies first, then Firefox, then an imported
            cookies.txt, then the Chromium browsers — and remembers whatever worked.
          </p>
        </div>
      </div>

      <div className="sonic-downloads-field">
        <label htmlFor="cookie-mode">Cookie source</label>
        <Select value={selected} onValueChange={setSelected}>
          <SelectTrigger id="cookie-mode">
            <SelectValue placeholder="Choose a source" />
          </SelectTrigger>
          <SelectContent>
            {(settings?.choices ?? []).map((choice) => (
              <SelectItem key={choice.value} value={choice.value}>
                {choice.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        {current?.note && <p className="sonic-extension-hint">{current.note}</p>}
        <p className="sonic-extension-hint">
          Detected on this computer:{" "}
          <strong>{settings?.installed_browsers.length ? settings.installed_browsers.join(", ") : "no known browser"}</strong>
          {settings?.last_good_source ? ` · last working source: ${settings.last_good_source}` : ""}
          {settings?.yt_dlp_version ? ` · yt-dlp ${settings.yt_dlp_version}` : ""}
        </p>
      </div>

      <Button onClick={() => void saveMode()} disabled={busy !== null || !settings || selected === settings.mode}>
        {busy === "save" ? <LoaderCircle className="animate-spin" /> : <Check />} Save
      </Button>

      <div className="sonic-downloads-file">
        <div className="sonic-downloads-file-head">
          <FileKey />
          <div>
            <strong>cookies.txt</strong>
            <p>
              Works with any browser, and is the only option if none of yours is signed in.{" "}
              {settings?.cookie_file_present ? "A file is imported." : "No file imported yet."}
            </p>
          </div>
        </div>

        <div className="sonic-extension-actions">
          <input
            ref={fileInput}
            type="file"
            accept=".txt,text/plain"
            className="sonic-downloads-file-input"
            onChange={(event) => {
              const file = event.target.files?.[0];
              if (file) void uploadFile(file);
            }}
          />
          <Button variant="outline" size="sm" onClick={() => fileInput.current?.click()} disabled={busy !== null}>
            <Upload /> {settings?.cookie_file_present ? "Replace file" : "Import file"}
          </Button>
          {settings?.cookie_file_present && (
            <Button variant="ghost" size="sm" onClick={() => void removeFile()} disabled={busy !== null}>
              <Trash2 /> Remove
            </Button>
          )}
        </div>

        <details className="sonic-downloads-howto">
          <summary>How to export cookies.txt</summary>
          <ol>
            <li>Open a private/incognito window and sign in to YouTube there.</li>
            <li>Visit youtube.com so the session cookie is fresh, then stay on that tab.</li>
            <li>
              Use a “Get cookies.txt” style extension for your browser and export for this site only.
            </li>
            <li>Close the private window afterwards, and delete the export once imported here.</li>
          </ol>
          <p>
            Treat the file like a password: it grants access to that Google account. It is stored under the app’s data
            folder on this computer, is never uploaded anywhere, and is excluded from git.
          </p>
        </details>
      </div>

      <div className="sonic-downloads-test">
        <Button variant="outline" size="sm" onClick={() => void runTest()} disabled={busy !== null}>
          {busy === "test" ? <LoaderCircle className="animate-spin" /> : <CloudDownload />} Test YouTube access
        </Button>
        {test && (
          <div className="sonic-downloads-test-result">
            <p>
              {test.ok ? (
                <span className="is-ok">Working: {test.used}</span>
              ) : (
                <span className="is-bad">
                  <TriangleAlert /> YouTube refused every source available on this computer.
                </span>
              )}
            </p>
            <ul>
              {test.attempts.map((attempt) => (
                <li key={attempt.source}>
                  {attempt.ok ? "✓" : "✕"} {attempt.source}
                  {attempt.error ? ` — ${attempt.error}` : ""}
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>

      {message && <p className="sonic-extension-hint" role="status">{message}</p>}
      {error && <p className="sonic-extension-warning" role="alert">{error}</p>}
    </div>
  );
}
