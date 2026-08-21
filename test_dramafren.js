#!/usr/bin/env node
/**
 * test_dramafren.js — live smoke for DramaFren (api.dramafren.org + reelfren)
 * Uses fetch for api.dramafren.org (server-rendered) and notes Cloudflare for reelfren.
 * Playwright is used where a real browser is needed to pass JS challenge.
 * No extra npm deps — Node >=18, uses global fetch. Playwright is optional.
 */
"use strict";

const DEFAULT_API = "https://api.dramafren.org";
const DEFAULT_REEL = "https://reelfren.dramafren.org";
const UA =
  "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";
const HEADERS = {
  "User-Agent": UA,
  Accept: "text/html,application/xhtml+xml",
  "Accept-Language": "en-US,en;q=0.9",
};
const TIMEOUT = 25000;

const CATALOG = [
  ["Melolo", "melolo"],
  ["Sereal+", "sereal"],
  ["PineDrama", "pinedrama"],
  ["Shorten", "shorten"],
  ["HappyShort", "happyshort"],
  ["Vigloo", "vigloo"],
  ["RaptDrama", "raptdrama"],
  ["CubeTV", "cubetv"],
  ["JoyReels", "joyreels"],
  ["AnyReel", "anyreel"],
  ["MiniTV", "minitv"],
  ["Bstation", "bstation"],
  ["GoldDrama", "golddrama"],
  ["Reelife", "reelife"],
  ["ReelShort", "reelshort"],
  ["DramaBox", "dramabox"],
  ["DramaNova", "dramanova"],
  ["KalosTV", "kalostv"],
  ["VibeShort", "vibeshort"],
  ["FreeReels", "freereels"],
  ["WeTV", "wetv"],
  ["StoryReel", "storyreel"],
  ["MovieBox", "moviebox"],
  ["MovieBox Shorts", "movieboxshorts"],
  ["MyDrama", "mydrama"],
  ["FlareFlow", "flareflow"],
  ["PlayLet", "playlet"],
  ["ShortMax", "shortmax"],
];

const CAT_PARAM = {
  sereal: "feed=latest",
  pinedrama: "category=0",
  shorten: "category=releases",
  happyshort: "category=home",
  vigloo: "category=home",
  bstation: "category=dracin",
  golddrama: "category=all",
  reelife: "category=all",
  dramanova: "category=all",
  kalostv: "category=all",
  vibeshort: "category=all",
  freereels: "category=all",
  moviebox: "category=1232643093049001320",
  movieboxshorts: "category=all",
  mydrama: "category=all",
};

function exploreUrl(base, provider, page) {
  const cat = CAT_PARAM[provider];
  const pg = page > 1 ? `&page=${page}` : "";
  return cat
    ? `${base}/explore?provider=${provider}&lang=en&${cat}${pg}`
    : `${base}/explore?provider=${provider}&lang=en${pg}`;
}
function normalize(u) {
  const t = (u || "").trim().replace(/\/+$/, "");
  if (!t) return null;
  return /^https?:\/\//.test(t) ? t : `https://${t}`;
}
function parseCards(html, base) {
  const out = [];
  const seen = new Set();
  const re = /href="(\/drama\/[^"]+)"/g;
  let m;
  while ((m = re.exec(html)) !== null) {
    const href = m[1];
    const abs = href.startsWith("http") ? href : base + href;
    if (seen.has(abs)) continue;
    seen.add(abs);
    // try to find title near href - crude but works for smoke
    const idx = m.index;
    const slice = html.slice(Math.max(0, idx - 800), idx + 800);
    const titleM =
      slice.match(/alt="([^"]+)"/) || slice.match(/<h3[^>]*>([^<]+)</);
    const title = titleM ? titleM[1].trim() : "";
    if (title) out.push({ title, link: abs });
  }
  return out;
}

let passed = 0,
  failed = 0;
function check(name, cond, extra) {
  if (cond) {
    passed++;
    console.log(`  ok    ${name}`);
  } else {
    failed++;
    console.log(`  FAIL  ${name}${extra ? ` :: ${extra}` : ""}`);
  }
}
async function getText(url) {
  const ctrl = new AbortController();
  const to = setTimeout(() => ctrl.abort(), TIMEOUT);
  try {
    const r = await fetch(url, { headers: HEADERS, signal: ctrl.signal });
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    return await r.text();
  } finally {
    clearTimeout(to);
  }
}

async function main() {
  const base = normalize(process.argv[2]) || DEFAULT_API;
  console.log(`DramaFren test — base: ${base} — ${CATALOG.length} categories`);
  // unit: catalog
  console.log("\n== unit: catalog ==");
  check("28 categories", CATALOG.length === 28);
  check("titles unique", new Set(CATALOG.map((c) => c[0])).size === 28);
  check("slugs unique", new Set(CATALOG.map((c) => c[1])).size === 28);

  // live: each provider at least 1 drama via HTML
  console.log("\n== live: 28 providers (page1) ==");
  for (const [title, slug] of CATALOG) {
    try {
      const html = await getText(exploreUrl(base, slug, 1));
      const cards = parseCards(html, base);
      check(`${title}: has cards`, cards.length > 0, `got ${cards.length}`);
      if (cards[0])
        check(`${title}: link sane`, cards[0].link.includes("/drama/"));
    } catch (e) {
      check(`${title}: has cards`, false, e.message);
    }
    await new Promise((r) => setTimeout(r, 180));
  }

  // pagination: try page2 for melolo
  console.log("\n== live: pagination (melolo) ==");
  try {
    const p1 = parseCards(await getText(exploreUrl(base, "melolo", 1)), base);
    const p2 = parseCards(await getText(exploreUrl(base, "melolo", 2)), base);
    check("melolo p1 has cards", p1.length > 0);
    check("melolo p2 fetched", true, `p1=${p1.length} p2=${p2.length}`);
    if (p1[0] && p2[0])
      check(
        "p1 vs p2 first link (if pagination via ?page, should differ)",
        p1[0].link !== p2[0].link,
        `${p1[0].link} vs ${p2[0].link}`,
      );
  } catch (e) {
    check("melolo pagination", false, e.message);
  }

  // detail + watch
  console.log("\n== live: detail + watch ==");
  try {
    const html = await getText(exploreUrl(base, "melolo", 1));
    const cards = parseCards(html, base);
    const first = cards[0];
    check("first drama link exists", !!first);
    if (first) {
      const detail = await getText(first.link);
      check(
        "detail page loads",
        detail.includes("EP") || detail.includes("Episode"),
      );
      // watch
      const watchUrl = detail.match(/href="(\/watch\/[^"]+)"/)?.[1];
      if (watchUrl) {
        const absWatch = watchUrl.startsWith("http")
          ? watchUrl
          : base + watchUrl;
        const watchHtml = await getText(absWatch);
        check("watch page loads", watchHtml.length > 1000);
        const hasVideo =
          watchHtml.includes("m3u8") ||
          watchHtml.includes(".mp4") ||
          watchHtml.includes("video");
        check(
          "watch has video hint",
          hasVideo,
          hasVideo ? "found m3u8/mp4" : "no m3u8 in html (may be JS-loaded)",
        );
      } else {
        check("watch link found", false, "no /watch/ href in detail");
      }
    }
  } catch (e) {
    check("detail+watch", false, e.message);
  }

  // search
  console.log("\n== live: search ==");
  try {
    const html = await getText(`${base}/search?lang=en&q=love`);
    const cards = parseCards(html, base);
    check("search love has results", cards.length > 0, `got ${cards.length}`);
  } catch (e) {
    check("search", false, e.message);
  }

  // Cloudflare check for reelfren
  console.log("\n== live: Cloudflare (reelfren) ==");
  try {
    const r = await fetch(`${DEFAULT_REEL}/?lang=en`, { headers: HEADERS });
    check(
      "reelfren returns 403 challenge (expected)",
      r.status === 403,
      `got ${r.status} — use WebView button in settings`,
    );
    if (r.status === 403)
      console.log(
        "  note: Cloudflare protected — provider will use saved cookies from settings WebView",
      );
  } catch (e) {
    check("reelfren", false, e.message);
  }

  console.log(`\n==== ${passed} passed, ${failed} failed ====`);
  process.exit(failed ? 1 : 0);
}
main().catch((e) => {
  console.error(e);
  process.exit(1);
});
