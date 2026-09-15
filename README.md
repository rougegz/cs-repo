## Install

Tap The Copy Icon On The Right:

```
https://raw.githubusercontent.com/rougegz/cs-repo/builds/repo.json
```

If that shows "repo not found", use:

```
https://raw.githubusercontent.com/rougegz/cs-repo/builds/plugins.json
```

One-tap button:

[![Add to CloudStream](https://img.shields.io/badge/Add_to_CloudStream-0d1117?style=for-the-badge&logo=android)](cloudstreamrepo://raw.githubusercontent.com/rougegz/cs-repo/builds/repo.json)

```
cloudstreamrepo://raw.githubusercontent.com/rougegz/cs-repo/builds/repo.json
```

## StremioCS (v3) — no built-in addons

Fresh install starts empty. Add your own:

1. Extensions → StremioCS → Settings → **Browse Addons** (in-app mini-browser,
   locked to `https://stremio-addons.net`) → open an addon → **Copy Link**.
2. Back in Settings → **Add** (accepts `https://…`, `stremio://…`, bare host,
   `Name|URL`, `?token=` query) or **Paste** from clipboard.
3. Reorder with ↑/↓ (order = catalogue & stream priority), toggle, search,
   Export/Import/Clear in Data.

Notes:

- `android.media.tv` log lines are benign (missing TV provider on phones —
  ignore).
- If download fails: re-add the `repo.json` raw URL above (not `plugins.json`),
  bump check `builds/plugins.json` returns 200, and ensure version bumped (app
  caches by version).
- Export warning: URLs may contain private `?tokens` — treat exports as secrets.
