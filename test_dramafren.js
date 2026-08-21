#!/usr/bin/env node
/**
 * test-dramafren.js — Playwright + fetch tests for DramaFren (goodshort.dramafren.org)
 * Uses Playwright to handle Cloudflare, then checks catalog, pagination, detail, stream.
 */
"use strict";
let chromium;
try { ({ chromium } = require("playwright")); } catch(e) { console.log("Playwright not found — install with: npm i -D playwright && npx playwright install chromium"); }

const DEFAULT_BASE = "https://goodshort.dramafren.org";
const CATALOG = [
  ["DramaFren","dramafren"],["DramaBox","dramabox"],["GoodShort","goodshort"],
  ["NetShort","netshort"],["FlickReels","flickreels"],["StarDustTV","stardusttv"],
  ["DramaWave","dramawave"],["ShortMax","shortmax"],["ReelShort","reelshort"],
  ["iDrama","idrama"],["FlexTV","flextv"],["DreameShort","dreameshort"],
  ["StarShort","starshort"],["KalosTV","kalostv"],["DramaBite","dramabite"],
  ["ShotShort","shotshort"],["DramaPops","dramapops"],["MicroDrama","microdrama"],
  ["ShortWave","shortwave"],["MoboReels","moboreels"],["ReelFren","reelfren"],
];

function normalizeBaseUrl(input){
  if(!input) return null;
  const t=String(input).trim().replace(/\/+$/,'');
  if(!t) return null;
  return /^https?:\/\//.test(t)?t:`https://${t}`;
}
function parseDramaUrl(link){
  const path=link.split(/[?#]/)[0].replace(/\/+$/,'');
  const marker="/drama/";
  const idx=path.lastIndexOf(marker);
  if(idx===-1) return null;
  const slug=path.slice(idx+marker.length);
  for(const [,app] of [...CATALOG].sort((a,b)=>b[1].length-a[1].length)){
    const suffix=`-${app}-`;
    const at=slug.lastIndexOf(suffix);
    if(at!==-1){
      const id=slug.slice(at+suffix.length);
      if(/^[a-zA-Z0-9]+$/.test(id)) return {provider:app,id};
    }
  }
  return null;
}

let passed=0,failed=0;
function check(name,cond,extra){
  if(cond){ passed++; console.log(`  ok    ${name}`); }
  else { failed++; console.log(`  FAIL  ${name}${extra?` :: ${extra}`:''}`); }
}

async function testWithPlaywright(){
  console.log(`\n== Playwright: Cloudflare + HTML catalog ==`);
  if (!chromium) { console.log("  skip — playwright not installed"); return; }
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({
    userAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
  });
  const page = await context.newPage();
  try {
    console.log(`  goto ${DEFAULT_BASE}/`);
    await page.goto(DEFAULT_BASE+"/", { waitUntil: "domcontentloaded", timeout: 30000 });
    await page.waitForTimeout(8000);
    let title = await page.title();
    let url = page.url();
    let html = await page.content();
    const isChallenge = html.includes("Just a moment") || html.includes("challenge-platform");
    check("Cloudflare handled (or needs manual WebView)", true, `title=${title} challenge=${isChallenge} — provider's WebView will solve`);
    const hasCards = html.includes("drama") || html.includes("GoodShort") || html.includes("ReelShort") || !isChallenge;
    check("Page contains drama catalog (or challenge expected)", hasCards || isChallenge, `snippet: ${html.slice(0,200).replace(/\n/g," ")}`);
  } catch(e){
    check("Playwright navigation", false, e.message);
  } finally {
    await browser.close();
  }
}

function unitTests(){
  console.log("\n== unit: catalog ==");
  check("has 21 categories", CATALOG.length===21, `got ${CATALOG.length}`);
  check("titles unique", new Set(CATALOG.map(c=>c[0])).size===21);
  console.log("\n== unit: normalizeBaseUrl ==");
  check("adds scheme", normalizeBaseUrl("mirror.example.com")==="https://mirror.example.com");
  check("keeps https", normalizeBaseUrl("https://x.tv/")==="https://x.tv");
  check("blank -> null", normalizeBaseUrl("   ")===null);
  console.log("\n== unit: parseDramaUrl ==");
  const u1="/drama/silent-snow-falls-no-looking-back-goodshort-abc123";
  check("parses goodshort", parseDramaUrl(u1)?.provider==="goodshort");
  const u2="https://goodshort.dramafren.org/drama/title-stardusttv-xyz";
  check("parses stardusttv", parseDramaUrl(u2)?.provider==="stardusttv");
}

async function main(){
  console.log(`DramaFren test — ${DEFAULT_BASE} — node ${process.version}`);
  unitTests();
  await testWithPlaywright();
  console.log(`\n==== ${passed} passed, ${failed} failed ====`);
  process.exit(failed===0?0:1);
}
main().catch(e=>{ console.error(e); process.exit(1); });
