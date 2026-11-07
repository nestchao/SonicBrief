import path from "node:path";
import { fileURLToPath } from "node:url";

import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

const root = path.dirname(fileURLToPath(import.meta.url));

// Static build of the UI for the desktop app: `npm run build:desktop-ui`.
// Run it from the repository root so Tailwind scans the right sources.
export default defineConfig({
  root: path.join(root, "desktop-ui"),
  publicDir: path.join(root, "public"),
  plugins: [react()],
  resolve: { alias: { "@": root } },
  css: { postcss: root }, // reuse the repo's postcss.config.mjs (Tailwind v4)
  define: {
    // Same-origin API: the backend serves this UI, so calls become "/api/...".
    "process.env.NEXT_PUBLIC_API_BASE": JSON.stringify(""),
  },
  build: {
    outDir: path.join(root, "build", "desktop-ui"),
    emptyOutDir: true,
  },
});
