import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

import "../app/globals.css";
import { SonicBriefApp } from "../app/sonicbrief";

// Static single-page build of the SonicBrief UI for the desktop app. It talks
// to the API on the same origin (see vite.desktop.config.ts), so it needs no
// Next.js/vinext server at runtime.
createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <SonicBriefApp />
  </StrictMode>,
);
