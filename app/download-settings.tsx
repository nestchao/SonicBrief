"use client";

import { useEffect, useState } from "react";
import { Check, CloudDownload, LoaderCircle } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://127.0.0.1:7860";

type Choice = { value: string; label: string };

type DownloadSettings = {
  cookies_browser: string;
  label: string;
  choices: Choice[];
};

export function DownloadSettings() {
  const [settings, setSettings] = useState<DownloadSettings | null>(null);
  const [selected, setSelected] = useState("off");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetch(API_BASE + "/api/settings/downloads")
      .then((response) => response.json())
      .then((data: DownloadSettings) => {
        setSettings(data);
        setSelected(data.cookies_browser);
      })
      .catch(() => setError("Could not read the download settings from the local server."));
  }, []);

  async function save() {
    setBusy(true);
    setError(null);
    setMessage(null);
    try {
      const response = await fetch(API_BASE + "/api/settings/downloads", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ cookies_browser: selected }),
      });
      const data = (await response.json()) as DownloadSettings & { detail?: string };
      if (!response.ok) throw new Error(data.detail || "Saving failed.");
      setSettings(data);
      setSelected(data.cookies_browser);
      setMessage(
        data.cookies_browser === "off"
          ? "Saved. Downloads will not use browser cookies."
          : `Saved. Downloads will use ${data.label} cookies.`,
      );
    } catch (problem) {
      setError(problem instanceof Error ? problem.message : "Saving failed.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="sonic-downloads">
      <div className="sonic-downloads-intro">
        <CloudDownload />
        <div>
          <h3>YouTube “confirm you’re not a bot”</h3>
          <p>
            YouTube sometimes refuses downloads that are not signed in. Choosing a browser lets SonicBrief reuse that
            browser’s YouTube session — the browser must be signed in, and it helps if it has watched a video recently.
          </p>
        </div>
      </div>

      <div className="sonic-downloads-field">
        <label htmlFor="cookies-browser">Use cookies from</label>
        <Select value={selected} onValueChange={setSelected}>
          <SelectTrigger id="cookies-browser">
            <SelectValue placeholder="Choose a browser" />
          </SelectTrigger>
          <SelectContent>
            {(settings?.choices ?? []).map((choice) => (
              <SelectItem key={choice.value} value={choice.value}>
                {choice.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <p className="sonic-extension-hint">
          Cookies are read on this computer and never leave it. Leave this off unless YouTube asks you to sign in.
        </p>
      </div>

      {message && <p className="sonic-extension-hint" role="status">{message}</p>}
      {error && <p className="sonic-extension-warning" role="alert">{error}</p>}

      <Button onClick={() => void save()} disabled={busy || !settings || selected === settings.cookies_browser}>
        {busy ? <LoaderCircle className="animate-spin" /> : <Check />} Save download settings
      </Button>
    </div>
  );
}
