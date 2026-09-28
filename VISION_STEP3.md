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

### Done when

- [ ] Wearing the lens leaves a coloured trail of server-measured samples at the measured positions
- [ ] Handovers and reselections are visibly marked where they happened
- [ ] Standing still does not pile up duplicate markers
- [ ] `/rancraftc drivetest export` writes well-formed RFC 4180 CSV whose numbers stay correct on a
      comma-decimal default locale
- [x] `DriveTestLog` is pure and unit-tested — **19 tests green**, built in isolation while the
      Step 2 workflow held the build; not yet copied into `src/`
- [ ] Nothing on the trail is computed client-side

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

`LensSettings` reaches **6 fields** in this step (band filter, lobes, links, coverage, trail, metric).
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

1. **3a pure logic** — `DriveTestLog` + tests. *Collision-free; started while Step 2 finishes.*
2. **3a wiring** — payload position, client log, trail renderer, client command, `TRAIL` preset.
   *Waits for the Step 2 workflow to release the build: it edits the same files.*
3. **3b** — survey KPI fields, metric layer, problem finder + tests, diagnose command.
