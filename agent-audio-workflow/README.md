# Agent Audio Collection Workflow

This folder provides a small, framework-neutral workflow for an Agent that receives one public YouTube or Bilibili URL and collects its audio locally.

## Quick use

From the project root, run:

```powershell
.\agent-audio-workflow\collect_audio.ps1 -Url "https://youtu.be/example"
```

The default output format is MP3. Choose another supported format if needed:

```powershell
.\agent-audio-workflow\collect_audio.ps1 -Url "https://www.bilibili.com/video/BV..." -Format wav
```

The command prints a JSON manifest to stdout. Audio is saved under `agent-audio-workflow\collected\`.

## Agent integration

Use [`AGENT_WORKFLOW.md`](AGENT_WORKFLOW.md) as the Agent instruction, and expose the command as a tool named `collect_audio`. The only required input is `url`; the Agent can return the JSON manifest directly to the user.

## Requirements

- The project `.venv` must exist.
- `yt-dlp[default]` is installed by `backend\requirements.txt`; this includes the matching `yt-dlp-ejs` challenge scripts.
- Node.js 22+ is used as yt-dlp's JavaScript challenge runtime when it is available.
- FFmpeg must be available in `PATH`, or pass `-FfmpegLocation` to the PowerShell wrapper.

If YouTube reports “Sign in to confirm you're not a bot”, sign in to YouTube in Chrome or Edge, close that browser, then pass the matching `-Browser chrome` or `-Browser edge`. Cookies are read directly from the local browser and are not copied into this project.

This workflow only accepts public YouTube and Bilibili URLs supplied by the user. The user is responsible for having permission to download and use the media.

## Collect, transcribe, and summarize with Gemini

Add your Gemini API key to `backend\.env` first:

```env
GEMINI_API_KEY=your_key_here
GEMINI_TRANSCRIPTION_MODEL=gemini-3.5-flash-lite
GEMINI_SUMMARY_MODEL=gemini-3.5-flash-lite
```

Then run the full workflow:

```powershell
.\agent-audio-workflow\collect_and_transcribe.ps1 -Url "https://youtu.be/example"
```

It saves the audio in `collected\`, the transcript as both TXT and JSON in `transcripts\`, and a Markdown summary in `summaries\`. Use `-SkipSummary` when only the transcript is needed.

Gemini receives the downloaded audio for transcription. The Flash-Lite workflow requests timestamped JSON, but those timestamps are model estimates. For specialist speech-to-text capabilities such as word-level timestamps and built-in diarization, set `GEMINI_TRANSCRIPTION_MODEL=gemini-3.5-transcribe` instead.
