/**
 * Progressive enhancement: real release data from the public GitHub API.
 *
 * The markup already links to immutable, versioned assets, so this module is a
 * convenience, not a requirement. When it runs it refreshes the star count, the
 * version in the hero and every download href to the newest published release,
 * with the real file size.
 */

const REPO = "nestchao/SonicBrief";
const API = `https://api.github.com/repos/${REPO}`;
const CACHE_KEY = "sb-gh-releases";
const TTL = 30 * 60 * 1000;

const ASSETS = {
  "windows-gpu": { channel: "desktop", name: "SonicBrief-Setup-gpu.exe" },
  "windows-cpu": { channel: "desktop", name: "SonicBrief-Setup-cpu.exe" },
  android: { channel: "android", name: "app-universal-release.apk" },
  "android-arm64": { channel: "android", name: "app-arm64-v8a-release.apk" },
};

function readCache() {
  try {
    const raw = sessionStorage.getItem(CACHE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed.at !== "number" || Date.now() - parsed.at > TTL) return null;
    return parsed.data;
  } catch (error) {
    return null;
  }
}

function writeCache(data) {
  try {
    sessionStorage.setItem(CACHE_KEY, JSON.stringify({ at: Date.now(), data }));
  } catch (error) {
    /* private mode or quota: ignore */
  }
}

function latestChannel(releases, prefix) {
  return releases.find((release) => release.tag.startsWith(prefix)) || null;
}

function megabytes(bytes) {
  return `${Math.round(bytes / 1048576)} MB`;
}

function render({ stars, releases }) {
  if (typeof stars === "number") {
    const label = new Intl.NumberFormat().format(stars);
    for (const node of document.querySelectorAll("[data-github-stars]")) {
      node.textContent = label;
      node.hidden = false;
    }
  }

  const android = latestChannel(releases, "android-v");
  if (android) {
    const version = android.tag.replace(/^android-v/, "");
    for (const node of document.querySelectorAll("[data-release-version]")) {
      node.textContent = `v${version}`;
    }
  }

  for (const node of document.querySelectorAll("[data-download]")) {
    const spec = ASSETS[node.dataset.download];
    if (!spec) continue;
    const release = latestChannel(releases, `${spec.channel}-v`);
    if (!release) continue;
    const asset = release.assets.find((item) => item.name === spec.name);
    if (!asset) continue;
    node.setAttribute("href", asset.url);
    const size = document.querySelector(`[data-size="${node.dataset.download}"]`);
    if (size) size.textContent = megabytes(asset.size);
  }
}

async function getJSON(url) {
  const response = await fetch(url, {
    headers: { Accept: "application/vnd.github+json" },
    credentials: "omit",
  });
  if (!response.ok) throw new Error(`${url} → HTTP ${response.status}`);
  return response.json();
}

export async function initGitHub() {
  const cached = readCache();
  if (cached) {
    render(cached);
    return;
  }

  try {
    const [repo, releases] = await Promise.all([getJSON(API), getJSON(`${API}/releases?per_page=15`)]);
    const data = {
      stars: repo.stargazers_count,
      releases: (releases || [])
        .filter((release) => !release.draft && !release.prerelease)
        .map((release) => ({
          tag: release.tag_name,
          published: release.published_at,
          assets: (release.assets || []).map((asset) => ({
            name: asset.name,
            size: asset.size,
            url: asset.browser_download_url,
          })),
        })),
    };
    writeCache(data);
    render(data);
  } catch (error) {
    // Offline or rate-limited: the versioned links in the markup still work.
    console.warn("[SonicBrief] Live release data unavailable.", error);
  }
}
