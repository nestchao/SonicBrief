# SonicBrief

SonicBrief is a localhost-only audio transcription and summarization workspace. It accepts public media links from YouTube, Bilibili, Vimeo, TikTok, X/Twitter, SoundCloud, and Twitch, plus local audio/video files. It transcribes with `faster-whisper` first, can fall back to Gemini when local quality is poor, adds optional speaker labels, and saves transcripts and summaries in SQLite.

## What is included

- Public media URLs from YouTube, Bilibili, Vimeo, TikTok, X/Twitter, SoundCloud, and Twitch
- Local `faster-whisper` transcription with timestamps
- Whisper model choices: Turbo, Large v3, Distil-Large v3, and Small
- Media duration limit defaults to 12 hours and can be changed with `MAX_MEDIA_HOURS` in `backend/.env`
- RTX GPU detection with automatic CPU fallback
- Gemini transcription fallback when the local result fails or is low quality
- Simplified Chinese summaries through Gemini
- Optional speaker diarization through `pyannote.audio`
- Local SQLite history with title/creator search and downloaded-media creator metadata
- Dedicated Summary, Transcript, and Details workspace views
- Full-transcript search and one-click copy
- Markdown-formatted summaries with headings, bold text, lists, quotes, and timestamp chips
- Summary presets: Brief, Standard, Detailed, Study Notes, Key Points, and Custom
- Optional per-summary custom instructions, with Standard remembered as the default preset
- TXT, SRT, and JSON export endpoints
- Temporary media deletion after each task
- Local stdio MCP server for AI agents, backed by the same FastAPI job queue and SQLite history

## Windows setup

Requirements:

- Windows 10 or 11
- Python 3.11
- Node.js 22 or newer
- FFmpeg and FFprobe available in `PATH`

Run `setup.bat` once. It creates a Python virtual environment, installs the base transcription dependencies, installs the frontend dependencies, and creates `backend/.env`.

For URL jobs, SonicBrief detects the supported site automatically and downloads public media through `yt-dlp`. Site availability can change when providers update playback or anti-bot rules; private/login-only media is not guaranteed to work.

Double-click `start.bat`. On first launch, open **Gemini Settings** and paste your own Google AI Studio API key. SonicBrief asks Google's Gemini Models API for the models available to that key, keeps Gemini models that support `generateContent`, filters out specialized transcription, TTS, image, Live/audio, embedding, Omni, custom-tools, robotics, computer-use, and research variants, and fills the model dropdown dynamically. This avoids shipping a short hardcoded model list that can become stale.

The API key and selected model are saved to the local `backend/.env` file on that computer; you do not need to edit the file manually. If you paste a different key, use **Refresh models** before testing or saving so the dropdown reflects that key's access. The dropdown puts the `-latest` aliases first (Flash-Lite Latest, Flash Latest, Pro Latest), then sorts numbered releases from newest to oldest, with stable models ahead of preview/experimental variants for the same version.

You can also configure the shared model in `backend/.env` if needed:

```env
GEMINI_MODEL=gemini-flash-lite-latest
```

Legacy `GEMINI_TRANSCRIPTION_MODEL` and `GEMINI_SUMMARY_MODEL` overrides remain supported for existing installs. The app still performs local transcription without Gemini, but summary generation and cloud fallback require the key.

Then double-click `start.bat`. It starts the API at `http://127.0.0.1:7860`, starts the web interface at `http://127.0.0.1:3000`, and opens the interface in your browser.

## Speaker identification

Speaker diarization is optional because its machine-learning packages are large. Install it after the base setup:

```powershell
.\.venv\Scripts\python.exe -m pip install -r backend\requirements-diarization.txt
```

Create a Hugging Face access token, accept the access terms for the configured pyannote model, and add the token to `backend/.env`:

```env
HF_TOKEN=your_hugging_face_token
DIARIZATION_MODEL=pyannote/speaker-diarization-3.1
```

Speaker diarization identifies anonymous voices such as `Speaker 1` and `Speaker 2`; it does not determine a person's real name.

## GPU notes for the RTX 4050

The API health check reports whether CTranslate2 can access CUDA. If it reports CPU mode even though NVIDIA CUDA is installed, the CTranslate2 wheel may expect a different CUDA runtime library version. Check the CTranslate2 compatibility notes for the installed version, or use CPU mode until the matching runtime libraries are installed. SonicBrief uses `int8_float16` on CUDA and `int8` on CPU.

## Local data and privacy

- The API binds to `127.0.0.1`, so it is not exposed to other computers.
- Each user supplies their own Gemini API key; it is saved locally in `backend/.env` and is never returned to the frontend.
- `backend/.env` is local configuration and must never be committed to Git.
- Temporary downloaded or uploaded audio is deleted after processing.
- Persistent results are stored in `data/sonicbrief.sqlite3`.
- If Gemini fallback or summaries are enabled, the relevant audio or transcript is sent to Google's API.

## MCP server for AI agents

SonicBrief includes a local MCP server at `sonicbrief_mcp.py`. It is a lightweight bridge to the same FastAPI backend used by the browser interface:

```text
AI agent
  -> MCP over stdio
  -> SonicBrief API at 127.0.0.1:7860
  -> yt-dlp / FFmpeg / local faster-whisper
  -> SQLite history
  -> Gemini only when summary generation or configured fallback needs it
```

Whisper does **not** run inside the MCP process. The local FastAPI backend owns the processing queue, GPU/CPU Whisper execution, downloads, and persistent history. This prevents the GUI and an AI agent from starting separate Whisper workers.

Run `setup.bat` after pulling the MCP feature so the virtual environment installs the MCP SDK. Then start the SonicBrief backend with `start.bat`, or run the backend by itself:

```powershell
Set-Location backend
..\.venv\Scripts\python.exe app.py
```

Configure an MCP host to launch the server with the same local virtual environment. The exact settings screen differs by host, but the launch values are:

```text
command: C:\path\to\SonicBrief\.venv\Scripts\python.exe
args:    C:\path\to\SonicBrief\sonicbrief_mcp.py
```

The server uses stdio by default and calls only a loopback SonicBrief API. Set `SONICBRIEF_API_BASE` only when the backend uses a different local port; non-loopback addresses are rejected.

Available MCP tools include:

- `get_health`
- `submit_media_url` and `submit_media_urls` (up to 20 URLs, queued in submission order)
- `submit_local_file`
- `get_job`, `list_jobs`, and `search_jobs`
- `get_summary`
- `get_transcript` with offset/limit pagination
- `regenerate_summary` with all SonicBrief summary presets and custom instructions
- `cancel_job`
- `delete_job`

Long-running transcription remains job-based: submit first, then use `get_job` until the job is complete before retrieving the summary/transcript. The MCP server does not hold one tool call open for the full duration of a long video.

Local media submitted through MCP is read by the local MCP process and passed to the local SonicBrief API. If Gemini fallback or summaries are configured, the same privacy behavior as the GUI applies: relevant audio/transcript data may be sent to Google's API.

## Development commands

Frontend:

```powershell
npm run dev -- --host 127.0.0.1 --port 3000 --strictPort
```

Backend:

```powershell
Set-Location backend
..\.venv\Scripts\python.exe app.py
```

Production frontend build:

```powershell
npm run build
```

## Main API routes

- `GET /api/health`
- `GET /api/settings/gemini`
- `POST /api/settings/gemini` (save a local Gemini key and model)
- `POST /api/settings/gemini/models` (load text-focused generation models available to a key)
- `POST /api/settings/gemini/test` (test a key/model without saving it)
- `DELETE /api/settings/gemini` (remove the saved key)
- `GET /api/jobs`
- `GET /api/jobs/{id}`
- `POST /api/jobs/upload`
- `POST /api/jobs/url`
- `POST /api/jobs/{id}/summaries`
- `GET /api/jobs/{id}/export/txt`
- `GET /api/jobs/{id}/export/srt`
- `GET /api/jobs/{id}/export/json`
- `DELETE /api/jobs/{id}`
