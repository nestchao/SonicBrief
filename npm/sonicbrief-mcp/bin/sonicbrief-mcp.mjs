#!/usr/bin/env node
// Node is the only end-user prerequisite. Heavy Python, STT, and FFmpeg code
// lives in the verified portable Windows release, cached per runtime version.
import { createHash } from 'node:crypto';
import { createReadStream, createWriteStream, existsSync } from 'node:fs';
import { mkdir, mkdtemp, readFile, rename, rm, stat, writeFile } from 'node:fs/promises';
import https from 'node:https';
import os from 'node:os';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const manifestPath = path.resolve(here, '..', 'runtime-manifest.json');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

export function applicationHome(env = process.env) {
  return path.join(env.LOCALAPPDATA || path.join(os.homedir(), 'AppData', 'Local'), 'SonicBrief');
}

export function assertSupported(platform = process.platform, arch = process.arch) {
  if (platform !== 'win32' || arch !== 'x64') {
    throw new Error('SonicBrief portable MCP v0.1 supports Windows x64 only. See GitHub for Docker/native alternatives.');
  }
}

export function validateManifest(manifest) {
  if (manifest.platform !== 'win32' || manifest.arch !== 'x64' || !/^[0-9A-Za-z._-]+$/.test(manifest.runtimeVersion || '')) {
    throw new Error('Invalid SonicBrief runtime manifest.');
  }
  if (!/^[a-f0-9]{64}$/i.test(manifest.sha256 || '')) {
    throw new Error('No verified runtime SHA-256 is packaged. Use the published npm package built from a GitHub Release.');
  }
  const uri = new URL(manifest.url);
  if (uri.protocol !== 'https:' || uri.hostname !== 'github.com'
      || !uri.pathname.startsWith('/nestchao/SonicBrief/releases/download/')) {
    throw new Error('Runtime manifest must point to an official SonicBrief GitHub Release.');
  }
}

function allowedDownloadUrl(url) {
  const uri = new URL(url);
  return uri.protocol === 'https:' &&
    (uri.hostname === 'github.com' || uri.hostname.endsWith('.githubusercontent.com'));
}

async function downloadStream(url, redirects = 0) {
  if (redirects > 8 || !allowedDownloadUrl(url)) {
    throw new Error('Untrusted or excessive GitHub release redirect.');
  }
  return new Promise((resolve, reject) => {
    https.get(url, { headers: { 'User-Agent': 'sonicbrief-mcp-launcher' } }, response => {
      if ([301, 302, 303, 307, 308].includes(response.statusCode) && response.headers.location) {
        const next = new URL(response.headers.location, url).href;
        response.resume();
        resolve(downloadStream(next, redirects + 1));
        return;
      }
      if (response.statusCode !== 200) {
        response.resume();
        reject(new Error('Runtime download returned HTTP ' + response.statusCode));
        return;
      }
      resolve(response);
    }).on('error', reject);
  });
}

async function downloadVerified(url, dest, expectedDigest) {
  const response = await downloadStream(url);
  const hash = createHash('sha256');
  let size = 0;
  const maxSize = 3 * 1024 * 1024 * 1024;
  const meter = new Transform({
    transform(chunk, encoding, callback) {
      size += chunk.length;
      if (size > maxSize) {
        callback(new Error('SonicBrief runtime archive exceeds 3 GiB limit.'));
        return;
      }
      hash.update(chunk);
      callback(null, chunk);
    },
  });
  await pipeline(response, meter, createWriteStream(dest, { flags: 'wx' }));
  const actual = hash.digest('hex');
  if (actual.toLowerCase() !== expectedDigest.toLowerCase()) {
    throw new Error('SonicBrief runtime SHA-256 mismatch. Installation aborted.');
  }
}

async function withInstallLock(lockPath, callback) {
  let ownsLock = false;
  for (let tries = 0; tries < 480; tries++) {
    try {
      await mkdir(lockPath);
      ownsLock = true;
      break;
    } catch (error) {
      if (error.code !== 'EEXIST') throw error;
      const info = await stat(lockPath).catch(() => null);
      if (info && Date.now() - info.mtimeMs > 60 * 60 * 1000) {
        // Recovery from a crashed installer; never delete a recent install lock.
        await rm(lockPath, { recursive: true, force: true });
        continue;
      }
      await sleep(250);
    }
  }
  if (!ownsLock) throw new Error('Timed out waiting for another SonicBrief installation.');
  try {
    return await callback();
  } finally {
    await rm(lockPath, { recursive: true, force: true });
  }
}

async function installRuntime(manifest, home) {
  const runtimeDirectory = path.join(home, 'runtime', 'cpu-' + manifest.runtimeVersion);
  const binary = path.join(runtimeDirectory, 'SonicBrief-MCP.exe');
  const receipt = path.join(runtimeDirectory, 'install.json');
  await mkdir(path.dirname(runtimeDirectory), { recursive: true });

  async function installed() {
    if (!existsSync(binary)) return false;
    try {
      const saved = JSON.parse(await readFile(receipt, 'utf8'));
      return saved.sha256 === manifest.sha256 && saved.version === manifest.runtimeVersion;
    } catch {
      return false;
    }
  }

  if (await installed()) return binary;
  return withInstallLock(runtimeDirectory + '.lock', async () => {
    if (await installed()) return binary;
    const staging = await mkdtemp(path.join(home, 'runtime', '.setup-'));
    try {
      const archive = path.join(staging, 'runtime.zip');
      const extracted = path.join(staging, 'extracted');
      await mkdir(extracted);
      console.error('[SonicBrief] Downloading verified Windows CPU runtime (first launch only)...');
      await downloadVerified(manifest.url, archive, manifest.sha256);
      const result = spawnSync('tar.exe', ['-xf', archive, '-C', extracted], {
        encoding: 'utf8', windowsHide: true, timeout: 180000,
      });
      if (result.error || result.status !== 0) {
        throw new Error('Could not extract portable runtime: ' + (result.error?.message || result.stderr || result.status));
      }
      if (!existsSync(path.join(extracted, 'SonicBrief-MCP.exe'))) {
        throw new Error('Runtime archive is missing SonicBrief-MCP.exe.');
      }
      await writeFile(path.join(extracted, 'install.json'), JSON.stringify({
        version: manifest.runtimeVersion, sha256: manifest.sha256,
      }), 'utf8');
      // Only versioned binaries are replaced; SQLite and models live elsewhere.
      await rm(runtimeDirectory, { recursive: true, force: true });
      await rename(extracted, runtimeDirectory);
      console.error('[SonicBrief] Runtime installed.');
      return binary;
    } finally {
      await rm(staging, { recursive: true, force: true });
    }
  });
}

async function main() {
  assertSupported();
  const home = applicationHome();
  // Development escape hatch: skip the download when testing a local executable.
  const override = process.env.SONICBRIEF_MCP_BINARY;
  let binary;
  if (override) {
    binary = path.resolve(override);
    if (!existsSync(binary)) throw new Error('SONICBRIEF_MCP_BINARY not found: ' + binary);
  } else {
    const manifest = JSON.parse(await readFile(manifestPath, 'utf8'));
    validateManifest(manifest);
    binary = await installRuntime(manifest, home);
  }

  for (const dirname of ['data', 'models', 'cache']) {
    await mkdir(path.join(home, dirname), { recursive: true });
  }
  const env = {
    ...process.env,
    SONICBRIEF_DATA_DIR: process.env.SONICBRIEF_DATA_DIR || path.join(home, 'data'),
    HF_HOME: process.env.HF_HOME || path.join(home, 'models'),
    XDG_CACHE_HOME: process.env.XDG_CACHE_HOME || path.join(home, 'cache'),
    SONICBRIEF_FORCE_CPU: '1',
    PATH: path.dirname(binary) + path.delimiter + (process.env.PATH || ''),
  };
  const child = spawn(binary, process.argv.slice(2), {
    stdio: 'inherit', env, windowsHide: true,
  });
  child.on('error', error => {
    console.error('[SonicBrief] Unable to start runtime:', error.message);
    process.exitCode = 1;
  });
  child.on('exit', (code, signal) => {
    process.exitCode = code === null ? 1 : code;
  });
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => {
    console.error('[SonicBrief] ' + error.message);
    process.exitCode = 1;
  });
}
