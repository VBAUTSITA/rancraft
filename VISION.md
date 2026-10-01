# RANCraft — RF Vision

> Wearable augmented reality for radio. Put on the lens and the invisible field becomes
> geometry you can walk around.

This document covers the whole feature, split into two shippable steps. **Step 1 is implemented.**
Step 2 is designed but not built.

---

## Why this exists

Phase 2 can already tell you *that* your SINR is −5.6 dB. It cannot show you **why**.

A real example from testing: the meter reported `(9 co-channel)` and a player spent ten minutes
confused about why standing closer to the antenna did not help. The actual cause was nine Signal
Masts stacked in one column, four metres away — visually indistinguishable from a single tall
tower, because the mast block renders as a full-height pillar.

A HUD number could not show that. **A picture would have shown it instantly.** That is the entire
argument for this feature: RANCraft is a teaching tool, and the thing it teaches is inherently
spatial. Beamwidth, downtilt, front-to-back, cell dominance, interference geometry — all of it is
shape, and we have been rendering it as text.

---

## The governing principle

> **The client may render an antenna's declared pattern.**
> **The client may never compute what you receive.**

This line is load-bearing, and it is what keeps the feature from violating the Phase 2 ground rule
that the server is authoritative.

| | Who owns it | Why |
|---|---|---|
| Antenna azimuth, tilt, beamwidths, gain, band, PCI | **Public config.** Synced to client. | It is the antenna's own specification, like a furnace's contents. Rendering it client-side is free and cannot be cheated into an advantage. |
| RSRP, SINR, obstruction, serving-cell choice, coverage | **Server only.** Sent as values. | These are *measurements*. A client that could compute them could see through terrain it has not loaded, and would diverge from the server's truth. |

`dev.rancraft.rf` having zero `net.minecraft` imports is what makes the first row possible: the
client can run the *exact same* `AntennaPattern` object the server evaluates against. The lobe you
see and the dBm you measure cannot drift apart, because there is one implementation of the maths.

---

## Step 1 — See the Antenna *(implemented)*

**Scope: the antenna's own radiation pattern, drawn in the world. Zero new RF computation, zero
new measurement payload.**

### What you see

Wear the **RF Lens** in the helmet slot. Every antenna in range grows a wireframe lobe anchored at
its radiating point, oriented by its real azimuth and tilt, scaled by its real gain.

- A 65° × 10° sector shows a long narrow beam with a small backlobe stub.
- A Signal Mast shows a sphere, because an omni is flat in every direction.
- Crank downtilt from 0° to 10° and watch the beam tip into the ground.
- Swing the azimuth and watch it sweep.

Drawn as **nested wireframe shells at −3 dB, −10 dB and −20 dB** below peak gain.

### Why wireframe shells and not a solid blob

Two reasons, one aesthetic and one about honesty.

A solid surface implies a boundary. **RF does not stop anywhere.** A filled lobe would teach a
player that there is an edge to the beam, which is exactly the wrong intuition — the whole point of
the parabolic pattern is that it rolls off continuously and saturates at a floor. Nested contour
shells say "this is where you are 3 dB down, this is where you are 10 dB down", which is true, and
is how every real antenna pattern visualiser draws it.

It also happens to dodge translucency sorting entirely and reads better against terrain.

### Architecture

```
rf/PatternMesh.java          pure geometry: samples AntennaPattern on a sphere,
                             returns unit-radius vertices. No Minecraft. Unit-tested.
        │
        ├── client/LensRenderer.java      RenderLevelStageEvent -> draws the shells
        └── client/AntennaConfigScreen    (could reuse later for a 3D preview)

block/AntennaBlockEntity     getUpdateTag/getUpdatePacket -> client knows real CellParams
item/RfLensItem              Equipable, HEAD slot
```

`PatternMesh` lives in `rf` for the same reason everything else does: it is pure maths, it is
unit-testable headless, and Phase 4's measured-pattern JSON will feed it unchanged.

### Cost

| | |
|---|---|
| New RF computation | **none** — pattern sampling is trig, no ray marching |
| New measurement payload | **none** — uses vanilla block-entity sync |
| Per-frame work | none; meshes are generated once per config change and cached |
| Server tick impact | **zero** |

The mesh is built from `CellParams` alone. When a player edits an antenna, the block entity syncs
and the cache entry is invalidated. Nothing else recomputes.

### Done when

- [x] Wearing the lens draws a lobe on every antenna in range
- [x] The lobe swings when azimuth changes and tips when tilt changes
- [x] An omni mast renders as a sphere, a sector as a beam
- [x] Removing the lens stops all rendering
- [x] `PatternMesh` is pure and unit-tested
- [x] Zero measured values are computed client-side

---

## Step 2 — See the Field *(designed, not built)*

**Scope: what you actually receive, and where it comes from. Needs server-computed data.**

### 2a. Link rays — cheap, high value

A line from your head to every cell currently hitting you, coloured by RSRP, drawn along the
**actual voxel march path** so it visibly reddens where terrain eats it.

The data already exists: `SignalSamplePayload` carries the top 4 cells with RSRP, position and
band. The only addition would be optionally streaming the march path itself for the serving link,
so you can see *where* the dB went.

This is the piece that would have diagnosed the stacked-mast confusion in two seconds — nine lines
converging on one column four metres away.

**Cost:** no new RF computation. Tiny payload growth. This should be built first in Step 2.

### 2b. Best-server painting — the real planning tool

Colour the terrain by *which* PCI serves it. This is the best-server plot every radio planner
already reads, except draped over real hills, and it moves when you retilt.

**Cost: this is the expensive one.** Every ground point needs its own ray march.

| Grid | Evaluations | At 0.0086 ms each |
|---|---|---|
| 32 × 32 | 1,024 | ~9 ms |
| 64 × 64 | 4,096 | ~35 ms |
| 128 × 128 | 16,384 | ~140 ms |

A 64 × 64 survey is 0.7 of a tick — far too much at once. It must be **time-sliced across ticks**
(e.g. 8 rows per tick, ~4 ms/tick for 8 ticks).

It cannot go off-thread: `WorldProbe` reads `ServerLevel` block states, and that is not thread-safe.
Time-slicing on the main thread is the only safe answer.

Invalidation reuses machinery `SignalTicker` already has: the per-dimension block-change epoch and
the move-epsilon check. A survey is recomputed when the player moves far enough or any block in the
dimension changes.

### 2c. Volumetric particles — deliberately rejected

Filling the air with signal-coloured particles is the prettiest option and the least informative.
It obscures the terrain you are trying to plan against, costs a fortune in particle updates, and
tells you less than a flat colour on the ground. **Not planned.**

---

## Scalability: multi-band vision

The requirement is that "see one band" grows into "see all bands at once" without a rewrite. The
seam is in place from Step 1.

### The seam

Lens behaviour is carried by a **`LensSettings` data component** on the item, not by hardcoded
renderer logic:

```java
record LensSettings(
    String bandFilter,   // "" = all bands (Step 1 default)
    boolean showLobes,   // Step 1
    boolean showLinks,   // Step 2a
    boolean showCoverage // Step 2b
)
```

Step 1 ships `bandFilter = ""` and only honours `showLobes`. Everything else is a declared, unused
flag — the same technique Phase 1 used for the azimuth/tilt/PCI seams that Phase 2 then switched on.

### How multi-band vision lands later

1. **Per-band colour.** Give each band a hue (700 = red, 900 = orange, 1800 = green, 3500 = blue).
   Lobes already know their `bandId`; this is a lookup, not a redesign.
2. **Filter cycling.** Right-click the lens to cycle `all → band_700 → band_900 → …`. Only writes a
   different string into the existing component.
3. **Band-separated coverage.** Step 2b's survey returns best-server per band rather than overall.
   The grid payload gains a band dimension; the renderer picks a layer.
4. **New bands need no code.** Bands are datapack JSON. A fifth band dropped into
   `data/rancraft/rf/bands/` appears in the lens automatically, because the filter is a string
   compared against `bandId` and the colour table falls back to a default hue.

The thing that makes all of this cheap is that **the renderer never knows what a band is** — it
reads `bandId` off a `CellParams` and asks a colour table. Adding bands is data, not code.

---

## Fidelity constraints

Carried over from the rest of the project: label abstractions, never teach a wrong thing quietly.

**Honest:**
- The lobe shape is the genuine 3GPP parabolic pattern, the same object the server evaluates with.
- Contour shells at real dB values, not decorative.
- An omni renders as a true sphere, because `OmniPattern` genuinely is flat in all directions.

**Abstractions that must stay labelled:**
- **The lobe has no absolute scale.** It is normalised to peak gain and drawn at a configurable
  world size. A bigger lobe does **not** mean more range — a 22 dBi pencil beam and a 6 dBi omni
  are drawn at the same nominal radius. It shows *shape*, not *reach*.
- **The omni sphere is a simplification.** `OmniPattern` is flat in elevation too, so its lobe is a
  perfect sphere. A real omni panel has a vertical pattern and a null straight down. RANCraft does
  not model that (see `NOTES.md`), and the render faithfully reflects the model, not reality.
- **No near-field, no polarisation, no multipath.** The lobe is a far-field pattern drawn right up
  against the antenna, where a real pattern is not yet formed.

---

## Open questions

1. **Should the lens gate on progression?** Phase 3 gates devices on `ServiceLevel` +
   `capacityTier`. A "good" lens needing a high-tier band to craft would fit, but it also delays
   the teaching tool until late game — which may be backwards for a mod whose purpose is teaching.
   *Resolved in Phase 3 slice 10 (§3C.1): no. Radio tiers gate antennas (a Sector Antenna needs a
   Wideband Radio Unit for band_3500) and devices gate on the serving band's tier, but the lens is
   not tier-gated: it shows every band from the first mast and gets a cheap early recipe (§3C.6,
   slice 16) (NOTES.md, slice 10).*
2. **Should the mast stack collapse into one cell?** Independent of this feature, but the lens will
   make the current N-cells-per-stack behaviour very visually obvious (nine overlapping spheres).
   Phase 3's "stack masts for height" needs to address it.
   *Resolved in Phase 3 slice 6 (§3B.1): yes. A contiguous column is one cell, owned by its lowest
   mast and radiating from above its top, and the lens draws one lobe there (NOTES.md, slice 6).*
3. **Lobe size units.** Currently a flat configurable world radius. An alternative is scaling by
   actual computed range per band, which would make band choice visible in the geometry — but it
   would also make the lobe enormous for band_700 and tiny for band_3500.
