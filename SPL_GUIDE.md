# SPL basics used in this project

A Splunk search (SPL) is a pipeline: each stage after `|` takes the previous stage's output and transforms it. Read left to right as filter → parse → project → order.

```
index=main "6a8575..."  |  spath  |  table _time source message  |  sort _time
      filter                parse         project                    order
```

## The base search (before any `|`)

```spl
index=main "6a8575385f6bf4e3fdcfe03e8fd18577"
```

- `index=main` — which index to search (a hard filter, cheap, always put it first).
- A bare quoted string with no `field=` is a **full-text match** against the raw event text (`_raw`). It works even on fields Splunk hasn't extracted yet — which matters here, see below.
- `field=value` (e.g. `source=service-a`) only works if `field` is already a *known* field at search time — either indexed at ingest time, or one of Splunk's automatic extractions (`source`, `sourcetype`, `host`, `_time` always are). Anything inside our JSON body is not, until we extract it.

## `spath` — why we need it

Our events look like:
```json
{"severity":"INFO","logger":"...","message":"Calling service-b","properties":{"traceId":"...","spanId":"..."}}
```

`traceId` isn't a field Splunk knows about — it's a key nested two levels down inside `_raw`. `spath` walks the JSON and extracts fields, using dot notation for nesting:

```spl
| spath
```
turns `properties.traceId`, `properties.spanId`, `severity`, `logger`, `message` etc. into real fields you can filter/table/stats on.

You can also extract just one path instead of the whole document — faster on large result sets:
```spl
| spath "properties.traceId"
```
This is what you used — it only pulls that one field out, so a subsequent `search "properties.traceId"=<id>"` has something to match against.

**Order matters.** A field only exists *after* the `spath` that extracts it. This fails:
```spl
index=main severity=ERROR | spath   ❌ severity isn't extracted yet
```
This works:
```spl
index=main | spath | where severity="ERROR"   ✅
```

## `table` — projection

```spl
| table _time source properties.traceId properties.spanId message
```
Picks specific fields, in that order, drops everything else. Doesn't filter or reorder rows — purely cosmetic, for readability. Compare `fields` (same idea, doesn't force a specific column order or drop `_raw` unless you say so).

## `sort` — ordering

```spl
| sort _time      " ascending (oldest first)
| sort -_time     " descending (newest first), the - is the flip
```

## Other commands worth knowing (used elsewhere in this repo's SPLs)

- `stats dc(source) as services by properties.traceId` — one row per distinct `traceId`, counting distinct `source` values seen. Used to find traces that actually touched 2+ services.
- `where <expr>` — like `search`, but for expressions/comparisons after fields already exist (post-`spath` filtering).
- `earliest(_time)` / `latest(_time)` inside `stats` — first/last timestamp per group. Useful for span duration (see debugging doc below).

## Quick reference: our common query shape

```spl
index=main "<trace_id or free-text filter>"
| spath
| table _time source properties.traceId properties.spanId message
| sort _time
```
Filter cheaply on raw text first (fast, no parsing needed), parse with `spath` only on the already-narrowed result set, then shape/order for reading.
