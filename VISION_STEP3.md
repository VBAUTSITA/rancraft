# RANCraft — RF Vision Step 3: Measure & Diagnose

> Steps 1 and 2 show what the network **should** do. Step 3 records what you **actually measured**,
> and tells you what is wrong with it.

Companion to `VISION.md` (Steps 1–2). Split into two parts; **3a is deliberately small** so it can
start straight away, and 3b builds on the log 3a produces.

---

## Why this step

Real radio optimisation does not start from a coverage prediction. It starts from a **drive test**:
an engineer walks or drives the area with a scanner, every sample is logged with its position, and
the log is analysed for problems — coverage holes, overshooting cells, pilot pollution, ping-pong
handovers. The prediction is only a hypothesis; the drive log is the evidence.

RANCraft already computes a real measurement for the wearer every second. Today that number is
drawn on the HUD and thrown away. Step 3 keeps it.

The governing principle from `VISION.md` is unchanged, and Step 3 needs no exception to it:

> **The client may render an antenna's declared pattern.**
> **The client may never compute what you receive.**

Every point on a drive trail is a value the **server** measured and sent. The client stores and
draws it; it never derives one.

---

## Part 3a — Drive-test trail and log export *(simple on purpose)*

**Scope: remember every server sample, draw it as a trail, export it as a CSV log.
Zero new RF computation. One small payload addition.**

### What you see

Walk with the lens on. Behind you, a **breadcrumb trail** of small markers hangs at eye height, one
per sample, coloured by the service level the server measured there:

| Marker | Meaning |
|---|---|
| green → yellow → orange → red | EXCELLENT → GOOD → FAIR → POOR (same colours as the HUD) |
| grey | NO SERVICE |
| **tall white pillar** | a **handover** happened here (the server's handover count went up) |
| **tall yellow pillar** | a **reselection** here (serving cell changed without a handover, i.e. recovery from outage) |

The trail makes the stacked-mast confusion from Phase 2 testing obvious: stand still next to a
co-channel cluster and the markers pile up grey, however strong the RSRP. Walk a cell boundary and a
ping-pong shows up as a row of white pillars.

### The log

`/rancraftc drivetest export` writes the trail to
`<game dir>/rancraft/drivetests/drivetest-<timestamp>.csv`, one row per sample:

```
tick,x,y,z,serving_cell_id,pci,band,rsrp_dbm,sinr_db,service_level,handover_count,cells,event
```

That is the shape of a TEMS or Nemo export, cut down to what the model actually has. A radio
engineer can open it in Excel or any of their usual tools. `/rancraftc drivetest clear` resets it.

Numbers are written with `Locale.ROOT`. On a machine with a Spanish or other comma-decimal locale,
`String.format` would otherwise write `-82,4` and silently corrupt every column of the CSV.

**Excel caveat, stated honestly.** The file is standard RFC 4180 CSV: comma separator, `.` decimals,
CRLF line endings. That is what pandas, R, QGIS and every drive-test tool expect. But Excel on a
comma-decimal locale (such as `es-PE`) opened by **double-click** splits on `;` and reads `.` as a
thousands separator, so `-82.4` can silently become `-824`. Open it with **Data → From Text/CSV**,
choosing comma as the delimiter and `.` as the decimal separator. Writing a locale-specific file
instead would break every other tool, so the export stays standard.

### Architecture

```
rf/DriveTestLog.java        pure: bounded ring buffer of samples, event classification
                            (handover / reselection / outage), stationary de-duplication,
                            CSV formatting. No Minecraft. Unit-tested.

net/SignalSamplePayload     + rxX, rxY, rxZ: where the server actually evaluated.  (v3)
client/ClientDriveTest      feeds each received sample into a DriveTestLog
client/TrailRenderer        RenderLevelStageEvent -> markers + connecting line
client/DriveTestCommands    RegisterClientCommandsEvent -> /rancraftc drivetest export|clear
item/LensLayers             + TRAIL preset; ALL now includes the trail
```

**Why the payload has to carry the position.** Without it, the client would stamp a sample with its
*own* position when the packet arrives, which lags the server's evaluation point by network latency
times walking speed. That is wrong by a block or more, and the trail's whole point is *where*. The
server sends the exact point it measured at.

**Stationary de-duplication.** At 1 Hz, standing still for a minute would stack 60 identical markers
in one voxel. If the receiver moved less than half a block **and** the serving cell and service level
are unchanged, the new sample replaces the previous one instead of being appended. Any change of
cell, level or event always appends, so nothing a diagnosis needs is lost.

### Cost

| | |
|---|---|
| New RF computation | **none** — every sample already exists |
| Server | three doubles added to a packet it already sends |
| Client memory | bounded ring buffer, default 3,600 samples (1 hour at 1 Hz) |
| Per frame | one line segment and one marker per stored sample in render range |

### Honest about

- **The trail is a record, not a map.** It shows what was measured *where you walked*, nothing in
  between. That is the point of a drive test, and exactly why Part 3b exists.
- **Sampling is 1 Hz.** A sprinting player skips ground between markers. A real scanner does too, at
  its own sample rate.

### As built (Phase 3 slice 0)

Implemented as designed above, with these differences (full reasoning in `NOTES.md`, Phase 3,
slice 0):

- The payload also carries **`cellsHeard`**, the full count of cells heard. The `cells` column would
  otherwise cap at the four cells the payload carries.
- The payload's four-cell cut now **always keeps the serving cell** (a bug fix that also affects the
  meter HUD when hysteresis holds the serving cell at rank 5 or lower).
- **One log per dimension**, and the export writes one file per dimension:
  `drivetest-<yyyyMMdd-HHmmss>-<dimension>.csv`. The header is exactly as above.
- The log is **kept across respawn and dimension change** until `/rancraftc drivetest clear`, and
  **cleared on leaving the world** (Phase 3 follow-up; slice 0 first kept it across disconnect too,
  which drew one world's trail in the next).
- The export's chat link opens the `drivetests` folder, not the CSV, so Excel never opens it by
  double-click on a comma-decimal locale (Phase 3 follow-up).
- Every received sample is logged, from the meter or the lens; the trail layer only decides
  whether it is drawn. The lens band filter does not apply to the trail.
- **Every evaluation is sent** (slice 0 gate fix). The server evaluates a player who holds the
  meter, or whose lens shows the trail or the link rays, and every one of those evaluations now
  reaches the client. Before, a lens on the LINKS preset was evaluated without being sent the
  sample. Its handovers then showed up as one false pillar wherever the trail or the meter came
  back. The log reads handovers off the server's counter between consecutive samples, so it must
  not miss one.
- Capacity and trail render distance are client config (`config/rancraft-client.toml`).
- **"Stationary" is measured from where the still period began** (Phase 3A review, round 1), not
  from the previous sample, which may itself be a replacement. Before, any movement under half a
  block per sample (a 2-tick interval, sneaking, soul sand, or caching switched off) kept replacing
  one entry, and a whole walk collapsed into one sliding marker: a 100-block walk at 0.43 blocks per
  sample gave 1 entry, now 116. The server's sample cache had hidden it, because a cache refresh
  needs a 0.5-block move.

Status (Phase 3 row 5b): everything headless is done and green; the in-game half is steps 1, 9, 10
(the slow-walk check) and 11 (the Nether portal) of "How to test Part 3A in game" at the end of
`PHASE_3.md`.

### Done when

`[~]` = built and unit-tested where possible, needs an in-game check.

- [~] Wearing the lens leaves a coloured trail of server-measured samples at the measured positions
- [~] Handovers and reselections are visibly marked where they happened *(classification is
      unit-tested; the pillars need an in-game look)*
- [x] Standing still does not pile up duplicate markers *(the log keeps one entry, and exact
      replays of a cached sample are dropped, `DriveTestLogTest`; the renderer draws one marker
      per entry)*. Since review round 1 a slow walk is not collapsed either (0.4 blocks per sample
      over 100 blocks: 126 entries, pinned in `DriveTestLogTest`)
- [~] `/rancraftc drivetest export` writes well-formed RFC 4180 CSV whose numbers stay correct on a
      comma-decimal default locale *(the CSV text is unit-tested under `es-PE`; the command writing
      the file needs an in-game run)*
- [x] `DriveTestLog` is pure and unit-tested — **25 tests green** (22 at landing, +1 slice 0 gate,
      +2 review round 1), in `src/` since Phase 3 slice 0; `PackagePurityTest` asserts `rf` has no
      Minecraft reference
- [x] Nothing on the trail is computed client-side *(`SignalSamplePayload.toDriveTestSample()` is a
      field-for-field copy, pinned by `SignalSamplePayloadTest`; the renderer only picks colours for
      server-named levels and events)*

---

## Part 3b — KPI layers and automatic problem finder

**Scope: turn the Step 2 survey and the 3a log into diagnoses. Server-side analysis; heavier.**

### KPI layers on the coverage painting

Step 2 paints best-server. The survey already evaluates every point with the full engine, so it
already *has* RSRP, SINR and every cell's level there; it just throws most of it away. Keep it, and
let the lens switch what the terrain shows:

| Metric | What the colour means | Real or heuristic |
|---|---|---|
| **Best server** | who serves here (Step 2) | real |
| **RSRP** | received power, −120 → −60 dBm ramp | real (in model terms) |
| **SINR** | quality, −5 → +25 dB ramp | real (in model terms) |
| **Pilot pollution** | how many cells are within 6 dB of the best | **planning rule of thumb** |
| **Handover zone** | best − second-best ≤ the handover hysteresis | real A3 geometry |

The survey payload gains per-point quantised RSRP and SINR bytes plus a polluter count. At 48×48
that is about 7 KB more per survey.

### Automatic problem finder

After each survey completes, a pure analyser in `rf` walks the result and emits findings, each with a
location. The lens draws them as floating markers, and `/rancraft diagnose` lists them:

| Finding | Detection | Source |
|---|---|---|
| **Coverage hole** | a connected patch of NO SERVICE points above a minimum area | survey |
| **Overshooting cell** | a cell serving points beyond *k* × the distance to its nearest same-band neighbour | survey |
| **Pilot pollution zone** | a patch where ≥ *N* cells sit within *X* dB of the best above a floor | survey |
| **Ping-pong** | serving A→B→A within *T* seconds | 3a drive log |
| **PCI collision / confusion / mod-3** | already exists — `PciPlanner` | registry |

Every threshold is `ModConfigSpec` config, with commonly used planning defaults
(pollution: ≥ 4 cells within 6 dB above −100 dBm; ping-pong: A→B→A within 10 s).

### Honest about

- **Pilot pollution and overshooting are heuristics.** Operators define them differently. RANCraft
  uses common rules of thumb, labels them as such, and exposes the thresholds, so a player learns the
  *idea* without mistaking one operator's numbers for a standard.
- **Findings are only as good as the model.** A "coverage hole" is a hole in the *model*: no fading,
  no load, flat per-block attenuation (see the fidelity notes in `NOTES.md`).

### Scalability ceiling to know about

`LensSettings` reaches **6 fields** in this step (band filter, lobes, links, coverage, trail, metric);
3a took it to 5.
`StreamCodec.composite` in 1.21.1 supports at most 6. Any further lens setting has to switch that
codec to a hand-written `StreamCodec.of(...)`, as `SignalSamplePayload` already does. It is not hard;
it just must not be discovered by a compile error.

### Done when

- [ ] The lens cycles the coverage metric between the five layers above
- [ ] The problem finder flags a planted coverage hole, an overshooting cell, a pollution cluster
      and a ping-pong, and stays silent on a clean plan (unit tests on synthetic surveys)
- [ ] Findings render in-world and list via `/rancraft diagnose`
- [ ] Every heuristic threshold is configurable and labelled as a heuristic

---

## Deliberately not in Step 3 — candidates for Step 4

- **What-if ghost antenna.** Hold an antenna item and see a ghost of it at the placement point, with
  the server computing the coverage *change* before you commit. Powerful, but it is a prediction
  tool, and `VISION.md` keeps predictions for the planning phase.
- **Measured vs predicted.** Overlay the 3a drive log on the Step 2 survey and highlight where they
  disagree. In this model they only disagree where the world changed after the survey. With
  Phase 4 shadowing turned on, this becomes model calibration, which is real RAN work.
- **Shared drive logs** between players on a server.

---

## Build order

1. **3a pure logic** — `DriveTestLog` + tests. *Done.*
2. **3a wiring** — payload position, client log, trail renderer, client command, `TRAIL` preset.
   *Done in Phase 3 slice 0, with its gate fixes, the 0a follow-ups and one review round 1 fix;
   in-game checks pending (`PHASE_3.md`, "How to test Part 3A in game", steps 1, 9, 10 and 11).*
3. **3b** — survey KPI fields, metric layer, problem finder + tests, diagnose command.
