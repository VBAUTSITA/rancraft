# PHASE 3 BUILD PROMPT — RANCraft: Devices, Towers & Infrastructure

> The spec, verbatim as given. Progress and follow-ups are tracked in `PHASE_3.md`, not here.

## 0. Where the repo actually is

Verified against the tree, not assumed from the roadmap:

| Item | State |
|---|---|
| Phase 1, Phase 2 | done. 3GPP pattern, 4 bands, SINR, PCI planner, A3 handover, config GUI |
| RF Vision Step 1–2 | done. Lens lobes, link rays, time-sliced best-server coverage (CoverageSurveyor) |
| RF Vision Step 3 | designed only. DriveTestLog exists outside src/ |
| Tests | 161, headless. dev.rancraft.rf has 0 net.minecraft imports |
| Versions | ModPayloads.PROTOCOL_VERSION = "3", SignalSamplePayload.VERSION = 2, AntennaBlockEntity.DATA_VERSION = 2 |
| Band.capacityTier | loaded from JSON, read by nothing. Phase 3 is its first consumer |
| ReceiverStateStore<K> | already generic "so Phase 3 can key fixed devices by block position". Use it, don't copy it |
| Recipes | none exist. Every block and item is creative-only today |

The roadmap's Phase 4 coverage heatmap already shipped as Vision Step 2b. Phase 3 does not touch CoverageSurveyor except where §3B.2 says so.

### Four known problems Phase 3 must close

1. **Stacked masts are N co-channel cells.** The "(9 co-channel)" incident in VISION.md: nine Signal Masts in one column are nine cells shouting over each other. VISION.md open question 2 and the CellParams javadoc both defer this to Phase 3. §3B.1 resolves it.
2. **The block-change epoch is dimension-wide.** NOTES.md records this as a conservative simplification. Fine for a few players; unacceptable once hundreds of fixed devices each invalidate on every block anyone places anywhere. §3B.2 replaces it.
3. **Datapack folder names look pre-1.21.** The tree has `data/rancraft/loot_tables/blocks/` and `data/minecraft/tags/blocks/mineable/pickaxe.json`. Minecraft 1.21 renamed data pack folders to singular (`loot_table/`, `tags/block/`, `recipe/`). If that applies here, both blocks currently drop nothing when mined in survival, and pickaxes are not their effective tool. Nobody noticed because all testing so far has been creative. It becomes a blocker the moment §3C.6 adds recipes. Verify first (survival world, iron pickaxe, break a mast — does it drop?), then fix by moving the files. Record the result in NOTES.md either way.
4. **Nothing reads capacityTier.** Four bands exist, but band_3500 is currently strictly worse than band_1800 at everything. Phase 3 gives the player a reason to deploy it.

### Precondition: Vision Step 3a

Vision Step 3a (drive-test trail) edits SignalTicker and SignalSamplePayload. So does §3A.3 here. Do not interleave them. Either land Step 3a first (it is small, and its pure half is already written) or explicitly park it in NOTES.md before starting. Recommended: land it first. Phase 3 does not depend on it, but merging two refactors of the same ticker at once is how a handover-timer bug gets reintroduced.

## 1. Objective

Phases 1–2 made signal measurable. Vision made it visible. Phase 3 makes it something you depend on.

Three parts, each shippable on its own, in the same style as the Vision steps:

| Part | Delivers | Headline test |
|---|---|---|
| 3A | Device framework + the Network Locator (cellular positioning) | Fix accuracy visibly improves when you add a band_3500 site, and collapses when the towers are in a line |
| 3B | Mast columns, fixed receivers, Radio Link (remote redstone) | A radio link flickers at POOR service and is solid at FAIR |
| 3C | Radio tiers, backhaul, storage terminal, proximity scanner, power, recipes | A tree grown into a microwave path takes three cells off the air |

Out of scope: load/capacity sharing between users, cell sleep, multiplayer ownership, datapack propagation models, measured patterns, what-if ghost antennas. Phase 4.

## 2. Ground rules

Carried over, still binding:

- `dev.rancraft.rf` keeps zero net.minecraft imports.
- Server is authoritative. VISION.md's line holds: the client may render an antenna's declared pattern; the client may never compute what you receive.
- Records change additively. Append components, never reorder. Bump the relevant version.
- Everything tunable is JSON or ModConfigSpec.
- ≤ 1 ms/tick of RF work on the server, excluding the separately budgeted coverage surveys.

New for Phase 3:

- **Devices never compute RF.** A device consumes a SignalSample the server already produced. One evaluation per receiver per interval, no matter how many devices that receiver carries.
- **Device logic that can be pure is pure.** DeviceRequirement, Ranging, LocatorSolver, BlerModel, BinTraversal, MicrowaveLink, BackhaulGraph and PowerModel go in rf. ColumnScan (not RF) goes in a new `dev.rancraft.util` package. Extend the existing zero-import grep assertion to cover util too.
- **Logistics default off.** requireBackhaul and requirePower default to false, following the precedent requireRedstone set in Phase 1 "so a freshly placed mast just works". Turning them on is a server choice. A Phase 2 world must not go dark on upgrade.
- **Don't teach a wrong thing.** Every abstraction gets a comment at the code site and a line in NOTES.md, as the repo already does for the mod-3 penalty.

## Part 3A — Device framework and the Network Locator

### 3A.1 DeviceRequirement (rf)

```java
public record DeviceRequirement(ServiceLevel minServiceLevel, int minCapacityTier) {
    public static final DeviceRequirement NONE = new DeviceRequirement(ServiceLevel.NONE, 0);

    public Verdict check(SignalSample sample, BandTable bands);

    public enum Verdict { OK, NO_SERVICE, LOW_QUALITY, LOW_TIER }
}
```

- NO_SERVICE: `sample.isNoService()`.
- LOW_QUALITY: served, but serviceLevel is below the minimum.
- LOW_TIER: good enough quality, but the serving cell's band has capacityTier below the minimum.

The verdict carries the reason because the HUD needs to say "needs band tier 3 — you're on band_900", not just "unavailable". A player who cannot tell LOW_QUALITY from LOW_TIER will fix the wrong thing: retilting when they needed a different band, or vice versa.

Evaluate against the serving cell, via `sample.serving()`, never `cells.get(0)`. NOTES.md already records why index 0 stopped meaning "serving" in Phase 2.

### 3A.2 SignalDevice (new package dev.rancraft.device)

```java
public interface SignalDevice {
    DeviceRequirement requirement(ItemStack stack);
    /** Server side, once per evaluation. Must tolerate a replayed (cached) sample. */
    void onSample(ServerPlayer player, ItemStack stack, boolean held, DeviceContext ctx);
}

public record DeviceContext(
        SignalSample sample, DeviceRequirement.Verdict verdict,
        BandTable bands, RfConfig config, ServerLevel level, long tick) {}
```

FieldTestMeterItem becomes the first implementation (requirement NONE; sends SignalSamplePayload only when held). This is the regression reference: after the refactor the meter must behave byte-identically.

The lens stays as it is. Its link-ray path in SignalTicker is not a device and does not move.

### 3A.3 SignalTicker refactor

Today onServerTick asks two hard-coded questions, holdsMeter() and linkLensOf(). Replace the first with a scan:

```java
record CarriedDevice(ItemStack stack, SignalDevice device, boolean held) {}
static List<CarriedDevice> carried(ServerPlayer player);   // main hand, offhand, hotbar 0–8
```

- A player is evaluated if they carry any device or wear a link lens. Unchanged cost: one evaluation.
- `held` is true for main hand and offhand only. Devices in the hotbar still run (so the Locator's emergency record keeps working in a pocket) but draw no HUD.
- Dispatch the one SignalSample to every carried device after evaluation. Cached replays dispatch too. Devices must be idempotent on `sample.timestampTick()`.
- Keep every existing subtlety: cache skip while a handover candidate is armed, forget() on logout, dimension change and respawn, the lens link cap, the stagger by player.getId(). Add per-device server state to forget().

Regression gate before §3A.4: the meter HUD, link rays and handover counter behave exactly as before. Write down how you checked in NOTES.md.

### 3A.4 Ranging model (rf/Ranging.java)

Name it honestly. GPS is satellite navigation. This device locates you from cell towers: the family of network positioning methods that includes E-CID, OTDOA and NR multi-RTT. Calling it "GPS" would teach exactly the wrong thing to exactly the audience this mod is for. Item id `rancraft:network_locator`, display name "Network Locator".

Append `bandwidthMhz` to Band (default 10.0). Timing resolution comes from bandwidth, so this is what makes high band worth deploying:

```
resolution_m      = c / BW = 299.792458 / bandwidthMhz        // metres
resolution_blocks = resolution_m / metersPerBlock
```

| Band | Bandwidth | Ranging resolution |
|---|---|---|
| band_700 | 10 MHz | 30.0 m |
| band_900 | 10 MHz | 30.0 m |
| band_1800 | 20 MHz | 15.0 m |
| band_3500 | 100 MHz | 3.0 m |

Per heard cell, server side, from the evaluation's full cell list (up to maxCellsEvaluated, not the 4 the payload carries):

```
usable       = cell.rsrpDbm() >= locatorMinRsrpDbm                    // default -100
rangeBlocks  = round(cell.distanceBlocks() / res) * res
             + nlosBiasBlocksPerDb * cell.obstructionDb()              // default 0.25
sigmaBlocks  = res / sqrt(12)                                         // quantisation std dev
```

The NLOS term is the important lesson. In the real world the direct path through a hill is not the first thing to arrive; a longer reflected path is, so the measured range is biased long. RANCraft has no reflections, so the bias is a flat stand-in proportional to obstruction. Label it as such in the code. It is still directionally right: the Locator gets worse behind terrain, and high towers with clear sight lines make it better.

`distanceBlocks` is the true 3D distance the engine already computed. The Locator never sees the receiver's position; only the quantised, biased ranges.

### 3A.5 LocatorSolver (rf)

```java
public sealed interface LocatorFix {
    record NoSignal() implements LocatorFix {}
    record RangeOnly(double cx, double cz, double radius) implements LocatorFix {}           // 1 cell
    record Ambiguous(double ax, double az, double bx, double bz, int likely) implements LocatorFix {} // 2
    record PoorGeometry(double hdop, int cellsUsed) implements LocatorFix {}
    record Fix(double x, double y, double z, double hdop, double errorBlocks, int cellsUsed) implements LocatorFix {}
}

public static LocatorFix solve(List<RangeMeasurement> ranges, SurfaceProbe ground,
                               LocatorFix previous, LocatorParams params);
```

**3 or more usable cells:** 2D weighted least squares with altitude aiding.

- Take up to locatorMaxCells (default 8) usable cells.
- Initial guess: centroid of those cells' (x, z).
- Assume the receiver stands on the ground: `yRx = ground.surfaceY(round(x), round(z)) + 1.62`. SurfaceProbe already exists in rf for the coverage survey. Reuse it.
- Horizontal range per cell: `ρ_i = sqrt(max(0, r_i² − (y_i − yRx)²))`.
- Gauss–Newton on (x, z), weights 1/σ_i². At most 15 iterations; stop when the step is under 0.01 blocks. Re-derive yRx each iteration.
- HDOP = sqrt(trace((HᵀH)⁻¹)), rows of H being the unit vectors from the estimate to each cell in the horizontal plane. Unweighted, as HDOP is conventionally defined.
- HDOP > locatorMaxHdop (default 6.0), or HᵀH singular → PoorGeometry. Towers in a straight line give a singular or near-singular matrix. That is the lesson, not a bug to smooth over.
- errorBlocks = HDOP × rms(σ_i), reported as "±". Label it as a reported uncertainty, not a guarantee. The NLOS bias is systematic and is not in it.

Mixed bands just work: a band_3500 cell has a 10× smaller σ, so it dominates the fit. That is exactly why operators want wideband carriers for positioning.

**2 cells:** intersect the two horizontal circles. 0 intersections → RangeOnly on the nearer cell. 2 intersections → Ambiguous, with `likely` pointing at whichever candidate is nearer `previous` if there is one.

**1 cell:** RangeOnly. A ring, not a point.

Honest limits for NOTES.md: altitude aiding assumes you stand on the top surface, so it is wrong in caves and under overhangs (you won't hear cells underground anyway). Real positioning uses dedicated reference signals and time differences; this is modelled as absolute ranging, like multi-RTT.

### 3A.6 The Locator item

- Implements SignalDevice. Requirement NONE: it degrades by fix type rather than switching off.
- Server computes the fix every evaluation and keeps the last one per player.
- LocatorFixPayload (S2C, sent only while held): version as first field, fix type, the estimate or candidates, HDOP, ± error, cells used, and up to 8 rings (cx, cy, cz, radius, bandId) for the cells that contributed. Band ids capped at CellParams.MAX_BAND_ID_LENGTH on the wire, as every existing payload does.

HUD (top-left while held):

```
Network Locator                       FIX
Est  x 1204   z -3391   y ~87        ±9 m    HDOP 1.4
Cells 4   best res 15 m (band_1800)
WP 2/3  "base"   312 m   bearing 047°
```

States: NO SIGNAL, RANGE ONLY, AMBIGUOUS, POOR GEOMETRY (HDOP 11.2), FIX.

World render while held, from the payload only: a horizontal ring around each contributing cell at its measured range, coloured with the existing BandColours; the fix as a marker with an error circle; two markers for Ambiguous, the likely one brighter.

**Waypoints** replace the roadmap's "live map" (see §8 for why). Data component on the stack, up to 8 entries. Sneak + use saves the current estimate, not the true position. Use cycles the selected waypoint. The HUD shows distance and bearing from the current estimate to the selected waypoint. Navigation error therefore inherits fix error, and a player who saved a waypoint under a bad fix walks to the wrong place. That is the point.

**Emergency record.** NeoForge data attachment on the player, `copyOnDeath()`. On every Fix, store (dimension, x, y, z, errorBlocks, gameTime). On death, if the last fix is younger than locatorEmergencyMaxAgeTicks (default 1200), freeze it as "last fix before death"; the Locator HUD shows it after respawn until the next death. It stores the estimate, so a bad fix sends you to the wrong place. Real-world analogue: network-derived emergency caller location. Say so in a comment.

### 3A tests (headless)

1. DeviceRequirement: each verdict fires on a hand-built sample; the tier check reads the serving cell's band, not the strongest's.
2. Ranging resolution matches the table for all four bands.
3. Quantisation: a true range of 44 blocks with 30-block resolution reports 30; 46 reports 60.
4. NLOS bias is zero at zero obstruction and exactly 0.25 × dB otherwise.
5. Perfect ranges (resolution → 0, no bias) and 4 well-spread cells: the solver recovers the true (x, z) within 0.01 blocks.
6. Three collinear cells → PoorGeometry.
7. Three cells at 120° spacing → HDOP ≈ 1.15 (theoretical 2/√3) within 0.05.
8. Two cells → Ambiguous with the correct candidate marked likely when a previous fix is given.
9. One cell → RangeOnly with the measured radius.
10. Adding one band_3500 cell to three band_900 cells reduces errorBlocks by at least 3×.
11. Positive NLOS bias on one cell pulls the estimate away from that cell.
12. Solver never returns NaN or Infinity, including when the receiver stands exactly under a tower.

### 3A done when

- Meter and lens behave exactly as before the ticker refactor.
- Carrying the Locator costs no extra evaluation (assert via the existing EvaluationStats log).
- Standing inside a triangle of three masts gives FIX with a sensible ± value; lining the three masts up gives POOR GEOMETRY.
- Adding a band_3500 site nearby visibly shrinks the ± value.
- Walking behind a hill visibly increases fix error.
- Rings render at the measured ranges and pass through (or near) the player.
- The emergency record survives death and shows the estimate, not the true position.

## Part 3B — Towers and fixed devices

### 3B.1 Mast columns

A vertical run of contiguous SignalMastBlocks is one site.

- **The base owns the cell.** `cellId = base.asLong()`. Extending a tower upward keeps the cell's id and its PCI and moves only the radiating point. Using the top as owner would re-plan the PCI every time someone adds a block, which is the opposite of what a planner wants.
- **Radiating point = top.above().** CellParams already treats the radiating point as decoupled from the block position; this is the case that decoupling was left in for.
- **Only the base registers.** Every other mast in the column is structure and never transmits.
- **An antenna on top silences the column.** A SectorAntennaBlock directly above the top mast means the column is a mounting pole. The sector is its own cell, as it is today.
- **Power gate (requireRedstone):** the column is powered if any mast in it receives a signal.
- **Height cap** maxMastHeight (default 64). Blocks above the cap are structure, not signal.
- `util/ColumnScan.bounds(int y, IntPredicate isMast, int maxHeight) → (baseY, topY)`. Pure, unit-tested with a fake predicate.
- Re-scan on updateShape / neighborChanged, then call refreshRegistration() on the base's block entity only.

Breaking the base promotes the next mast up to base. It has its own block entity with defaults, so it gets a fresh PCI plan. Record that in NOTES.md as a known behaviour, not a bug.

**Lens.** LensRenderer walks client block entities and draws a lobe for every AntennaBlockEntity. Without a change it will keep drawing nine lobes on a nine-mast column. Make the client apply the same ColumnScan rule (structure is public world data, so this doesn't break the VISION principle) and draw only the transmitting cell, at the new radiating point.

**On-air flag.** Add OnAir to the block entity's update tag. It is the antenna's own state, like a furnace being lit, so it is public. The lens draws off-air cells greyed out. This matters once backhaul and power (§3C) can take a cell off the air for reasons the client can't otherwise see.

Honest note for NOTES.md: the log-distance model has no antenna-height term (Okumura–Hata does). Height helps here only by clearing obstruction, and through the vertical pattern for sectors on top. In a voxel world obstruction is the dominant effect, so this is the right first-order behaviour, but it is not the whole story.

No persisted field changes in 3B, so no DATA_VERSION bump. The collapse of existing stacks is a behaviour change. Log it once: "N stacked masts now form M columns; N−M masts stopped transmitting."

### 3B.2 Region block epochs

Replace the single per-dimension counter with per-bin counters, using the same 128-block XZ bins as `SiteRegistry.BIN_SIZE`.

- BlockEvent.BreakEvent / EntityPlaceEvent bump the bin containing the block.
- Also bump on ExplosionEvent.Detonate (every affected bin) and on piston moves. Today neither invalidates anything. Record the gaps that remain (fluid flow, fire spread, leaf decay) in NOTES.md. Most of those are 0 dB blocks anyway.
- `rf/BinTraversal.binsAlong(x0, z0, x1, z1, binSize)` is a 2D DDA over bins. Pure, tested.
- An evaluation's dependency set is the union of bins along every ray it actually marched.

Why this is correct and not an approximation: the cells that got rays were chosen by optimistic RSRP, which has no obstruction term, so which cells are marched cannot change with blocks. Cells pruned by budget could not be heard even through open air, so no block change can revive them. Only the marched rays depend on the world, so only their bins need watching. Put that argument in the class javadoc; it is the non-obvious part.

Cache validity becomes: site registry version unchanged **and** every dependency bin's epoch unchanged **and** the receiver hasn't moved. Keep a dimension-wide sum so CoverageSurveyor works untouched; it is already floored by coverageMinIntervalTicks, and changing it is not in scope.

### 3B.3 Fixed receivers

Some devices are blocks. They need evaluations too, and they're cheaper than players because they never move.

- `FixedDevice` interface: `requirement()`, `onSample(ServerLevel, BlockPos, DeviceContext)`.
- `world/FixedReceiverRegistry`, per level, keyed by `BlockPos.asLong()`.
- `ReceiverStateStore<Long>` for their handover state. The class was made generic for exactly this.
- Receiver position: the block centre.
- `world/FixedReceiverTicker`: round-robin under fixedReceiverTickBudgetMs (default 0.5), reusing the CoverageSurveyor scheduling pattern (batches, clock read between batches, rotating start). Each receiver is evaluated at most once per evaluationIntervalTicks.
- With region epochs, a fixed receiver whose bins are quiet is free in steady state: its cached sample is replayed to its device. That is why §3B.2 comes first.
- Lifecycle on all four paths: onLoad, setRemoved, chunk load, chunk unload. NOTES.md records that sector antennas once missed the chunk paths and kept transmitting from unloaded chunks. Don't repeat it.

### 3B.4 Radio Link (remote redstone)

Blocks `radio_link_transmitter` and `radio_link_receiver`, both FixedDevices.

- **Address** 0–15, stored on the block entity. Use cycles up, sneak + use cycles down, and the action bar shows the value. Called "address", not "channel", because channel already means something in this mod.
- Requirement: POOR, tier 1.
- A receiver outputs 15 if any transmitter on its address in the same dimension is powered and the last update from it was delivered. Otherwise it holds its previous output. A lost message means a stale state, not a toggled one.
- Both ends must be served. Two radios talk through the network, not to each other.
- Block state LIT while the block has service, for visible feedback. This is server-derived and pushed through vanilla block-state sync, so no client computation.

`rf/BlerModel` — block error rate from SINR:

```
BLER(sinr) = 1 / (1 + 10^((sinr − sinr50) / slope))        // sinr50 = 0 dB, slope = 2 dB
P(deliver) = (1 − BLER(sinr_tx)) × (1 − BLER(sinr_rx))
```

| SINR (dB) | −2 | 0 | 2 | 5 | 10 |
|---|---|---|---|---|---|
| BLER | 0.91 | 0.50 | 0.091 | 0.0032 | 0.00001 |

So POOR service (0–5 dB) is flaky and FAIR (≥ 5 dB) is solid. That's a real distinction the HUD bars can't convey on their own.

Randomness must stay deterministic and reproducible: draw from SplitMix64 seeded with `pos.asLong() ^ gameTime`, never `Math.random()`.

Honest labels: the curve is a generic sigmoid shaped like a link-level BLER curve, not a real MCS table. No HARQ, no retransmission. Updates arrive at 1 Hz by design; say so in the block's tooltip so nobody builds a clock with it and files a bug.

### 3B tests

- ColumnScan: single block, 9-stack, gap in the column, antenna on top, height cap.
- BinTraversal: axis-aligned, diagonal, negative coordinates, start and end in the same bin.
- Dependency set: a pruned cell contributes no bins (assert with a counting probe).
- BlerModel matches the table within 0.001 and is monotonic in SINR.
- Delivery draws are identical across two runs with the same seed and differ across ticks.

### 3B done when

- The nine-mast column from VISION.md reads as one cell, (0 co-channel), with its lobe at the top.
- Adding a mast to the top of a column keeps its PCI.
- Placing a block 500 blocks away no longer invalidates a player's cached sample. Placing one on the link path does.
- 200 radio links on a quiet server cost under 0.1 ms/tick in steady state (measure and record it).
- A radio link visibly drops updates at POOR service and is solid at FAIR.
- Unloading a chunk stops its fixed devices; reloading resumes them.

## Part 3C — Infrastructure and progression

### 3C.1 Radio tiers

Add `radioTier` to AntennaBlockEntity. This is the DATA_VERSION 2 → 3 bump.

| Block | Default tier | Bands it may use |
|---|---|---|
| Signal Mast | 1 | band_900 only (no GUI, unchanged) |
| Sector Antenna | 2 | tier ≤ 2: 700 / 900 / 1800 |
| Sector + Wideband Radio Unit | 3 | adds band_3500 |

- The Wideband Radio Unit is an item. Using it on a sector antenna raises its tier to 3 and consumes the item. Breaking the antenna drops it back.
- `UpdateCellParamsPayload.applyOn` rejects any band whose capacityTier > radioTier, on the server.
- OpenAntennaConfigPayload gains radioTier (append). The GUI band cycle shows locked bands greyed, with "needs Wideband Radio Unit".
- Migration v2 → v3: `radioTier = max(blockDefault, tierOf(currentBand))`. A sector already on band_3500 is grandfathered to tier 3. Nothing that worked before stops working.

This answers VISION.md open question 1 as well. The RF Lens is not tier-gated. It gets a cheap early recipe (§3C.6), because gating the teaching tool behind late game would be backwards for this mod.

### 3C.2 Backhaul

A cell without a path to the core network has nothing to carry, so it stays off the air. In the real network a site that loses its transport link gets taken out of service. Here it drops out of SiteRegistry when requireBackhaul is on.

Blocks:

- `core_site`: the core network / data centre.
- `backhaul_dish`: one end of a point-to-point microwave link.
- `link_tool` (item): use on dish A, then dish B, to pair them. Both block entities store the partner's position. Alignment is automatic; record that as an abstraction (real alignment is fiddly field work).

Topology (`rf/BackhaulGraph`, pure):

- **Fiber:** a cell whose column base, or a dish, within fiberRadiusBlocks (default 24) horizontally of a core in the same dimension is connected. Fiber is implicit; say so.
- **Site:** a dish within siteRadiusBlocks (default 8) of a cell's base serves that cell.
- **Microwave:** a paired dish link with its measured state.

Link budget (`rf/MicrowaveLink`, pure). Parameters from `data/rancraft/rf/backhaul/microwave.json`, a separate file on purpose so the link band never appears as a cellular band:

```json
{ "frequency_mhz": 18000, "tx_power_dbm": 20, "dish_gain_dbi": 32,
  "penetration_factor": 3.0, "up_threshold_dbm": -50, "degraded_threshold_dbm": -70,
  "fresnel_penalty_db": 6.0, "rain_db_per_km": 2.5, "thunder_db_per_km": 6.0 }
```

```
FSPL    = 20·log10(f_MHz) − 27.56 + 20·log10(d_m)          // free space, n = 2
RSL     = Tx + 2·G_dish − FSPL − Obstruction − Fresnel − Rain
UP        if RSL ≥ −50    (full rate: high-order modulation holds)
DEGRADED  if RSL ≥ −70    (adaptive modulation stepped down)
DOWN      otherwise
```

Sanity check: 1000 m gives FSPL = 117.6 dB and RSL = −33.6 dBm, 16 dB of margin. One stone block on the path costs 12 × 3.0 = 36 dB, which is DEGRADED. Two blocks is DOWN. About six leaves (18 dB) is DEGRADED. Distance barely matters; line of sight is everything. That is true of real short microwave hops, and it's the lesson.

**Fresnel clearance.** First Fresnel radius at fraction t along the path: `r(t) = sqrt(λ · d · t(1−t))`, with `λ = c / f`. At 18 GHz over 1000 m that is 2.04 m at the midpoint. Check the 60% clearance zone by marching 4 offset paths (up, down, left, right), each as two straight segments through the midpoint offset by `0.6 · r(0.5)`. If any offset path hits a block with non-zero attenuation, add fresnel_penalty_db: the ≈6 dB grazing knife-edge loss. Label it as a simplified knife-edge approximation.

**Rain fade.** Minecraft weather is real input. If it is raining at the link midpoint (`Biome.getPrecipitationAt` says rain, not snow), add `rain_db_per_km × d_km`; thunder uses the higher figure. Figures are in the spirit of ITU-R P.838 at 18 GHz; say "approximate" in the comment. A marginal link that drops in a thunderstorm is a real phenomenon and good gameplay.

Backhaul state per cell is two BFS passes over the graph:

1. Using fiber and UP links only → cells reached are FULL.
2. Adding DEGRADED links → newly reached cells are LIMITED.
3. Everything else → NONE.

Effects:

- NONE and requireBackhaul → `isTransmitting()` is false. Unregister and set OnAir false.
- LIMITED → the cell transmits, but devices served by it have their effective service level capped at FAIR in DeviceContext. This is a backhaul-limited cell, a real planning concept. The SignalSample itself stays pure RF; the cap is applied in the network layer. The meter shows `BH: LIMITED (capped FAIR)`.

Recompute when the site registry version changes, a dish is paired or unpaired, a region epoch moves on any bin a link crosses, or the weather changes. At most once per backhaulRecomputeTicks (default 100). There are few links and each is a handful of marches, so this is cheap.

**Visibility.** Link state is a measurement, so the client cannot derive it. Send BackhaulLinksPayload (endpoints plus state) to lens wearers within range, and draw the links on the lens when the lobes layer is on. Don't add a field to LensSettings. VISION_STEP3.md already notes that StreamCodec.composite tops out at 6 fields and Step 3b will take it to 6.

**Command:** `/rancraft backhaul status [radius]` lists off-air cells, LIMITED cells, and every link's RSL, margin, Fresnel state and rain loss.

### 3C.3 Wireless Storage Terminal

- Item. Sneak-use a chest or barrel to bind it (data component: dimension + position).
- The bound container must be within fiberRadiusBlocks of a core site. It is network-attached storage in the data centre, not "any chest anywhere".
- Requirement: GOOD, tier 2.
- Use → if the verdict is OK, the target's chunk is loaded and it is still a 27- or 54-slot container, open a RemoteContainerMenu wrapping ChestMenu. Its `stillValid` checks the last verdict for that player (not a fresh evaluation per tick). Losing service closes the menu. That's the lesson: your download stopped.
- Same dimension only. Never force-load a chunk; say "storage unreachable" instead.

### 3C.4 Proximity Scanner

- Item. Requirement: GOOD, tier 3. The first thing band_3500 is for.
- While held with an OK verdict, the server sends ScannerPayload (at most 16 entries: entity type, distance, bearing) for hostile mobs within 24 blocks. HUD list, no world render.
- Label it honestly: the scan is not RF physics. It stands in for a high-rate sensor feed that needs a high-capacity link. It's the gameplay reward that makes a hard-to-propagate band worth deploying, and the comment must say exactly that.

### 3C.5 Power (requirePower, default false)

- Antennas expose FE through NeoForge's block energy capability (`Capabilities.EnergyStorage.BLOCK`, registered in RegisterCapabilitiesEvent). The mast column's base holds the buffer. Buffer 10,000 FE.
- `rf/PowerModel`:

```
P_rf_W   = 10^((txDbm − 30) / 10)
FE/tick  = baseFe + paFePerWatt × P_rf_W / paEfficiency
           baseFe: mast 2, sector 4, +4 if radioTier 3
           paFePerWatt = 20, paEfficiency = 0.3
```

| Tx | P_rf | Sector FE/t |
|---|---|---|
| 20 dBm | 0.1 W | ≈ 10.7 |
| 30 dBm | 1.0 W | ≈ 70.7 |

10 dB more power costs 10× the amplifier energy. Most players never feel dBm; they feel a fuel bill.

- Out of energy → off air. Back on air when the buffer is above 10%, so it doesn't flap.
- `site_generator`: burns furnace fuel (use NeoForge's burn-time lookup), makes 40 FE/t, and pushes to adjacent FE receivers. Single slot, exposed to hoppers.
- Seam, not a feature: keep a per-cell counter of receivers served in the last N minutes. Phase 4 can build cell sleep / DTX energy saving on it. Don't implement sleep.

### 3C.6 Recipes, loot tables, creative tab

Recipes in `data/rancraft/recipe/` (1.21 singular folder; results use "id" and "count"). Loot tables for every new block in `loot_table/blocks/`, and the pickaxe tag in `tags/block/mineable/`, once §0.3 is confirmed.

| Item | Suggested ingredients | Tier |
|---|---|---|
| Field Test Meter | copper ingot, redstone, glass pane | early |
| RF Lens | copper, glass, amethyst shard | early, deliberately (§3C.1) |
| Signal Mast | iron bars, copper ingot | early |
| Network Locator | compass, copper, redstone | early |
| Radio Link TX / RX | redstone torch, copper, comparator (RX) | early |
| Sector Antenna | iron ingots, copper, redstone, Signal Mast | mid |
| Core Site | iron block, redstone block, chest | mid |
| Backhaul Dish | iron, copper, lightning rod | mid |
| Link Tool | stick, copper | mid |
| Site Generator | furnace, copper, redstone | mid |
| Wideband Radio Unit | gold, amethyst, redstone block | late-mid |
| Storage Terminal | ender pearl, copper, gold | late-mid |
| Proximity Scanner | spyglass, amethyst, gold, sculk sensor | late |

These are starting points. They're data, so they can be retuned without code.

### 3C tests

- Radio tier: a band_3500 request on a tier-2 sector is rejected; the v2 → v3 migration grandfathers it.
- MicrowaveLink: FSPL at 18 GHz / 1000 m is 117.55 dB within 0.01; the one-stone case is DEGRADED; the two-stone case is DOWN.
- Fresnel radius at 18 GHz / 1000 m midpoint is 2.04 m within 0.01.
- Rain adds exactly rate × d_km; snow adds nothing.
- BackhaulGraph: FULL via fiber, FULL via UP chain, LIMITED via one DEGRADED hop, NONE when isolated, and a DEGRADED path loses to an UP path when both exist.
- LIMITED caps DeviceContext service at FAIR while the underlying SignalSample is untouched.
- PowerModel matches the table and scales exactly 10× in the PA term per 10 dB.

### 3C done when

- A tier-2 sector cannot be set to band_3500 until it gets a Wideband Radio Unit.
- With requireBackhaul on, three sites chained by microwave from a core are on air; breaking the middle dish takes the far site off air, and the lens shows it greyed.
- A tree grown into a link path turns it DEGRADED, the cells behind it read BH: LIMITED, and the Storage Terminal stops working while the Radio Link keeps working.
- A marginal link drops in a thunderstorm and recovers after.
- The Proximity Scanner works on band_3500 at GOOD and refuses on band_1800 with "needs tier 3".
- With requirePower on, a 30 dBm sector burns through fuel about 7× faster than a 20 dBm one.
- Every block and item is craftable in survival, and every block drops itself.
- With both logistics flags off (the default), a Phase 2 world plays exactly as before.

## 4. Versions and payloads

| Thing | Change | When |
|---|---|---|
| ModPayloads.PROTOCOL_VERSION | +1 from whatever it is at start (Step 3a may have bumped it) | 3A |
| LocatorFixPayload | new, versioned | 3A |
| Band | + bandwidthMhz (append) | 3A |
| AntennaBlockEntity update tag | + OnAir | 3B |
| AntennaBlockEntity.DATA_VERSION | 2 → 3 (radioTier) | 3C |
| OpenAntennaConfigPayload | + radioTier (append) | 3C |
| BackhaulLinksPayload, ScannerPayload | new, versioned | 3C |
| LensSettings | unchanged (6-field codec ceiling) | — |

Band JSON gains `"bandwidth_mhz": 10 / 10 / 20 / 100`.

## 5. Config additions (RanCraftConfig, COMMON)

```
locatorMinRsrpDbm           = -100.0
locatorMaxCells             = 8
locatorMaxHdop              = 6.0
nlosBiasBlocksPerDb         = 0.25
locatorEmergencyMaxAgeTicks = 1200
maxMastHeight               = 64
fixedReceiverTickBudgetMs   = 0.5
blerSinr50Db                = 0.0
blerSlopeDb                 = 2.0
requireBackhaul             = false
fiberRadiusBlocks           = 24
siteRadiusBlocks            = 8
backhaulRecomputeTicks      = 100
enableRainFade              = true
requirePower                = false
```

Engine-facing values go into RfConfig (append). Server-cost and gameplay values stay out of it, as the lens settings already do.

## 6. Fidelity notes — add to NOTES.md

**Real, modelled faithfully:** ranging resolution set by bandwidth (c/BW); NLOS ranging bias in the right direction; dilution of precision and the collinear-tower failure; altitude aiding and where it breaks; the BLER-vs-SINR sigmoid shape; microwave free-space loss and LOS dependence; first-Fresnel-zone radius and 60% clearance; rain fade rising with frequency; adaptive-modulation states on the link; a backhaul-limited cell; PA efficiency making Tx power an energy cost.

**Abstracted, labelled at the code site:** NLOS bias as a flat per-dB constant; absolute ranging instead of time differences; the generic BLER curve with no MCS table and no HARQ; knife-edge loss as a flat 6 dB; automatic dish alignment; implicit fiber by radius; the Proximity Scanner's detection; the backhaul cap as a flat FAIR ceiling.

**Deliberately absent:** load and capacity sharing, scheduler, cell sleep, per-user throughput, height gain in propagation. All are Phase 4 candidates.

## 7. Working order

Commit per slice. Update NOTES.md in every slice.

1. Precondition (Vision 3a landed or parked). Verify the datapack-folder issue and fix it.
2. DeviceRequirement + tests.
3. Ranging + LocatorSolver + tests 2–12. Get the geometry right headless first, as with the antenna pattern in Phase 2.
4. SignalDevice + ticker refactor + meter port. Pass the regression gate before continuing.
5. Locator item, payload, HUD, rings, waypoints, emergency record. **3A ships here.**
6. ColumnScan + mast columns + lens column/on-air handling.
7. BinTraversal + region epochs + cache rework.
8. Fixed receiver registry and ticker.
9. BlerModel + Radio Link. **3B ships here.**
10. Radio tiers + v3 migration.
11. MicrowaveLink + BackhaulGraph + tests.
12. Core site, dish, link tool, backhaul state, lens lines, `/rancraft backhaul status`.
13. Storage Terminal.
14. Proximity Scanner.
15. Power + generator.
16. Recipes and loot tables, then a full survival playthrough. **3C ships here.**

If Vision 3a is merged, an optional last slice appends fix_x, fix_z, fix_err to the drive-test CSV.

## 8. Decisions already made — don't reopen without a reason

- **No "live map" item.** Vanilla map rendering (held-map, item frames) is keyed on the vanilla filled-map item, so a custom map item needs renderer hooks this phase doesn't need. Locator waypoints teach the same lesson ("your position estimate is only as good as your coverage") at a fraction of the risk. Revisit in Phase 4.
- **No mob radar as RF.** The scanner is labelled as a capacity reward, not a sensing model.
- **Base owns the column's cell, not the top.** PCI stability beats simplicity.
- **Logistics default off.** A teaching tool that goes dark on upgrade teaches nothing.

If an instruction here conflicts with something in the tree that this prompt didn't anticipate, stop and say so in NOTES.md before working around it. That's how the repo got this far.
