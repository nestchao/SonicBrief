"use client";

import { useEffect, useMemo, useState } from "react";
import { Check, Eye, EyeOff, KeyRound, LoaderCircle, Settings, X } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://127.0.0.1:7860";

type GeminiModelOption = {
  id: string;
  label: string;
  description: string;
};

type GeminiSettingsResponse = {
  configured: boolean;
  model: string;
  models: GeminiModelOption[];
};

type Props = {
  onConfiguredChange?: (configured: boolean) => void;
};

const FALLBACK_MODELS: GeminiModelOption[] = [
  { id: "gemini-3.8-flash", label: "Gemini 3.8 Flash", description: "Recommended · latest balanced model" },
  { id: "gemini-3.5-flash", label: "Gemini 3.5 Flash", description: "Balanced speed and quality" },
  { id: "gemini-3.5-flash-lite", label: "Gemini 3.5 Flash-Lite", description: "Fastest and lowest-cost option" },
];

export function GeminiSettings({ onConfiguredChange }: Props) {
  const [open, setOpen] = useState(false);
  const [apiKey, setApiKey] = useState("");
  const [configured, setConfigured] = useState(false);
  const [model, setModel] = useState("gemini-3.8-flash");
  const [models, setModels] = useState<GeminiModelOption[]>(FALLBACK_MODELS);
  const [showKey, setShowKey] = useState(false);
  const [busy, setBusy] = useState(false);
  const [testing, setTesting] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [messageType, setMessageType] = useState<"error" | "success">("success");

  const selectedModel = useMemo(
    () => models.find((item) => item.id === model) ?? FALLBACK_MODELS.find((item) => item.id === model),
    [model, models],
  );

  const refresh = async () => {
    try {
      const response = await fetch(API_BASE + "/api/settings/gemini");
      if (!response.ok) return;
      const data = await response.json() as GeminiSettingsResponse;
      setConfigured(Boolean(data.configured));
      if (data.model) setModel(data.model);
      if (Array.isArray(data.models) && data.models.length) setModels(data.models);
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

  function handleModelChange(value: string) {
    setModel(value);
    setMessage(null);
  }

  async function testSettings() {
    if (!apiKey.trim() && !configured) {
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
        body: JSON.stringify({
          model,
          ...(apiKey.trim() ? { api_key: apiKey.trim() } : {}),
        }),
      });
      const data = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(data.detail ?? "Gemini connection failed.");
      setMessageType("success");
      setMessage(`Connection successful with ${selectedModel?.label ?? model}.`);
    } catch (error) {
      setMessageType("error");
      setMessage(error instanceof Error ? error.message : "Gemini connection failed.");
    } finally {
      setTesting(false);
    }
  }

  async function saveSettings() {
    if (!apiKey.trim() && !configured) {
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
        body: JSON.stringify({
          model,
          ...(apiKey.trim() ? { api_key: apiKey.trim() } : {}),
        }),
      });
      const data = await response.json().catch(() => ({})) as Partial<GeminiSettingsResponse> & { detail?: string };
      if (!response.ok) throw new Error(data.detail ?? "Could not save Gemini settings.");
      setConfigured(Boolean(data.configured));
      if (data.model) setModel(data.model);
      if (Array.isArray(data.models) && data.models.length) setModels(data.models);
      onConfiguredChange?.(Boolean(data.configured));
      setApiKey("");
      setShowKey(false);
      setMessageType("success");
      setMessage(`Gemini settings saved. Using ${selectedModel?.label ?? model}.`);
    } catch (error) {
      setMessageType("error");
      setMessage(error instanceof Error ? error.message : "Could not save Gemini settings.");
    } finally {
      setBusy(false);
    }
  }

  async function clearKey() {
    if (!window.confirm("Remove the saved Gemini API key from this computer?")) return;
    setBusy(true);
    setMessage(null);
    try {
      const response = await fetch(API_BASE + "/api/settings/gemini", { method: "DELETE" });
      const data = await response.json().catch(() => ({})) as Partial<GeminiSettingsResponse> & { detail?: string };
      if (!response.ok) throw new Error(data.detail ?? "Could not remove the API key.");
      setConfigured(false);
      if (data.model) setModel(data.model);
      onConfiguredChange?.(false);
      setMessageType("success");
      setMessage("Gemini API key removed. Your model preference was kept.");
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
        <div
          className="sonic-settings-backdrop"
          role="presentation"
          onMouseDown={(event) => { if (event.target === event.currentTarget) setOpen(false); }}
        >
          <section className="sonic-settings-dialog" role="dialog" aria-modal="true" aria-labelledby="gemini-settings-title">
            <div className="sonic-settings-header">
              <div>
                <p className="sonic-result-eyebrow"><KeyRound />GOOGLE AI STUDIO</p>
                <h2 id="gemini-settings-title">Gemini settings</h2>
                <p>Connect your own API key and choose the Gemini model SonicBrief uses for cloud features.</p>
              </div>
              <button type="button" className="sonic-settings-close" aria-label="Close settings" onClick={() => setOpen(false)}><X /></button>
            </div>

            <div className="sonic-settings-body">
              <div className={configured ? "sonic-settings-connection is-connected" : "sonic-settings-connection"}>
                <span className="sonic-settings-connection-icon"><Check /></span>
                <div>
                  <strong>{configured ? "Google AI Studio connected" : "Google AI Studio not connected"}</strong>
                  <small>{configured ? "Your saved key stays on this computer." : "Add an API key to enable summaries and cloud fallback."}</small>
                </div>
              </div>

              <div className="sonic-settings-section">
                <div className="sonic-settings-section-heading">
                  <div>
                    <Label htmlFor="gemini-api-key">API key</Label>
                    <p>{configured ? "Leave this blank to keep the saved key." : "Paste a key from Google AI Studio."}</p>
                  </div>
                </div>

                <div className="sonic-settings-key-row">
                  <input
                    id="gemini-api-key"
                    className="sonic-settings-key-input"
                    type={showKey ? "text" : "password"}
                    autoComplete="off"
                    spellCheck={false}
                    placeholder={configured ? "Saved key will be kept" : "Paste your Google AI Studio API key"}
                    value={apiKey}
                    onChange={(event) => {
                      setApiKey(event.target.value);
                      setMessage(null);
                    }}
                  />
                  <button type="button" className="sonic-settings-eye" aria-label={showKey ? "Hide API key" : "Show API key"} onClick={() => setShowKey((value) => !value)}>
                    {showKey ? <EyeOff /> : <Eye />}
                  </button>
                </div>
                <p className="sonic-settings-help">Stored locally in <code>backend/.env</code>. SonicBrief never returns the saved key to the interface.</p>
              </div>

              <div className="sonic-settings-section">
                <div className="sonic-settings-section-heading">
                  <div>
                    <Label htmlFor="gemini-model">Gemini model</Label>
                    <p>Used for both cloud transcription fallback and summaries.</p>
                  </div>
                </div>

                <Select value={model} onValueChange={handleModelChange} disabled={busy || testing}>
                  <SelectTrigger id="gemini-model" className="sonic-settings-model-trigger">
                    <SelectValue placeholder="Choose a Gemini model" />
                  </SelectTrigger>
                  <SelectContent className="sonic-settings-model-menu" position="popper" align="start">
                    {models.map((item) => (
                      <SelectItem key={item.id} value={item.id}>{item.label}</SelectItem>
                    ))}
                  </SelectContent>
                </Select>

                <div className="sonic-settings-model-detail">
                  <strong>{selectedModel?.label ?? model}</strong>
                  <span>{selectedModel?.description ?? "Available for Gemini-powered SonicBrief features."}</span>
                </div>
              </div>

              {message && (
                <p
                  className={messageType === "success" ? "sonic-message sonic-settings-message" : "sonic-error sonic-settings-message"}
                  role={messageType === "success" ? "status" : "alert"}
                >
                  {message}
                </p>
              )}

              <div className="sonic-settings-footer">
                <div>
                  {configured && (
                    <Button variant="ghost" className="sonic-settings-clear" onClick={() => void clearKey()} disabled={busy || testing}>
                      Remove API key
                    </Button>
                  )}
                </div>
                <div className="sonic-settings-actions">
                  <Button variant="outline" onClick={() => void testSettings()} disabled={testing || busy || (!apiKey.trim() && !configured)}>
                    {testing ? <LoaderCircle className="animate-spin" /> : <Check />} Test connection
                  </Button>
                  <Button onClick={() => void saveSettings()} disabled={busy || testing || (!apiKey.trim() && !configured)}>
                    {busy ? <LoaderCircle className="animate-spin" /> : <KeyRound />} Save changes
                  </Button>
                </div>
              </div>
            </div>
          </section>
        </div>
      )}
    </>
  );
}
