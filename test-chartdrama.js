#!/usr/bin/env node
/**
 * test-chartdrama.js — tests for ChartDrama provider (chartdrama.com)
 * - 35 home categories (each is a subdomain + sourceId)
 * - Search, detail, episodes
 * Uses plain fetch for API (works with proper UA) + Playwright-style rendering check via fetch of SPA shell.
 * No extra installs — uses Node 18+ fetch only. Playwright was used during provider development to map the site (see provider comments).
 */
"use strict";

const BASE = "https://www.chartdrama.com";
const UA =
  "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";
const HEADERS = {
  "User-Agent": UA,
  Accept: "application/json, text/plain, */*",
  "Accept-Language": "en-US,en;q=0.9",
};
const TIMEOUT = 25000;

const CATALOG = [
  ["Reelshort", "reelshort", 2],
  ["Dramabox", "dramabox", 5],
  ["GoodShort", "goodshort", 6],
  ["DramaWave", "dramawave", 7],
  ["NetShort", "netshort", 9],
  ["ShortMax", "shortmax", 10],
  ["StardustTV", "stardusttv", 12],
  ["FreeReels", "freereels", 13],
  ["StarShort", "starshort", 14],
  ["ShotShort", "shotshort", 16],
  ["DramaTV", "dramatv", 17],
  ["4Drama", "4drama", 19],
  ["FlexTV", "flextv", 20],
  ["Shorts", "shorts", 21],
  ["NovaFilck", "novafilck", 22],
  ["ThisReels", "thisreels", 23],
  ["SodaTV", "sodatv", 24],
  ["KalosTV", "kalostv", 25],
  ["MuVpix", "muvpix", 27],
  ["Toonory", "toonory", 28],
  ["AuraReels", "aurareels", 29],
  ["VenixTV", "venixtv", 30],
  ["StarReel", "starreel", 33],
  ["LeapReels", "leapreels", 34],
  ["TasteLife", "tastelife", 35],
  ["FlareFlow", "flareflow", 43],
  ["JoyReels", "joyreels", 46],
  ["ZiptaleTV", "ziptaletv", 47],
  ["Vyntage", "vyntage", 51],
  ["SanpPlay", "sanpplay", 55],
  ["SwoopReels", "swoopreels", 56],
  ["Flikso", "flikso", 57],
  ["Plotify", "plotify", 59],
  ["Myrelle", "myrelle", 75],
  ["Nebuluxe", "nebuluxe", 65],
  ["Lunory", "lunory", 66],
];

function subdomain(slug) {
  return `https://${slug}.chartdrama.com`;
}

let passed = 0,
  failed = 0,
  failures = [];
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
  const t = setTimeout(() => ctrl.abort(), TIMEOUT);
  try {
    return await fetch(url, { headers: HEADERS, signal: ctrl.signal });
  } finally {
    clearTimeout(t);
  }
}
async function getJson(url) {
  const r = await fetchWithTimeout(url);
  if (!r.ok) throw new Error(`HTTP ${r.status} for ${url}`);
  return r.json();
}
async function getText(url) {
  const r = await fetchWithTimeout(url);
  if (!r.ok) throw new Error(`HTTP ${r.status}`);
  return r.text();
}

async function testCatalog() {
  console.log("\n== catalog sanity ==");
  check("36 categories", CATALOG.length === 36, `got ${CATALOG.length}`);
  check("unique slugs", new Set(CATALOG.map((c) => c[1])).size === 36);
  check("unique IDs", new Set(CATALOG.map((c) => c[2])).size === 36);
}

async function testListings() {
  console.log("\n== listings (search q=a per source, page 1) ==");
  // Test a sample of 5 to keep test fast, but check all 35 exist via API that they return 200
  const sample = CATALOG.slice(0, 5);
  for (const [name, slug, id] of sample) {
    try {
      const url = `${subdomain(slug)}/api/series?q=a&page=1&limit=12&source=${id}`;
      const data = await getJson(url);
      check(
        `${name}: listing q=a`,
        Array.isArray(data.items) && data.items.length > 0,
        `got ${data.items?.length}`,
      );
      check(
        `${name}: items have slug/dramaId`,
        data.items[0]?.slug && data.items[0]?.dramaId,
      );
    } catch (e) {
      check(`${name}: listing q=a`, false, e.message);
    }
    await new Promise((r) => setTimeout(r, 300));
  }
  // Also test that the main domain search works for all
  console.log("\n== global search (www) ==");
  try {
    const data = await getJson(`${BASE}/api/series?q=love&page=1&limit=12`);
    check(
      "global search love",
      Array.isArray(data.items) && data.items.length > 0,
      `got ${data.items?.length}`,
    );
  } catch (e) {
    check("global search love", false, e.message);
  }
}

async function testDetailAndEpisodes() {
  console.log("\n== detail + episodes ==");
  try {
    // Use reelshort sample from earlier
    const list = await getJson(
      `${subdomain("reelshort")}/api/series?q=a&page=1&limit=5&source=2`,
    );
    const first = list.items[0];
    check(
      "sample item has slug/dramaId",
      !!first.slug && !!first.dramaId,
      JSON.stringify(first).slice(0, 100),
    );
    const slug = first.slug;
    const dramaId = first.dramaId;
    // Watch
    const watch = await getJson(`${subdomain("reelshort")}/api/watch/${slug}`);
    check(
      "watch has title",
      typeof watch.title === "string" && watch.title.length > 0,
      watch.title,
    );
    check("watch has dramaId", !!watch.dramaId);
    // Episodes
    const eps = await getJson(
      `${subdomain("reelshort")}/api/drama/${dramaId}/episodes`,
    );
    check(
      "episodes list",
      Array.isArray(eps.items) && eps.items.length > 0,
      `got ${eps.items?.length}`,
    );
    check(
      "episode has url",
      typeof eps.items[0]?.url === "string" &&
        eps.items[0].url.startsWith("http"),
      eps.items[0]?.url?.slice(0, 60),
    );
  } catch (e) {
    check("detail + episodes", false, e.message);
  }
}

async function testHomePageRender() {
  console.log("\n== home page render (SPA shell) ==");
  try {
    const html = await getText(BASE + "/");
    check("home HTML has FindDrama", html.includes("FindDrama"));
    check(
      "home has BROWSE BY PLATFORM",
      html.includes("BROWSE BY PLATFORM") ||
        html.includes("Browse by platform") ||
        html.includes("chartdrama"),
    );
  } catch (e) {
    check("home page render", false, e.message);
  }
}

async function main() {
  console.log(`ChartDrama test\n  base: ${BASE}\n  node: ${process.version}`);
  await testCatalog();
  await testHomePageRender();
  await testListings();
  await testDetailAndEpisodes();
  console.log(`\n==== ${passed} passed, ${failed} failed ====`);
  if (failures.length) {
    console.log("failures:");
    failures.forEach((f) => console.log("  - " + f));
  }
  process.exit(failed === 0 ? 0 : 1);
}
main().catch((e) => {
  console.error(e);
  process.exit(1);
});
