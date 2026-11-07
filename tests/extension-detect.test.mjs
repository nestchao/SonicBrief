import test from "node:test";
import assert from "node:assert/strict";

import { detectVideoPage, isSupportedUrl, PLATFORM_LABELS } from "../extension/detect.js";

// [input, expected platform (null = not a video page), expected normalized URL]
const CASES = [
  // --- YouTube -------------------------------------------------------------
  ["https://youtu.be/dQw4w9WgXcQ", "youtube", "https://www.youtube.com/watch?v=dQw4w9WgXcQ"],
  ["youtu.be/dQw4w9WgXcQ", "youtube", "https://www.youtube.com/watch?v=dQw4w9WgXcQ"],
  ["https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s&si=abc123&pp=ygU", "youtube",
    "https://www.youtube.com/watch?v=dQw4w9WgXcQ"],
  ["https://m.youtube.com/watch?v=dQw4w9WgXcQ", "youtube", "https://www.youtube.com/watch?v=dQw4w9WgXcQ"],
  ["https://music.youtube.com/watch?v=dQw4w9WgXcQ", "youtube", "https://www.youtube.com/watch?v=dQw4w9WgXcQ"],
  ["https://www.youtube.com/shorts/abc_-123XYZ", "youtube", "https://www.youtube.com/watch?v=abc_-123XYZ"],
  ["https://www.youtube.com/live/abc_-123XYZ", "youtube", "https://www.youtube.com/watch?v=abc_-123XYZ"],
  ["https://www.youtube.com/embed/abc_-123XYZ?start=30", "youtube", "https://www.youtube.com/watch?v=abc_-123XYZ"],
  ["https://www.youtube.com/watch", null],
  ["https://www.youtube.com/watch?v=short", null],
  ["https://www.youtube.com/@somechannel", null],
  ["https://www.youtube.com/feed/subscriptions", null],
  ["https://www.youtube.com/playlist?list=PL1234567890", null],
  ["https://www.youtube.com/", null],

  // --- Bilibili ------------------------------------------------------------
  ["https://www.bilibili.com/video/BV1GJ411x7h7/?p=2&spm_id_from=333.999", "bilibili",
    "https://www.bilibili.com/video/BV1GJ411x7h7?p=2"],
  ["https://www.bilibili.com/video/BV1GJ411x7h7", "bilibili",
    "https://www.bilibili.com/video/BV1GJ411x7h7"],
  ["https://www.bilibili.com/video/av170001", "bilibili", "https://www.bilibili.com/video/av170001"],
  ["https://b23.tv/AbCdEf1", "bilibili", "https://b23.tv/AbCdEf1"],
  ["https://space.bilibili.com/123456", null],
  ["https://www.bilibili.com/", null],

  // --- Vimeo ---------------------------------------------------------------
  ["https://vimeo.com/123456789", "vimeo", "https://vimeo.com/123456789"],
  ["https://player.vimeo.com/video/123456789?h=abc", "vimeo", "https://vimeo.com/123456789"],
  ["https://vimeo.com/channels/staffpicks", null],

  // --- TikTok --------------------------------------------------------------
  ["https://www.tiktok.com/@user.name/video/1234567890123456789", "tiktok",
    "https://www.tiktok.com/@user.name/video/1234567890123456789"],
  ["https://www.tiktok.com/@user.name/video/1234567890123456789?is_from_webapp=1", "tiktok",
    "https://www.tiktok.com/@user.name/video/1234567890123456789"],
  ["https://vm.tiktok.com/ZMabcdef1/", "tiktok", "https://vm.tiktok.com/ZMabcdef1/"],
  ["https://www.tiktok.com/@user.name", null],

  // --- X / Twitter ---------------------------------------------------------
  ["https://x.com/someone/status/1234567890123456789", "twitter",
    "https://x.com/someone/status/1234567890123456789"],
  ["https://twitter.com/someone/status/1234567890123456789?s=20", "twitter",
    "https://twitter.com/someone/status/1234567890123456789"],
  ["https://x.com/someone", null],
  ["https://x.com/home", null],

  // --- SoundCloud ----------------------------------------------------------
  ["https://soundcloud.com/artist/track-name", "soundcloud", "https://soundcloud.com/artist/track-name"],
  ["https://soundcloud.com/artist/sets/my-playlist", "soundcloud",
    "https://soundcloud.com/artist/sets/my-playlist"],
  ["https://soundcloud.com/discover", null],
  ["https://soundcloud.com/artist", null],

  // --- Twitch --------------------------------------------------------------
  ["https://www.twitch.tv/videos/1234567890", "twitch", "https://www.twitch.tv/videos/1234567890"],
  ["https://www.twitch.tv/somechannel/clip/FunnySlug", "twitch",
    "https://www.twitch.tv/somechannel/clip/FunnySlug"],
  ["https://clips.twitch.tv/FunnySlug", "twitch", "https://clips.twitch.tv/FunnySlug"],
  ["https://www.twitch.tv/somechannel", null],
  ["https://www.twitch.tv/", null],

  // --- Douyin --------------------------------------------------------------
  ["https://www.douyin.com/video/7345678901234567890", "douyin",
    "https://www.douyin.com/video/7345678901234567890"],
  ["https://v.douyin.com/iAbCdEfG/", "douyin", "https://v.douyin.com/iAbCdEfG/"],
  ["https://www.douyin.com/?modal_id=7345678901234567890", "douyin",
    "https://www.douyin.com/video/7345678901234567890"],
  ["https://www.douyin.com/", null],

  // --- Instagram -----------------------------------------------------------
  ["https://www.instagram.com/reel/C1a2b3c4d5/", "instagram",
    "https://www.instagram.com/reel/C1a2b3c4d5/"],
  ["https://www.instagram.com/p/C1a2b3c4d5/", "instagram", "https://www.instagram.com/p/C1a2b3c4d5/"],
  ["https://www.instagram.com/", null],
  ["https://www.instagram.com/someuser/", null],

  // --- Facebook ------------------------------------------------------------
  ["https://www.facebook.com/watch/?v=123456789", "facebook",
    "https://www.facebook.com/watch/?v=123456789"],
  ["https://www.facebook.com/somepage/videos/123456789", "facebook",
    "https://www.facebook.com/somepage/videos/123456789"],
  ["https://www.facebook.com/reel/123456789", "facebook", "https://www.facebook.com/reel/123456789/"],
  ["https://fb.watch/abcdef1/", "facebook", "https://fb.watch/abcdef1/"],
  ["https://www.facebook.com/", null],
  ["https://www.facebook.com/somepage", null],

  // --- Everything else -----------------------------------------------------
  ["https://example.com/watch?v=abc123", null],
  ["chrome://extensions", null],
  ["about:blank", null],
  ["file:///C:/video.mp4", null],
  ["", null],
  [null, null],
];

for (const [input, platform, expectedUrl] of CASES) {
  test(`detect ${String(input).slice(0, 70)}`, () => {
    const result = detectVideoPage(input);
    if (platform === null) {
      assert.equal(result, null, `expected no detection for ${input}`);
      assert.equal(isSupportedUrl(input), false);
      return;
    }
    assert.ok(result, `expected a detection for ${input}`);
    assert.equal(result.platform, platform);
    assert.equal(result.platformLabel, PLATFORM_LABELS[platform]);
    assert.ok(result.videoId, "videoId must be present");
    if (expectedUrl) assert.equal(result.url, expectedUrl);
  });
}

test("every platform has a display label", () => {
  for (const [platform, label] of Object.entries(PLATFORM_LABELS)) {
    assert.equal(typeof label, "string");
    assert.ok(label.length > 0, `${platform} needs a label`);
  }
});

test("normalization is stable (running twice changes nothing)", () => {
  for (const [input, platform] of CASES) {
    if (!platform) continue;
    const once = detectVideoPage(input);
    const twice = detectVideoPage(once.url);
    assert.ok(twice, `normalized URL must still detect: ${once.url}`);
    assert.equal(twice.url, once.url, `normalized URL must be idempotent: ${input}`);
  }
});
