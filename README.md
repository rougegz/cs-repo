# VDrama — CloudStream extension

A [CloudStream](https://github.com/recloudstream/cloudstream) extension that
turns [v-drama.net](https://v-drama.net) — an aggregator of short-drama apps —
into twenty endlessly-scrollable home categories:

> DramaBox · ReelShort · FreeReels · Youdrama · Hishort · Meloshort · Sodareels
> · Dramamax · NetShort · MoboReels · iDrama · Pinedrama · ShortMax · DramaBite
> · Flareflow · WeTV · iQIYI · DramaNova · Melolo · StarShort

## Features

- **20 home categories**, one row per source app, in the order above.
- **Endless scrolling** — every category keeps loading (`?page=2`, `?page=3`, …)
  as you scroll, and stops cleanly when the site runs out instead of repeating
  items.
- **Search & quick search** across all source apps at once.
- **Episodes with subtitles** — multi-quality HLS/MP4 links plus per-language
  subtitle tracks when the site provides them.
- **Changeable domain** — Settings → Plugins → VDrama (gear icon): point the
  provider at any mirror domain; blank resets to the default. Also compatible
  with CloudStream's built-in per-provider URL override ("clone site").
- **Cache management / lazy loading**
  - Posters are loaded lazily by CloudStream's own image pipeline — only what
    scrolls into view is fetched, with the app's memory/disk image cache.
  - Listing pages are cached ~10 min and detail pages ~30 min via NiceHttp's
    `cacheTime` riding OkHttp's 50 MiB disk cache, so scrolling back up is
    instant and offline-friendly.
  - Stream links are **never** cached (they expire).
  - Home rows fetch one page at a time, only when scrolled into
    (`sequentialMainPage`
    - small delay keeps 20 categories from bursting against one host).

## Repository layout

```
├── .github/workflows/build.yml   # CI: builds .cs3 artifacts into the "builds" branch
├── build.gradle.kts              # root build config (official plugin template)
├── settings.gradle.kts           # auto-includes every module dir
├── VDrama/
│   ├── build.gradle.kts          # extension metadata (name, tvTypes, icon…)
│   └── src/main/kotlin/com/vdrama/
│       ├── VdramaStore.kt        # catalog constants, domain storage, url parsing
│       ├── VDramaProvider.kt     # the provider: home/search/load/loadLinks
│       ├── VdramaSettingsDialog.kt # in-plugin settings UI (domain override)
│       └── VDramaPlugin.kt       # @CloudstreamPlugin entry point
└── test.js                       # zero-dep test suite (unit + live smoke)
```

## Building

CI builds automatically on push (`.cs3` + `plugins.json` land on the `builds`
branch). To build locally you need JDK 17 + Android SDK + Gradle 8.10+:

```bash
gradle make makePluginsJson
# output: VDrama/build/VDrama.cs3
```

> No `gradle-wrapper.jar` is committed (text-only repo) — run `gradle wrapper`
> once or let Android Studio generate it, or use any Gradle ≥ 8.9 directly.

## Installing in CloudStream

1. Fork this repo (so Actions builds the `builds` branch under your account).
2. In CloudStream: Settings → Extensions → Add repository:
   `https://raw.githubusercontent.com/<you>/vdrama-cloudstream/builds/plugins.json`
3. Install **VDrama** from your repo.

## Testing

```bash
node test.js                 # unit tests + live smoke tests against v-drama.net
node test.js --base https://mirror.example.com   # test a mirror domain
```

71 checks: url-builder/parser unit tests, all 20 categories live, pagination
delta between page 1 and 2, detail API shape, stream API shape, search.

## How it works (data flow, all verified live)

| Step      | Request                                                    | Result                                   |
| --------- | ---------------------------------------------------------- | ---------------------------------------- |
| Home rows | `GET /en/app/<slug>?page=N`                                | HTML card grid (~60/page)                |
| Search    | `GET /en/?q=<query>`                                       | HTML card grid, single page              |
| Detail    | `GET /api/detail?provider=&id=&lang=en-US`                 | JSON: title, synopsis, `episodeList[]`   |
| Stream    | `GET /api/stream?provider=&dramaId=&episodeId=&lang=en-US` | JSON: `url`/`qualities[]`, `subtitles[]` |

Drama permalinks look like `/en/drama/<title>-<app-slug>-<id>`; the provider
matches known app slugs (longest first, since some contain hyphens) and parses
host-independently, so saved links survive a domain change.

## Legal

This extension scrapes a public aggregator site and hosts no content itself.
Availability and legality of the aggregated content depend on your region; use
at your own discretion.
