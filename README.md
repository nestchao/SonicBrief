# SonicBrief

SonicBrief is a localhost-only audio transcription and summarization workspace. It accepts public YouTube links, public Bilibili links, and local audio files. It transcribes with `faster-whisper` first, can fall back to Gemini when local quality is poor, adds optional speaker labels, and saves transcripts and summaries in SQLite.

## What is included

- YouTube, Bilibili, and audio-file input
- Local `faster-whisper` transcription with timestamps
- RTX GPU detection with automatic CPU fallback
- Gemini transcription fallback when the local result fails or is low quality
- Simplified Chinese summaries through Gemini
- Optional speaker diarization through `pyannote.audio`
- Local SQLite history and summary regeneration
- TXT, SRT, and JSON export endpoints
- Temporary media deletion after each task

## Windows setup

Requirements:

- Windows 10 or 11
- Python 3.11
- Node.js 22 or newer
- FFmpeg and FFprobe available in `PATH`

Run `setup.bat` once. It creates a Python virtual environment, installs the base transcription dependencies, installs the frontend dependencies, and creates `backend/.env`.

Open `backend/.env` and set:

```env
GEMINI_API_KEY=your_google_ai_studio_key
GEMINI_TRANSCRIPTION_MODEL=gemini-3.5-flash-lite
GEMINI_SUMMARY_MODEL=gemini-3.5-flash-lite
```

Use a model ID that is currently available in your Google AI Studio account. The app still performs local transcription without Gemini, but summary generation and cloud fallback require the key.

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
- API keys stay in `backend/.env` and are never sent to the frontend.
- Temporary downloaded or uploaded audio is deleted after processing.
- Persistent results are stored in `data/sonicbrief.sqlite3`.
- If Gemini fallback or summaries are enabled, the relevant audio or transcript is sent to Google's API.

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
- `GET /api/jobs`
- `GET /api/jobs/{id}`
- `POST /api/jobs/upload`
- `POST /api/jobs/url`
- `POST /api/jobs/{id}/summaries`
- `GET /api/jobs/{id}/export/txt`
- `GET /api/jobs/{id}/export/srt`
- `GET /api/jobs/{id}/export/json`
- `DELETE /api/jobs/{id}`
