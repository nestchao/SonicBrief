# SonicBrief

SonicBrief is a localhost-only media-to-transcript workspace and MCP server. It accepts public media links from YouTube, Bilibili, Vimeo, TikTok, X/Twitter, SoundCloud, and Twitch, plus local audio/video files. Local `faster-whisper` transcription is the core service; Gemini fallback and built-in summaries are optional. Transcripts, optional summaries, and job history are stored in SQLite.

## What is included

- Public media URLs from YouTube, Bilibili, Vimeo, TikTok, X/Twitter, SoundCloud, and Twitch
- Local `faster-whisper` transcription with timestamps
- Whisper model choices: Turbo, Large v3, Distil-Large v3, and Small
- Media duration limit defaults to 12 hours and can be changed with `MAX_MEDIA_HOURS` in `backend/.env`
- RTX GPU detection with automatic CPU fallback
- Optional Gemini transcription fallback when explicitly requested
- Optional Gemini summaries; MCP agents can instead summarize the transcript with their own model
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

SonicBrief's MCP mode is **STT-first**. The default agent workflow is:

```text
AI agent
  -> SonicBrief MCP over stdio
  -> auto-started local FastAPI backend
  -> yt-dlp / FFmpeg / local faster-whisper
  -> timestamped transcript
  -> agent summarizes with its own model
```

MCP URL/file submissions default to **local Whisper only**: `allow_gemini_fallback=false` and `generate_gemini_summary=false`. This means a normal MCP transcription does not need a Gemini key. The agent can page through `get_transcript` and summarize the transcript itself.

If the user wants SonicBrief's built-in Gemini summary, configure a Gemini API key and explicitly call `generate_summary_with_gemini`, or submit a job with `generate_gemini_summary=true`. Cloud transcription fallback is also opt-in.

Whisper does **not** run inside the MCP process. The MCP bridge starts/reuses one local FastAPI backend, and that backend owns the processing queue, Whisper CPU/GPU work, yt-dlp/FFmpeg, and SQLite history. If the GUI/backend is already running, MCP reuses it. If it is not running, MCP starts it automatically and shuts down the backend process it owns when the MCP session ends.

### Recommended MCP installation: Docker

For headless agent use, Docker avoids installing Python, FFmpeg, yt-dlp, or the Python packages manually.

Build once:

```powershell
docker build -t sonicbrief-mcp:local .
```

Verify the image and automatic backend startup:

```powershell
docker run --rm -v sonicbrief-data:/data sonicbrief-mcp:local --self-test
```

A healthy result reports `"ok": true`, `"ffmpeg_available": true`, and `"audio_decode_available": true`. This self-test starts the backend automatically, checks it, performs a tiny local faster-whisper/PyAV audio decode to catch dependency incompatibilities, then exits.

Then configure the MCP host to launch Docker:

```text
command: docker
args:
  run
  --rm
  -i
  -v
  sonicbrief-data:/data
  sonicbrief-mcp:local
```

The `sonicbrief-data` volume persists:
- SQLite job/transcript history
- downloaded Whisper/Hugging Face model cache
- application cache

The FastAPI backend stays inside the same container and is not published to the host network. The first transcription for a Whisper model may take longer while its model files are downloaded; later container runs reuse the model from the Docker volume.

The included `compose.yaml` provides the same persistent setup for users who prefer Compose:

```powershell
docker compose build
docker compose run --rm -T sonicbrief-mcp --self-test
```

No Gemini key is required when the connected AI agent will summarize the transcript. To enable SonicBrief's optional Gemini features in Docker, pass `GEMINI_API_KEY` into the container through your MCP host/environment instead of baking it into the image.

For local files, the container must be able to see the file. Mount a read-only media folder, for example:

```text
-v C:\Users\you\Videos:/media:ro
```

and give `submit_local_file` a container path such as `/media/lecture.mp4`.

### NVIDIA GPU Docker image

The default `Dockerfile` remains the portable CPU image. NVIDIA users can build the dedicated CUDA image:

```powershell
docker build -f Dockerfile.gpu -t sonicbrief-mcp:gpu .
```

The GPU image is based on NVIDIA CUDA 12.8 with cuDNN and runs the same SonicBrief MCP/backend code. Verify GPU passthrough and CTranslate2 detection:

```powershell
docker run --rm --gpus all `
  -v sonicbrief-data:/data `
  sonicbrief-mcp:gpu --self-test
```

A successful GPU setup should report:

```json
{
  "ok": true,
  "backend_started_by_mcp": true,
  "ffmpeg_available": true,
  "audio_decode_available": true,
  "cuda_available": true,
  "device": "NVIDIA GPU"
}
```

Then configure an MCP host to launch the GPU image:

```text
command: docker
args:
  run
  --rm
  -i
  --gpus
  all
  -v
  sonicbrief-data:/data
  sonicbrief-mcp:gpu
```

The CPU and GPU images share the same `sonicbrief-data` volume, so job history and downloaded model files persist when switching between them.

Compose users can use the GPU profile:

```powershell
docker compose --profile gpu build sonicbrief-mcp-gpu
docker compose --profile gpu run --rm -T sonicbrief-mcp-gpu --self-test
```

If `nvidia-smi` works inside an NVIDIA CUDA container but SonicBrief still reports `cuda_available: false`, the remaining issue is inside the CUDA/cuDNN/CTranslate2 runtime rather than Docker GPU passthrough.

### Native MCP installation

Docker is optional. If SonicBrief is already installed natively, configure the MCP host with:

```text
command: C:\path\to\SonicBrief\.venv\Scripts\python.exe
args:    C:\path\to\SonicBrief\sonicbrief_mcp.py
```

You do **not** need to run `start.bat` first. The MCP process checks `127.0.0.1:7860` and starts `backend/app.py` automatically when needed. Set `SONICBRIEF_AUTO_START_BACKEND=0` only if you intentionally want to manage the backend yourself.

Available MCP tools include:
- `get_health`
- `submit_media_url` and `submit_media_urls`
- `submit_local_file`
- `get_job`, `list_jobs`, and `search_jobs`
- `get_transcript` with offset/limit pagination
- `get_summary` for an already stored built-in summary
- `generate_summary_with_gemini` for explicit Gemini summarization
- `cancel_job`
- `delete_job`

Long-running transcription remains job-based: submit first, check `get_job`, then retrieve transcript pages when the job completes. The MCP call does not remain open for the entire video.

### Privacy model

Local Whisper keeps STT audio processing on the computer/container. If the connected AI agent summarizes the transcript using a cloud model, the transcript may be sent to that model provider. If Gemini fallback or `generate_summary_with_gemini` is explicitly used, the relevant audio/transcript is sent to Google's API.

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
