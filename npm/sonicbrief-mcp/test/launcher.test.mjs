import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import {
  applicationHome, assertSupported, validateManifest,
} from '../bin/sonicbrief-mcp.mjs';

const runtimeManifest = {
  platform: 'win32',
  arch: 'x64',
  runtimeVersion: '0.1.1',
  url: 'https://github.com/nestchao/SonicBrief/releases/download/mcp-v0.1.1/sonicbrief-windows-x64-cpu.zip',
  sha256: 'a'.repeat(64),
};

test('launcher keeps transcripts outside versioned runtime', () => {
  const root = applicationHome({ LOCALAPPDATA: 'C:\\Temp\\Profiles' });
  assert.equal(root, path.join('C:\\Temp\\Profiles', 'SonicBrief'));
});

test('only the packaged Windows x64 CPU runtime is supported', () => {
  assert.doesNotThrow(() => assertSupported('win32', 'x64'));
  assert.throws(() => assertSupported('linux', 'x64'), /Windows x64/);
  assert.throws(() => assertSupported('win32', 'arm64'), /Windows x64/);
});

test('runtime needs checksum and approved release host', () => {
  assert.doesNotThrow(() => validateManifest(runtimeManifest));
  assert.throws(() => validateManifest({ ...runtimeManifest, sha256: '' }), /SHA-256/);
  assert.throws(() => validateManifest({ ...runtimeManifest, url: 'https://example.com/file.zip' }), /official SonicBrief/);
  assert.throws(() => validateManifest({ ...runtimeManifest, url: 'http://github.com/example.zip' }), /official SonicBrief/);
  assert.throws(() => validateManifest({ ...runtimeManifest, runtimeVersion: '../secrets' }), /Invalid/);
});
