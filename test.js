#!/usr/bin/env node
/**
 * test.js — tests for the VDrama CloudStream extension's data source.
 *
 * Zero dependencies (Node >= 18). Two layers:
 *   1. Unit tests of the logic ported into Kotlin (url builders, card parser,
 *      drama-url parser, domain normalizer).
 *   2. Live smoke tests against v-drama.net: all 20 home categories,
 *      endless-scroll pagination, search, detail API, stream API.
 *
 * Usage:  node test.js [--base https://mirror.example.com]
 * Exit code 0 = all green, 1 = at least one failure.
 */
"use strict";

const DEFAULT_BASE = "https://v-drama.net";
const UA =
  "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";
const HEADERS = {
  "User-Agent": UA,
  Accept: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
  "Accept-Language": "en-US,en;q=0.9",
};
const TIMEOUT_MS = 25000;
const CONCURRENCY = 3;

/** Keep in sync with VDRAMA_CATALOG in VdramaStore.kt */
const CATALOG = [
  ["DramaBox", "dramabox"],
  ["ReelShort", "reelshort"],
  ["FreeReels", "freereels"],
  ["Youdrama", "youdrama"],
  ["Hishort", "hishort"],
  ["Meloshort", "meloshort"],
  ["Sodareels", "sodareels"],
  ["Dramamax", "dramamax"],
  ["NetShort", "netshort"],
  ["MoboReels", "hoshiyomi-moboreels"],
  ["iDrama", "hoshiyomi-idrama"],
  ["Pinedrama", "hoshiyomi-pinedrama"],
  ["ShortMax", "hoshiyomi-shortmax"],
  ["DramaBite", "hoshiyomi-dramabite"],
  ["Flareflow", "hoshiyomi-flareflow"],
  ["WeTV", "hoshiyomi-wetv"],
  ["iQIYI", "hoshiyomi-iqiyi"],
  ["DramaNova", "hoshiyomi-dramanova"],
  ["Melolo", "hoshiyomi-melolo"],
  ["StarShort", "hoshiyomi-starshort"],
];

// ---------------------------------------------------------------------------
// Ported provider logic (must behave exactly like the Kotlin implementation)
// ---------------------------------------------------------------------------

function normalizeBaseUrl(input) {
  if (input == null) return null;
  const trimmed = String(input).trim().replace(/\/+$/, "");
  if (!trimmed) return null;
  return /^https?:\/\//.test(trimmed) ? trimmed : `https://${trimmed}`;
}

function listingUrl(base, slug, page) {
  return `${base}/en/app/${slug}${page > 1 ? `?page=${page}` : ""}`;
}

function searchUrlPath(query) {
  return `/en/?q=${encodeURIComponent(query)}`;
}

const SLUGS_BY_LENGTH = CATALOG.map((c) => c[1]).sort(
  (a, b) => b.length - a.length,
);

function parseDramaUrl(link) {
  const path = link.split(/[?#]/)[0].replace(/\/+$/, "");
  const marker = "/en/drama/";
  const idx = path.lastIndexOf(marker);
  if (idx === -1) return null;
  const slug = path.slice(idx + marker.length);
  for (const app of SLUGS_BY_LENGTH) {
    const suffix = `-${app}-`;
    const at = slug.lastIndexOf(suffix);
    if (at !== -1) {
      const id = slug.slice(at + suffix.length);
      if (/^[a-zA-Z0-9]+$/.test(id)) return { provider: app, id };
    }
  }
  return null;
}

/** Extract cards from the listing/search grid without an HTML parser dep. */
function parseCards(html, base) {
  const out = [];
  const seen = new Set();
  const chunks = html.split('<li class="card"').slice(1);
  for (const chunk of chunks) {
    const block = chunk.slice(0, chunk.indexOf("</li>") + 5 || undefined);
    const hrefMatch = block.match(/href="([^"]*\/en\/drama\/[^"]+)"/);
    if (!hrefMatch) continue;
    const imgTag = block.match(/<img[^>]*class="card-img"[^>]*>/i);
    if (!imgTag) continue;
    const src = (imgTag[0].match(/\ssrc="([^"]+)"/) || [])[1];
    const alt = (imgTag[0].match(/\salt="([^"]*)"/) || [])[1];
    if (!src || alt == null || !alt.trim()) continue;
    const link = new URL(hrefMatch[1], base).toString();
    if (seen.has(link)) continue;
    seen.add(link);
    out.push({ title: alt.trim(), link, poster: src });
  }
  return out;
}

// ---------------------------------------------------------------------------
// Tiny test harness
// ---------------------------------------------------------------------------

let passed = 0;
let failed = 0;
const failures = [];

function check(name, cond, extra) {
  if (cond) {
    passed++;
    console.log(`  ok    ${name}`);
  } else {
    failed++;
    failures.push(name + (extra ? ` :: ${extra}` : ""));
    console.log(`  FAIL  ${name}${extra ? ` :: ${extra}` : ""}`);
  }
}

async function fetchWithTimeout(url) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), TIMEOUT_MS);
  try {
    return await fetch(url, {
      headers: HEADERS,
      redirect: "follow",
      signal: ctrl.signal,
    });
  } finally {
    clearTimeout(timer);
  }
}

/** GET with one retry on network errors / 5xx. Returns text or throws. */
async function getText(url) {
  let lastErr;
  for (let attempt = 1; attempt <= 2; attempt++) {
    try {
      const res = await fetchWithTimeout(url);
      if (res.status >= 500) throw new Error(`HTTP ${res.status}`);
      if (!res.ok)
        throw Object.assign(new Error(`HTTP ${res.status}`), { fatal: true });
      return await res.text();
    } catch (err) {
      lastErr = err;
      if (err.fatal) break;
      await new Promise((r) => setTimeout(r, 1000));
    }
  }
  throw lastErr;
}

async function getJson(url) {
  return JSON.parse(await getText(url));
}

/** Run promise-makers with bounded parallelism. */
async function pool(items, worker) {
  const queue = [...items.entries()];
  async function run() {
    let entry;
    while ((entry = queue.shift()) !== undefined)
      await worker(entry[1], entry[0]);
  }
  await Promise.all(
    Array.from({ length: Math.min(CONCURRENCY, items.length) }, run),
  );
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---------------------------------------------------------------------------
// 1. Unit tests
// ---------------------------------------------------------------------------

function unitTests() {
  console.log("\n== unit: normalizeBaseUrl ==");
  check(
    "adds scheme",
    normalizeBaseUrl("mirror.example.com") === "https://mirror.example.com",
  );
  check("keeps https", normalizeBaseUrl("https://x.tv/") === "https://x.tv");
  check("keeps http", normalizeBaseUrl("http://x.tv") === "http://x.tv");
  check(
    "trims trailing slashes",
    normalizeBaseUrl("https://x.tv///") === "https://x.tv",
  );
  check(
    "trims whitespace",
    normalizeBaseUrl("  https://x.tv  ") === "https://x.tv",
  );
  check("blank -> null", normalizeBaseUrl("   ") === null);
  check("empty -> null", normalizeBaseUrl("") === null);
  check("null -> null", normalizeBaseUrl(null) === null);

  console.log("\n== unit: catalog ==");
  check("has 20 categories", CATALOG.length === 20, `got ${CATALOG.length}`);
  check("titles unique", new Set(CATALOG.map((c) => c[0])).size === 20);
  check("slugs unique", new Set(CATALOG.map((c) => c[1])).size === 20);

  console.log("\n== unit: parseDramaUrl ==");
  const u1 =
    "/en/drama/silent-snow-falls-no-looking-back-reelshort-6a7ecdc9c34b99d5370d4c80";
  check(
    "simple slug",
    JSON.stringify(parseDramaUrl(u1)) ===
      JSON.stringify({ provider: "reelshort", id: "6a7ecdc9c34b99d5370d4c80" }),
  );
  const u2 =
    "https://v-drama.net/en/drama/some-title-hoshiyomi-moboreels-abc123?q=1#frag";
  check(
    "hyphenated slug wins",
    JSON.stringify(parseDramaUrl(u2)) ===
      JSON.stringify({ provider: "hoshiyomi-moboreels", id: "abc123" }),
    JSON.stringify(parseDramaUrl(u2)),
  );
  const u3 = "https://other-host.example/en/en/drama/t-dramabox-42x".replace(
    "/en/en/",
    "/en/",
  );
  check("host-agnostic", parseDramaUrl(u3)?.provider === "dramabox");
  check(
    "rejects unknown app",
    parseDramaUrl("/en/drama/title-unknownapp-123") === null,
  );
  check("rejects non-drama url", parseDramaUrl("/en/app/reelshort") === null);
  check(
    "strips query",
    parseDramaUrl(`${u1}?x=1`)?.id === "6a7ecdc9c34b99d5370d4c80",
  );

  console.log("\n== unit: url builders honor domain override ==");
  const mirrored = listingUrl(
    normalizeBaseUrl("mirror.example.com"),
    "reelshort",
    2,
  );
  check(
    "listing uses override + page param",
    mirrored === "https://mirror.example.com/en/app/reelshort?page=2",
  );
  check(
    "page 1 has no page param",
    listingUrl(DEFAULT_BASE, "reelshort", 1).endsWith("/en/app/reelshort"),
  );
}

// ---------------------------------------------------------------------------
// 2. Live tests
// ---------------------------------------------------------------------------

async function liveTests(base) {
  // Every category must return a parseable, non-empty card grid.
  console.log(`\n== live: all ${CATALOG.length} categories (page 1) ==`);
  const firstCards = {};
  await pool(CATALOG, async ([title, slug]) => {
    try {
      const html = await getText(listingUrl(base, slug, 1));
      const cards = parseCards(html, base);
      firstCards[slug] = cards;
      check(
        `${title}: HTTP ok + cards parsed`,
        cards.length > 0,
        `got ${cards.length}`,
      );
      const sample = cards[0];
      check(
        `${title}: card fields sane`,
        !!sample &&
          sample.title.length > 0 &&
          sample.link.includes("/en/drama/") &&
          sample.poster.startsWith("http"),
        JSON.stringify(sample).slice(0, 120),
      );
    } catch (err) {
      check(`${title}: HTTP ok + cards parsed`, false, err.message);
    }
    await sleep(200); // be polite to the origin
  });

  // Endless scroll: page 2 exists and is not a duplicate of page 1.
  console.log("\n== live: endless scroll pagination ==");
  try {
    const p1 =
      firstCards["reelshort"] ||
      parseCards(await getText(listingUrl(base, "reelshort", 1)), base);
    const p2 = parseCards(
      await getText(listingUrl(base, "reelshort", 2)),
      base,
    );
    check("reelshort page 2 has cards", p2.length > 0, `got ${p2.length}`);
    check(
      "page 2 differs from page 1",
      p1.length > 0 && p2.length > 0 && p1[0].link !== p2[0].link,
      `${p1[0]?.link} vs ${p2[0]?.link}`,
    );
    const links1 = new Set(p1.map((c) => c.link));
    const overlap =
      p2.filter((c) => links1.has(c.link)).length / Math.max(p2.length, 1);
    check(
      "page overlap < 50%",
      overlap < 0.5,
      `${(overlap * 100).toFixed(1)}%`,
    );
  } catch (err) {
    check("reelshort page 2 has cards", false, err.message);
  }

  // Detail + stream pipeline on one real drama.
  console.log("\n== live: detail + stream pipeline ==");
  try {
    const seed =
      firstCards["reelshort"]?.find(
        (c) => parseDramaUrl(c.link)?.provider === "reelshort",
      ) || parseCards(await getText(listingUrl(base, "reelshort", 1)), base)[0];
    const parsed = parseDramaUrl(seed.link);
    check("seed drama url parses", !!parsed, seed.link);

    const detail = await getJson(
      `${base}/api/detail?provider=${parsed.provider}&id=${parsed.id}&lang=en-US`,
    );
    check(
      "detail success",
      detail.success === true,
      JSON.stringify(detail).slice(0, 120),
    );
    check(
      "detail title present",
      typeof detail.title === "string" && detail.title.length > 0,
    );
    check(
      "episodeList non-empty",
      Array.isArray(detail.episodeList) &&
        detail.episodeList.length > 0 &&
        !!detail.episodeList[0].episodeId,
    );

    const ep = detail.episodeList[0];
    const stream = await getJson(
      `${base}/api/stream?provider=${parsed.provider}&dramaId=${parsed.id}` +
        `&episodeId=${ep.episodeId}&lang=en-US`,
    );
    check(
      "stream success",
      stream.success === true,
      JSON.stringify(stream).slice(0, 160),
    );
    check(
      "stream yields media",
      typeof stream.url === "string" && stream.url.startsWith("http"),
      `type=${stream.type}`,
    );
    check(
      "stream type known",
      ["hls", "mp4", "dash"].includes(String(stream.type).toLowerCase()),
      `type=${stream.type}`,
    );

    // Subtitle shape may be absent; when present it must be usable.
    if (Array.isArray(stream.subtitles) && stream.subtitles.length > 0) {
      check(
        "subtitles have urls",
        stream.subtitles.every(
          (s) => typeof s.url === "string" && s.url.startsWith("http"),
        ),
      );
    }
  } catch (err) {
    check("detail + stream pipeline", false, err.message);
  }

  // Search returns matches on a single page.
  console.log("\n== live: search ==");
  try {
    const html = await getText(`${base}${searchUrlPath("love")}`);
    const cards = parseCards(html, base);
    check("search 'love' has results", cards.length > 0, `got ${cards.length}`);
    check(
      "search results are drama links",
      cards.every((c) => c.link.includes("/en/drama/")),
    );
  } catch (err) {
    check("search 'love' has results", false, err.message);
  }
}

// ---------------------------------------------------------------------------

async function main() {
  const argIdx = process.argv.indexOf("--base");
  const base =
    normalizeBaseUrl(argIdx !== -1 ? process.argv[argIdx + 1] : null) ||
    DEFAULT_BASE;

  console.log(
    `VDrama extension test\n  target: ${base}\n  node:   ${process.version}`,
  );

  unitTests();

  // Any HTTP response counts as reachable; retries cover transient blips.
  let online = false;
  for (let attempt = 0; attempt < 3 && !online; attempt++) {
    online = await fetchWithTimeout(base)
      .then(() => true)
      .catch(() => false);
    if (!online) await sleep(1500);
  }
  if (!online) {
    failed++;
    failures.push(`target unreachable: ${base}`);
    console.log(`\nFAIL  target unreachable: ${base} — skipping live tests`);
  } else {
    await liveTests(base);
  }

  console.log(`\n==== ${passed} passed, ${failed} failed ====`);
  if (failures.length) {
    console.log("failures:");
    for (const f of failures) console.log(`  - ${f}`);
  }
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((err) => {
  console.error("unexpected crash:", err);
  process.exit(1);
});
