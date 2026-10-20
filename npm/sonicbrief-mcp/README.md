# SonicBrief MCP launcher (Windows x64)

This npm package is a **launcher**, not the Python transcription engine. On
first launch, it downloads the corresponding verified portable SonicBrief
runtime from GitHub Releases, caches it under
`%LOCALAPPDATA%\SonicBrief\runtime\`, and starts it over stdio.

Requirements: Node.js 20.19+ and Windows x64. **No Docker, Python or FFmpeg
installation is needed** after the portable runtime has been published.
On slower Windows computers, initial backend startup may need extra time.
The portable launcher allows 90 seconds by default; adjust using the
`SONICBRIEF_BACKEND_STARTUP_TIMEOUT_SECONDS` environment variable (5–300).
Whisper model weights are downloaded separately on first transcription. CPU
transcription is supported in this initial release; GPU support is planned.

Data is persistent across upgrades:

- `%LOCALAPPDATA%\SonicBrief\data\sonicbrief.sqlite3`
- `%LOCALAPPDATA%\SonicBrief\models\`
- `%LOCALAPPDATA%\SonicBrief\cache\`

The package requires a release-generated runtime SHA-256 in
`runtime-manifest.json`. The source-tree manifest has an empty digest and
**cannot install anything** until the GitHub release packaging workflow has
generated it. Do not publish the source-tree npm package directly.

Proposed OpenCode V2 configuration (after npm publication):

```json
{
  "mcp": {
    "servers": {
      "sonicbrief": {
        "type": "local",
        "command": ["npx", "-y", "@nestchao/sonicbrief-mcp@latest"],
        "codemode": false
      }
    }
  }
}
```

If the client cannot launch `npx` directly on Windows, use
`["cmd", "/c", "npx", "-y", "@nestchao/sonicbrief-mcp@latest"]`.

The package handles the local environment but cannot provide compatible
operating system APIs or a CPU with sufficient performance. Never commit
API keys to this repository. Gemini is optional and disabled until configured.

Local packaging verification:

```powershell
cd npm/sonicbrief-mcp
npm test
npm pack --dry-run
```

## License

The SonicBrief launcher is licensed under [MIT](LICENSE).
Bundled third-party runtime components are distributed under their own terms;
see the release notices when available.

## License

The SonicBrief npm launcher is distributed under the [MIT License](LICENSE).
The separately downloaded portable runtime contains third-party software,
including FFmpeg, whose license terms remain separate and must be respected.
