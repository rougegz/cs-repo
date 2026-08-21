#!/usr/bin/env node
/** Narto smoke test — mirrors NartoProvider's real flow (sections single-shot). */
"use strict";
const BASE = "https://edge.narto-drama.com";
const UA = "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";
const H = { "User-Agent": UA, Accept: "application/json, text/plain, */*", "X-Requested-With": "XMLHttpRequest" };
let passed=0, failed=0;
const check=(n,c,x)=>{ if(c){passed++;console.log("  ok    "+n);} else {failed++;console.log(`  FAIL  ${n}${x?" :: "+x:""}`);} };
async function j(url){ const r=await fetch(url,{headers:H}); if(!r.ok) throw new Error("HTTP "+r.status); return r.json(); }
(async()=>{
  console.log("Narto test —", BASE);
  const s = await j(`${BASE}/home/providers/sections?provider=melolo&lang=en-US&target_lang=en-US&_cb=${Date.now()}`);
  check("sections ok:true", s.ok===true);
  const items = (s.sections||[]).flatMap(x=>x.items||[]).filter(i=>i.title && i.watch_url && i.poster_url);
  check("real dramas >= 50", items.length>=50, `got ${items.length}`);
  check("all have posters", items.every(i=>!!i.poster_url));
  check("watch_url absolute", items[0].watch_url.startsWith("http"));
  check("book_id present", !!items[0].book_id);
  console.log(`\n==== ${passed} passed, ${failed} failed ====`);
  process.exit(failed?1:0);
})().catch(e=>{console.error(e);process.exit(1);});
