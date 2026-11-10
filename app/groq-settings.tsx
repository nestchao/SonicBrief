"use client";

import { useEffect, useMemo, useState } from "react";
import { Check, Eye, EyeOff, KeyRound, LoaderCircle, RefreshCw } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://127.0.0.1:7860";

type GroqModelOption = {
  id: string;
  label: string;
  description: string;
};

type GroqSettingsResponse = {
  configured: boolean;
  model: string;
  models: GroqModelOption[];
};

type Props = {
  onConfiguredChange?: (configured: boolean) => void;
};

export function GroqSettings({ onConfiguredChange }: Props) {
  const [apiKey, setApiKey] = useState("");
  const [configured, setConfigured] = useState(false);
  const [model, setModel] = useState("whisper-large-v3-turbo");
  const [models, setModels] = useState<GroqModelOption[]>([]);
  const [showKey, setShowKey] = useState(false);
  const [busy, setBusy] = useState(false);
  const [testing, setTesting] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [messageType, setMessageType] = useState<"error" | "success">("success");

  const selectedModel = useMemo(
    () => models.find((item) => item.id === model),
    [model, models],
  );

  async function refresh() {
    try {
      const response = await fetch(API_BASE + "/api/settings/groq");
      if (!response.ok) return;
      const data = await response.json() as GroqSettingsResponse;
      const isConfigured = Boolean(data.configured);
      setConfigured(isConfigured);
      if (data.model) setModel(data.model);
      if (Array.isArray(data.models) && data.models.length) setModels(data.models);
      onConfiguredChange?.(isConfigured);
    } catch {}
  }

  useEffect(() => { void refresh(); }, []);

  function handleModelChange(value: string) {
    setModel(value);
    setMessage(null);
  }

  async function verifyKey(keyOverride?: string) {
    const response = await fetch(API_BASE + "/api/settings/groq/models", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(keyOverride?.trim() ? { api_key: keyOverride.trim() } : {}),
    });
    const data = await response.json().catch(() => ({})) as Partial<GroqSettingsResponse> & { detail?: string };
    if (!response.ok) throw new Error(data.detail ?? "Could not verify the Groq key.");
    if (Array.isArray(data.models) && data.models.length) setModels(data.models);
    if (data.model) setModel(data.model);
  }

  async function saveSettings() {
    if (!apiKey.trim() && !configured) {
      setMessageType("error");
      setMessage("Enter your Groq API key first.");
      return;
    }
    setBusy(true);
    setMessage(null);
    try {
      if (apiKey.trim()) await verifyKey(apiKey.trim());
      const response = await fetch(API_BASE + "/api/settings/groq", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          model,
          ...(apiKey.trim() ? { api_key: apiKey.trim() } : {}),
        }),
      });
      const data = await response.json().catch(() => ({})) as Partial<GroqSettingsResponse> & { detail?: string };
      if (!response.ok) throw new Error(data.detail ?? "Could not save Groq settings.");

      const isConfigured = Boolean(data.configured);
      setConfigured(isConfigured);
      if (data.model) setModel(data.model);
      onConfiguredChange?.(isConfigured);
      setApiKey("");
      setShowKey(false);
      setMessageType("success");
      setMessage(`Groq settings saved. Using ${selectedModel?.label ?? data.model ?? model}.`);
    } catch (error) {
      setMessageType("error");
      setMessage(error instanceof Error ? error.message : "Could not save Groq settings.");
    } finally {
      setBusy(false);
    }
  }

  async function testSettings() {
    if (!apiKey.trim() && !configured) {
      setMessageType("error");
      setMessage("Enter your Groq API key first.");
      return;
    }
    setTesting(true);
    setMessage(null);
    try {
      const response = await fetch(API_BASE + "/api/settings/groq/test", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          ...(apiKey.trim() ? { api_key: apiKey.trim() } : {}),
        }),
      });
      const data = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(data.detail ?? "Groq connection failed.");
      setMessageType("success");
      setMessage("Connection successful. This key can transcribe with Groq.");
    } catch (error) {
      setMessageType("error");
      setMessage(error instanceof Error ? error.message : "Groq connection failed.");
    } finally {
      setTesting(false);
    }
  }

  async function clearKey() {
    if (!window.confirm("Remove the saved Groq API key from this computer?")) return;
    setBusy(true);
    setMessage(null);
    try {
      const response = await fetch(API_BASE + "/api/settings/groq", { method: "DELETE" });
      const data = await response.json().catch(() => ({})) as Partial<GroqSettingsResponse> & { detail?: string };
      if (!response.ok) throw new Error(data.detail ?? "Could not remove the API key.");
      setConfigured(false);
      if (data.model) setModel(data.model);
      onConfiguredChange?.(false);
      setMessageType("success");
      setMessage("Groq API key removed. Your model preference was kept.");
    } catch (error) {
      setMessageType("error");
      setMessage(error instanceof Error ? error.message : "Could not remove the API key.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div>
      <div className={configured ? "sonic-settings-connection is-connected" : "sonic-settings-connection"}>
        <span className="sonic-settings-connection-icon"><Check /></span>
        <div>
          <strong>{configured ? "Groq connected" : "Groq not connected"}</strong>
          <small>{configured ? "Your key is stored locally and never returned to the browser." : "Add a key to enable Groq cloud transcription."}</small>
        </div>
      </div>

      <div className="sonic-settings-section">
        <div className="sonic-settings-section-heading">
          <div>
            <Label htmlFor="groq-api-key">API key</Label>
            <p>{configured ? "Leave this blank to keep the saved key." : "Paste a key from console.groq.com."}</p>
          </div>
        </div>

        <div className="sonic-settings-key-row">
          <input
            id="groq-api-key"
            className="sonic-settings-key-input"
            type={showKey ? "text" : "password"}
            autoComplete="off"
            spellCheck={false}
            placeholder={configured ? "Saved key will be kept" : "Paste your Groq API key (gsk_…)"}
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
        <p className="sonic-settings-help">Stored locally in <code>backend/.env</code>. Audio you transcribe with Groq is uploaded to Groq. SonicBrief never returns the saved key to the interface.</p>
      </div>

      <div className="sonic-settings-section">
        <div className="sonic-settings-section-heading">
          <div>
            <Label htmlFor="groq-model">Groq model</Label>
            <p>Turbo is faster and cheaper; large-v3 is the quality alternative.</p>
          </div>
          <Button
            type="button"
            variant="ghost"
            size="sm"
            className="sonic-settings-refresh-models"
            onClick={() => verifyKey(apiKey.trim() || undefined).then(() => { setMessageType("success"); setMessage("Groq key verified."); }).catch((error) => { setMessageType("error"); setMessage(error instanceof Error ? error.message : "Could not verify the Groq key."); })}
            disabled={(!apiKey.trim() && !configured) || busy || testing}
          >
            <RefreshCw />
            Verify key
          </Button>
        </div>

        <Select value={model} onValueChange={handleModelChange} disabled={busy || testing || models.length === 0}>
          <SelectTrigger id="groq-model" className="sonic-settings-model-trigger">
            <SelectValue placeholder="Choose a Groq model" />
          </SelectTrigger>
          <SelectContent className="sonic-settings-model-menu" position="popper" align="start">
            {models.map((item) => (
              <SelectItem key={item.id} value={item.id}>{item.label}</SelectItem>
            ))}
          </SelectContent>
        </Select>

        <div className="sonic-settings-model-detail">
          <div>
            <strong>{(selectedModel?.label ?? model) || "No model loaded"}</strong>
            {model && <code>{model}</code>}
          </div>
          <span>{selectedModel?.description ?? "Whisper Large v3 Turbo is the default option."}</span>
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
  );
}
