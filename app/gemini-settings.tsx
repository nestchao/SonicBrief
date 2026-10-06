"use client";

import { useEffect, useMemo, useState } from "react";
import { createPortal } from "react-dom";
import { Check, Eye, EyeOff, KeyRound, LoaderCircle, RefreshCw, Settings, X } from "lucide-react";

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

type GeminiModelsResponse = {
  model: string;
  models: GeminiModelOption[];
};

type Props = {
  onConfiguredChange?: (configured: boolean) => void;
};

export function GeminiSettings({ onConfiguredChange }: Props) {
  const [open, setOpen] = useState(false);
  const [apiKey, setApiKey] = useState("");
  const [configured, setConfigured] = useState(false);
  const [model, setModel] = useState("");
  const [models, setModels] = useState<GeminiModelOption[]>([]);
  const [showKey, setShowKey] = useState(false);
  const [busy, setBusy] = useState(false);
  const [testing, setTesting] = useState(false);
  const [loadingModels, setLoadingModels] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [messageType, setMessageType] = useState<"error" | "success">("success");

  const selectedModel = useMemo(
    () => models.find((item) => item.id === model),
    [model, models],
  );

  const hasKeyForDiscovery = Boolean(apiKey.trim() || configured);
  const needsModelRefresh = Boolean(apiKey.trim()) && models.length === 0;

  async function loadModels(keyOverride?: string, quiet = false) {
    setLoadingModels(true);
    if (!quiet) setMessage(null);
    try {
      const response = await fetch(API_BASE + "/api/settings/gemini/models", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(keyOverride?.trim() ? { api_key: keyOverride.trim() } : {}),
      });
      const data = await response.json().catch(() => ({})) as Partial<GeminiModelsResponse> & { detail?: string };
      if (!response.ok) throw new Error(data.detail ?? "Could not load Gemini models.");
      if (!Array.isArray(data.models) || data.models.length === 0) {
        throw new Error("Google returned no compatible Gemini models.");
      }

      setModels(data.models);
      const availableIds = new Set(data.models.map((item) => item.id));
      setModel((current) => {
        if (current && availableIds.has(current)) return current;
        if (data.model && availableIds.has(data.model)) return data.model;
        return data.models?.[0]?.id ?? "";
      });

      if (!quiet) {
        setMessageType("success");
        setMessage(`Loaded ${data.models.length} Gemini models available to this API key.`);
      }
    } catch (error) {
      setModels([]);
      setMessageType("error");
      setMessage(error instanceof Error ? error.message : "Could not load Gemini models.");
    } finally {
      setLoadingModels(false);
    }
  }

  async function refresh(loadAvailableModels = false) {
    try {
      const response = await fetch(API_BASE + "/api/settings/gemini");
      if (!response.ok) return;
      const data = await response.json() as GeminiSettingsResponse;
      const isConfigured = Boolean(data.configured);
      setConfigured(isConfigured);
      if (data.model) setModel(data.model);
      onConfiguredChange?.(isConfigured);

      if (loadAvailableModels && isConfigured) {
        await loadModels(undefined, true);
      }
    } catch {}
  }

  useEffect(() => { void refresh(); }, []);

  function openSettings() {
    setMessage(null);
    setApiKey("");
    setShowKey(false);
    setOpen(true);
    void refresh(true);
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
    if (!model || needsModelRefresh) {
      setMessageType("error");
      setMessage("Load the models available to this API key before testing.");
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
    if (!model || needsModelRefresh) {
      setMessageType("error");
      setMessage("Load the models available to this API key before saving.");
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

      const isConfigured = Boolean(data.configured);
      setConfigured(isConfigured);
      if (data.model) setModel(data.model);
      onConfiguredChange?.(isConfigured);
      setApiKey("");
      setShowKey(false);
      setMessageType("success");
      setMessage(`Gemini settings saved. Using ${selectedModel?.label ?? data.model ?? model}.`);
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
      setModels([]);
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

      {open && createPortal(
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
                <p>Connect your own API key and choose from the Gemini models Google makes available to that key.</p>
              </div>
              <button type="button" className="sonic-settings-close" aria-label="Close settings" onClick={() => setOpen(false)}><X /></button>
            </div>

            <div className="sonic-settings-body">
              <div className={configured ? "sonic-settings-connection is-connected" : "sonic-settings-connection"}>
                <span className="sonic-settings-connection-icon"><Check /></span>
                <div>
                  <strong>{configured ? "Google AI Studio connected" : "Google AI Studio not connected"}</strong>
                  <small>{configured ? "Your key is stored locally and never returned to the browser." : "Add an API key to enable summaries and Gemini fallback."}</small>
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
                      const nextValue = event.target.value;
                      setApiKey(nextValue);
                      setMessage(null);
                      if (nextValue.trim()) setModels([]);
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
                    <p>Loaded from Google for this API key and filtered to general-purpose text-response models that support <code>generateContent</code>.</p>
                  </div>
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    className="sonic-settings-refresh-models"
                    onClick={() => void loadModels(apiKey.trim() || undefined)}
                    disabled={!hasKeyForDiscovery || loadingModels || busy || testing}
                  >
                    <RefreshCw className={loadingModels ? "animate-spin" : ""} />
                    {loadingModels ? "Loading…" : "Refresh models"}
                  </Button>
                </div>

                <Select value={model} onValueChange={handleModelChange} disabled={busy || testing || loadingModels || models.length === 0}>
                  <SelectTrigger id="gemini-model" className="sonic-settings-model-trigger">
                    <SelectValue placeholder={loadingModels ? "Loading models from Google…" : "Load models to choose"} />
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
                  <span>
                    {loadingModels
                      ? "Checking the models available to this API key…"
                      : selectedModel?.description ?? "Refresh models to get the current text-focused list directly from Google."}
                  </span>
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
                    <Button variant="ghost" className="sonic-settings-clear" onClick={() => void clearKey()} disabled={busy || testing || loadingModels}>
                      Remove API key
                    </Button>
                  )}
                </div>
                <div className="sonic-settings-actions">
                  <Button variant="outline" onClick={() => void testSettings()} disabled={testing || busy || loadingModels || (!apiKey.trim() && !configured) || !model || needsModelRefresh}>
                    {testing ? <LoaderCircle className="animate-spin" /> : <Check />} Test connection
                  </Button>
                  <Button onClick={() => void saveSettings()} disabled={busy || testing || loadingModels || (!apiKey.trim() && !configured) || !model || needsModelRefresh}>
                    {busy ? <LoaderCircle className="animate-spin" /> : <KeyRound />} Save changes
                  </Button>
                </div>
              </div>
            </div>
          </section>
        </div>,
        document.body,
      )}
    </>
  );
}
