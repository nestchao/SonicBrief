// Pure URL detection for the video sites SonicBrief supports.
//
// No chrome.* APIs in this file: it is imported by the popup, the service
// worker and the Node test suite (tests/extension-detect.test.mjs).
//
// detectVideoPage() answers "is the page the user is looking at a specific
// video?" — not merely "is the host supported?" — and returns a normalized URL
// so the same video never creates two different jobs.

export const PLATFORM_LABELS = {
  youtube: "YouTube",
  bilibili: "Bilibili",
  vimeo: "Vimeo",
  tiktok: "TikTok",
  twitter: "X / Twitter",
  soundcloud: "SoundCloud",
  twitch: "Twitch",
  douyin: "Douyin",
  instagram: "Instagram",
  facebook: "Facebook",
};

// Query parameters that never change which video is being watched.
const TRACKING_PARAMS = new Set([
  "si", "t", "pp", "feature", "fbclid", "igsh", "igshid", "_r", "_t",
  "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
  "ref", "ref_src", "ref_url", "referrer", "share", "share_source",
  "share_medium", "share_plat", "share_session_id", "share_tag",
  "timestamp", "unique_k", "spm_id_from", "vd_source", "from_source",
  "is_story_h5", "modal_id", "is_from_webapp", "sender_device", "web_id",
]);

function parseUrl(rawUrl) {
  if (typeof rawUrl !== "string") return null;
  let value = rawUrl.trim();
  if (!value) return null;
  if (!/^https?:\/\//i.test(value)) {
    // chrome://, about:, file:, sonicbrief-desktop: ... are never media pages.
    if (/^[a-z][a-z0-9+.-]*:/i.test(value)) return null;
    value = `https://${value}`;
  }
  try {
    const url = new URL(value);
    return url.protocol === "http:" || url.protocol === "https:" ? url : null;
  } catch {
    return null;
  }
}

function bareHost(url) {
  return url.hostname.toLowerCase().replace(/\.$/, "").replace(/^www\./, "");
}

function onHost(url, domain) {
  const host = bareHost(url);
  return host === domain || host.endsWith(`.${domain}`);
}

/** The URL minus tracking noise and a trailing slash, for platforms where no
 *  cleaner canonical form exists. */
function clean(url) {
  const copy = new URL(url.toString());
  for (const key of [...copy.searchParams.keys()]) {
    if (TRACKING_PARAMS.has(key.toLowerCase())) copy.searchParams.delete(key);
  }
  const search = copy.searchParams.toString();
  const path = copy.pathname.replace(/\/+$/, "") || "/";
  return `${copy.origin}${path}${search ? `?${search}` : ""}`;
}

const YOUTUBE_ID = /^[A-Za-z0-9_-]{6,}$/;

function youtube(url) {
  if (!onHost(url, "youtube.com") && !onHost(url, "youtu.be") && !onHost(url, "youtube-nocookie.com")) return null;
  let id = null;
  if (onHost(url, "youtu.be")) {
    id = url.pathname.split("/").filter(Boolean)[0] || null;
  } else {
    const match = url.pathname.match(/^\/(?:shorts|live|embed|v)\/([A-Za-z0-9_-]+)/);
    id = match ? match[1] : url.searchParams.get("v");
  }
  if (!id || !YOUTUBE_ID.test(id)) return null;
  // A watch link, a Short, a live replay and an embed all become one URL.
  return { platform: "youtube", videoId: id, url: `https://www.youtube.com/watch?v=${id}` };
}

function bilibili(url) {
  if (onHost(url, "b23.tv")) {
    const code = url.pathname.split("/").filter(Boolean)[0];
    return code ? { platform: "bilibili", videoId: code, url: `https://b23.tv/${code}` } : null;
  }
  if (!onHost(url, "bilibili.com")) return null;
  const match = url.pathname.match(/^\/video\/(BV[0-9A-Za-z]{8,}|av\d+)/i);
  if (!match) return null;
  // "p" selects the part of a multi-part video and must be preserved.
  const page = url.searchParams.get("p");
  const suffix = page && /^\d+$/.test(page) ? `?p=${page}` : "";
  return { platform: "bilibili", videoId: match[1], url: `https://www.bilibili.com/video/${match[1]}${suffix}` };
}

function vimeo(url) {
  if (!onHost(url, "vimeo.com")) return null;
  const match = url.pathname.match(/^\/(?:video\/)?(\d{6,})/);
  if (!match) return null;
  return { platform: "vimeo", videoId: match[1], url: `https://vimeo.com/${match[1]}` };
}

function tiktok(url) {
  if (!onHost(url, "tiktok.com")) return null;
  const video = url.pathname.match(/^\/@[^/]+\/video\/(\d+)/) || url.pathname.match(/^\/v\/(\d+)\.html/);
  if (video) return { platform: "tiktok", videoId: video[1], url: clean(url) };
  if (onHost(url, "vm.tiktok.com")) {
    const code = url.pathname.split("/").filter(Boolean)[0];
    if (code && /^[A-Za-z0-9]{5,}$/.test(code)) {
      return { platform: "tiktok", videoId: code, url: `https://vm.tiktok.com/${code}/` };
    }
  }
  return null;
}

function twitter(url) {
  if (!onHost(url, "x.com") && !onHost(url, "twitter.com")) return null;
  const match = url.pathname.match(/^\/[^/]+\/status\/(\d+)/);
  if (!match) return null;
  const cleaned = new URL(url.toString());
  cleaned.searchParams.delete("s"); // "s=20" (share source) never changes the post.
  return { platform: "twitter", videoId: match[1], url: clean(cleaned) };
}

const SOUNDCLOUD_RESERVED = new Set([
  "discover", "stream", "you", "search", "upload", "settings", "charts",
  "tags", "people", "pages", "terms-of-use", "imprint", "notifications",
  "messages", "library", "signin", "signup",
]);

function soundcloud(url) {
  if (!onHost(url, "soundcloud.com")) return null;
  const segments = url.pathname.split("/").filter(Boolean);
  if (segments.length < 2) return null;
  if (SOUNDCLOUD_RESERVED.has(segments[0].toLowerCase())) return null;
  return { platform: "soundcloud", videoId: segments.slice(0, 3).join("-"), url: clean(url) };
}

function twitch(url) {
  if (onHost(url, "clips.twitch.tv")) {
    const slug = url.pathname.split("/").filter(Boolean)[0];
    return slug ? { platform: "twitch", videoId: slug, url: `https://clips.twitch.tv/${slug}` } : null;
  }
  if (!onHost(url, "twitch.tv")) return null;
  const vod = url.pathname.match(/^\/videos\/(\d+)/);
  if (vod) return { platform: "twitch", videoId: vod[1], url: `https://www.twitch.tv/videos/${vod[1]}` };
  const clip = url.pathname.match(/^\/[^/]+\/clip\/([^/]+)/);
  if (clip) return { platform: "twitch", videoId: clip[1], url: clean(url) };
  // A live channel page is not a downloadable recording.
  return null;
}

function douyin(url) {
  if (!onHost(url, "douyin.com") && !onHost(url, "iesdouyin.com")) return null;
  const video = url.pathname.match(/^\/video\/(\d+)/);
  if (video) return { platform: "douyin", videoId: video[1], url: `https://www.douyin.com/video/${video[1]}` };
  if (onHost(url, "v.douyin.com")) {
    const code = url.pathname.split("/").filter(Boolean)[0];
    if (code) return { platform: "douyin", videoId: code, url: `https://v.douyin.com/${code}/` };
  }
  // Douyin opens a video from the feed by setting ?modal_id=<id>.
  const modal = url.searchParams.get("modal_id");
  if (modal && /^\d+$/.test(modal)) return { platform: "douyin", videoId: modal, url: `https://www.douyin.com/video/${modal}` };
  return null;
}

function instagram(url) {
  if (!onHost(url, "instagram.com")) return null;
  const match = url.pathname.match(/^\/(reel|p|tv)\/([A-Za-z0-9_-]+)/);
  if (!match) return null;
  return { platform: "instagram", videoId: match[2], url: `https://www.instagram.com/${match[1]}/${match[2]}/` };
}

function facebook(url) {
  if (onHost(url, "fb.watch")) {
    const code = url.pathname.split("/").filter(Boolean)[0];
    return code ? { platform: "facebook", videoId: code, url: `https://fb.watch/${code}/` } : null;
  }
  if (!onHost(url, "facebook.com")) return null;
  // Mobile links (m.facebook.com) keep the same shapes once the host is normalized.
  const isMobile = bareHost(url).startsWith("m.") || bareHost(url).startsWith("web.");
  const share = url.pathname.match(/^\/share\/(?:v|r)\/([A-Za-z0-9_-]+)/);
  if (share) return { platform: "facebook", videoId: share[1], url: `https://www.facebook.com/share/v/${share[1]}/` };
  if (isMobile) return null;
  const id = url.searchParams.get("v");
  if (/^\/watch\/?$/i.test(url.pathname) && id) {
    return { platform: "facebook", videoId: id, url: `https://www.facebook.com/watch/?v=${id}` };
  }
  if (/^\/video\.php$/i.test(url.pathname) && id) {
    return { platform: "facebook", videoId: id, url: `https://www.facebook.com/video.php?v=${id}` };
  }
  const reel = url.pathname.match(/^\/reel\/(\d+)/);
  if (reel) return { platform: "facebook", videoId: reel[1], url: `https://www.facebook.com/reel/${reel[1]}/` };
  const video = url.pathname.match(/^(?:\/[^/]+)?\/videos\/(\d+)/);
  if (video) return { platform: "facebook", videoId: video[1], url: clean(url) };
  return null;
}

const MATCHERS = [youtube, bilibili, vimeo, tiktok, twitter, soundcloud, twitch, douyin, instagram, facebook];

/**
 * Returns { platform, platformLabel, videoId, url } when the page is a specific
 * supported video, otherwise null.
 */
export function detectVideoPage(rawUrl) {
  const url = parseUrl(rawUrl);
  if (!url) return null;
  for (const matcher of MATCHERS) {
    const found = matcher(url);
    if (found) {
      return { ...found, platformLabel: PLATFORM_LABELS[found.platform] ?? found.platform };
    }
  }
  return null;
}

export function isSupportedUrl(rawUrl) {
  return Boolean(detectVideoPage(rawUrl));
}
