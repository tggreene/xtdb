# Tx lifecycle presentation assets

Supporting material for link:../doc/tx-lifecycle-and-linger.adoc[the tx lifecycle + linger.ms writeup].

## Start here

Open `tx-index.html` in a browser — it's a tabbed index over everything else.

Some tabs fetch siblings (`tx-lifecycle.mmd`). Browsers block `fetch()` on
`file://` URLs, so serve locally:

```bash
cd dev/tx-lifecycle && python3 -m http.server 8000
# then open http://localhost:8000/tx-index.html
```

## Files

| File | What it is |
|------|------------|
| `tx-index.html` | Tabbed hub — load this one |
| `tx-architecture.html` | Three mermaid architecture diagrams (high-level, thread ownership, log contents) |
| `tx-walkthrough.html` | reveal.js walkthrough — step-by-step tx trace with diagram + code synced |
| `tx-lifecycle.html` | reveal.js deep-dive slides on the linger.ms story |
| `tx-lifecycle-standalone.html` | Sequence diagram — embedded, works via `file://` |
| `tx-lifecycle-mermaid.html` | Sequence diagram — fetches `.mmd` source (needs `http.server`) |
| `tx-lifecycle.mmd` | Raw mermaid source for the sequence diagram |
| `tx-quiz.html` | 14-question quiz on the lifecycle |

All assets are self-contained (CDN for mermaid / reveal.js, no build step).

Delete this whole directory when the content is out of date.
