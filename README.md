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

## StremioCS — no built-in addons

Fresh install starts empty. Add your own:

1. Extensions → StremioCS → Settings → **Browse Addons** to find one, then copy
   its manifest URL.
2. Back in Settings → **Add** (paste the URL) or **Paste** from clipboard.
3. Reorder with ↑/↓ (top = highest priority) or toggle one off. Subtitles come
   automatically with subtitle addons.

Notes:

- `android.media.tv` log lines are benign (missing TV provider on phones —
  ignore).
- If download fails: re-add the `repo.json` raw URL above (not `plugins.json`)
  and check `builds/plugins.json` returns 200.
