"use client";

import { useEffect, useState } from "react";
import { Check, Eye, EyeOff, KeyRound, LoaderCircle, Settings, X } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://127.0.0.1:7860";

type Props = {
  onConfiguredChange?: (configured: boolean) => void;
};

export function GeminiSettings({ onConfiguredChange }: Props) {
  const [open, setOpen] = useState(false);
  const [apiKey, setApiKey] = useState("");
  const [configured, setConfigured] = useState(false);
  const [showKey, setShowKey] = useState(false);
  const [busy, setBusy] = useState(false);
  const [testing, setTesting] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [messageType, setMessageType] = useState<"error" | "success">("success");

  const refresh = async () => {
    try {
      const response = await fetch(API_BASE + "/api/settings/gemini");
      if (!response.ok) return;
      const data = await response.json() as { configured: boolean };
      setConfigured(Boolean(data.configured));
      onConfiguredChange?.(Boolean(data.configured));
    } catch {}
  };

  useEffect(() => { void refresh(); }, []);

  function openSettings() {
    setMessage(null);
    setApiKey("");
    setShowKey(false);
    setOpen(true);
    void refresh();
  }

  async function testKey() {
    if (!apiKey.trim()) {
      setMessageType("error");
      setMessage("Enter your Google AI Studio API key first.");
      return;
    }
    setTesting(true);
    setMessage(null);
    try {
      const response = await fetch(API_BASE + "/api/settings/gemini/test", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ api_key: apiKey.trim() }),
      });
      const data = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(data.detail ?? "Gemini connection failed.");
      setMessageType("success");
      setMessage("Gemini API key is valid and can be used by SonicBrief.");
    } catch (error) {
      setMessageType("error");
      setMessage(error instanceof Error ? error.message : "Gemini connection failed.");
    } finally {
      setTesting(false);
    }
  }

  async function saveKey() {
    if (!apiKey.trim()) {
      setMessageType("error");
      setMessage("Enter your Google AI Studio API key first.");
      return;
    }
    setBusy(true);
    setMessage(null);
    try {
      const response = await fetch(API_BASE + "/api/settings/gemini", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ api_key: apiKey.trim() }),
      });
      const data = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(data.detail ?? "Could not save the API key.");
      setConfigured(true);
      onConfiguredChange?.(true);
      setApiKey("");
      setShowKey(false);
      setMessageType("success");
      setMessage("API key saved to backend/.env on this computer.");
    } catch (error) {
      setMessageType("error");
      setMessage(error instanceof Error ? error.message : "Could not save the API key.");
    } finally {
      setBusy(false);
    }
  }

  async function clearKey() {
    if (!window.confirm("Remove the saved Gemini API key from backend/.env?")) return;
    setBusy(true);
    setMessage(null);
    try {
      const response = await fetch(API_BASE + "/api/settings/gemini", { method: "DELETE" });
      const data = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(data.detail ?? "Could not remove the API key.");
      setConfigured(false);
      onConfiguredChange?.(false);
      setMessageType("success");
      setMessage("Gemini API key removed.");
    } catch (error) {
      setMessageType("error");
      setMessage(error instanceof Error ? error.message : "Could not remove the API key.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <Button variant="outline" size="sm" className="sonic-settings-button" onClick={openSettings}>
        <Settings /> Gemini Settings
        {configured && <span className="sonic-settings-dot" aria-label="Gemini configured"><Check /></span>}
      </Button>

      {open && (
        <div className="sonic-settings-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) setOpen(false); }}>
          <section className="sonic-settings-dialog" role="dialog" aria-modal="true" aria-labelledby="gemini-settings-title">
            <div className="sonic-settings-header">
              <div>
                <p className="sonic-result-eyebrow"><KeyRound />GOOGLE AI STUDIO</p>
                <h2 id="gemini-settings-title">Gemini API key</h2>
                <p>Use your own Google AI Studio key for summaries and Gemini transcription fallback.</p>
              </div>
              <button type="button" className="sonic-settings-close" aria-label="Close settings" onClick={() => setOpen(false)}><X /></button>
            </div>

            <div className="sonic-settings-body">
              <Label htmlFor="gemini-api-key">API key</Label>
              <div className="sonic-settings-key-row">
                <input
                  id="gemini-api-key"
                  className="sonic-settings-key-input"
                  type={showKey ? "text" : "password"}
                  autoComplete="off"
                  spellCheck={false}
                  placeholder="Paste your Google AI Studio API key"
                  value={apiKey}
                  onChange={(event) => setApiKey(event.target.value)}
                />
                <button type="button" className="sonic-settings-eye" aria-label={showKey ? "Hide API key" : "Show API key"} onClick={() => setShowKey((value) => !value)}>
                  {showKey ? <EyeOff /> : <Eye />}
                </button>
              </div>
              <p className="sonic-settings-help">The key is written only to <code>backend/.env</code> on this computer. It is never returned to the web interface.</p>

              {configured && !apiKey && <div className="sonic-settings-status"><Check /> A Gemini API key is configured.</div>}
              {message && <p className={messageType === "success" ? "sonic-message" : "sonic-error"} role={messageType === "success" ? "status" : "alert"}>{message}</p>}

              <div className="sonic-settings-actions">
                <Button variant="outline" onClick={() => void testKey()} disabled={testing || busy || !apiKey.trim()}>
                  {testing ? <LoaderCircle className="animate-spin" /> : <Check />} Test
                </Button>
                <Button onClick={() => void saveKey()} disabled={busy || testing || !apiKey.trim()}>
                  {busy ? <LoaderCircle className="animate-spin" /> : <KeyRound />} Save key
                </Button>
              </div>

              {configured && <Button variant="ghost" className="sonic-settings-clear" onClick={() => void clearKey()} disabled={busy || testing}>Remove saved API key</Button>}
            </div>
          </section>
        </div>
      )}
    </>
  );
}
