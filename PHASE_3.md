# Phase 3 — tracker and follow-ups

Spec: `PHASE_3_PROMPT.md` (verbatim). This file is the checklist and the place follow-ups get
written. Detailed engineering notes (deviations, measurements, fidelity) go in `NOTES.md`.

Legend: `[x]` done and verified · `[~]` partly done / needs a manual in-game check · `[ ]` not started

---

## §0 Scout — verified against the tree before starting

- [x] Phases 1–2 and RF Vision Steps 1–2 done; **161 tests** green; `rf` has 0 Minecraft imports
      (checked by grep).
- [x] Versions match the spec: `PROTOCOL_VERSION = "3"`, `SignalSamplePayload.VERSION = 2`,
      `AntennaBlockEntity.DATA_VERSION = 2`.
- [x] `Band.capacityTier` is read by nothing in `src/main` (only a Javadoc mention in `ServiceLevel`).
- [x] No recipes exist anywhere in `src/main/resources`.
- [x] `ReceiverStateStore<K>` is generic. Reuse it for fixed receivers (§3B.3).
- [x] `SurfaceProbe` already exists in `rf` (the solver's altitude aiding reuses it).
- [x] `CellParams.MAX_BAND_ID_LENGTH` exists and existing payloads cap band ids with it.
- [x] **Known problem 3 (datapack folders) confirmed from vanilla's own 1.21.1 jar:** vanilla ships
      `data/minecraft/loot_table/`, `recipe/`, `tags/block/`, `tags/item/`. RANCraft ships
      `loot_tables/` and `tags/blocks/`, which 1.21 no longer reads. Expected symptom: masts and
      sectors drop nothing in survival, and pickaxes are not their effective tool. Runtime check
      and fix happen in slice 1. **Slice 1: confirmed at runtime** (NeoForge GameTest on the old
      layout: no tag, bare-hand speed, no harvest, empty loot table, for both blocks) **and fixed**
      (folders moved; same test passes). Details in NOTES.md, Phase 3 slice 1.
- [x] **Spec discrepancy, recorded as §8 of the prompt asks:** the prompt says "extend the existing
      zero-import grep assertion". **No such test exists.** Purity was only ever checked by hand
      with grep. Slice 1 creates the assertion test, covering `rf` now and `util` once it exists.
- [x] Vision Step 3a: landed first (the spec's recommendation) as slice 0. `DriveTestLog` is in
      `src/` with 22 tests; `PROTOCOL_VERSION` is now "4" and `SignalSamplePayload.VERSION` 3.

---

## Slice status (working order from §7 of the prompt)

| # | Slice | Part | Status | Commit |
|---|---|---|---|---|
| 0 | Land Vision Step 3a (drive-test trail) | pre | [~] landed + gate fixes, build green (210 tests); in-game checks below | af20bb4; gate fixes 3906418 |
| 0a | Vision Step 3a follow-ups: trail cleared on logout, export link opens the folder | pre | [~] both fixed, build green (224 tests, 1 skipped); two in-game checks below | 0afda73; docs f2f8453 |
| 1 | Datapack folder fix + runtime check + purity test | pre | [x] folders fixed, harvest game test red before / green after, purity test in `build` (219 tests, 1 skipped until `util` exists); one quick in-game look below | 212f86c; 1471bf3 |
| 2 | DeviceRequirement + tests | 3A | [x] `rf/DeviceRequirement` per §3A.1, verdict order NO_SERVICE → LOW_QUALITY → LOW_TIER on the serving cell; test 1 green (7 tests, 231 total, 1 skipped) | 4ab5967 |
| 3 | Ranging + LocatorSolver + tests 2–12 | 3A | [x] `Band.bandwidthMhz` + JSON 10/10/20/100, `Ranging`, `RangeMeasurement`, `LocatorFix`, `LocatorParams`, `LocatorSolver`; 5 locator tunables in `RanCraftConfig`, 4 of them in `RfConfig`; tests 2–12 green (259 total, 1 skipped). Two recorded deviations (floor not round; extra solver starts) and one spec conflict (test 10 vs `errorBlocks`), see follow-ups | 0837945 |
| 4 | SignalDevice + ticker refactor + meter port (regression gate) | 3A | [ ] | |
| 5 | Locator item, payload, HUD, rings, waypoints, emergency record | 3A | [ ] | |
| 6 | ColumnScan + mast columns + lens column/on-air | 3B | [ ] | |
| 7 | BinTraversal + region epochs + cache rework | 3B | [ ] | |
| 8 | Fixed receiver registry + ticker | 3B | [ ] | |
| 9 | BlerModel + Radio Link | 3B | [ ] | |
| 10 | Radio tiers + v3 migration | 3C | [ ] | |
| 11 | MicrowaveLink + BackhaulGraph + tests | 3C | [ ] | |
| 12 | Core site, dish, link tool, backhaul state, lens lines, `/rancraft backhaul status` | 3C | [ ] | |
| 13 | Storage Terminal | 3C | [ ] | |
| 14 | Proximity Scanner | 3C | [ ] | |
| 15 | Power + generator | 3C | [ ] | |
| 16 | Recipes + loot tables + survival playthrough | 3C | [ ] | |
| 17 | (optional) fix_x/fix_z/fix_err in drive-test CSV | — | [ ] | |

---

## Slice 0 (Vision Step 3a) checks

Headless (verified by `./gradlew build`, 210 tests since the gate fixes):

- [x] `DriveTestLog` pure and unit-tested in `src/` (23 tests); `rf` still has 0 Minecraft imports.
- [x] Standing still does not pile up entries; an exact replay of a cached sample adds nothing.
- [x] CSV is RFC 4180 and stays correct under a comma-decimal default locale (`es-PE`).
- [x] `SignalSamplePayload` v3 round-trips byte-exact; the serving cell survives the 4-cell cut.
- [x] The drive-test sample is a field-for-field copy of the server payload (nothing computed).
- [x] Pre-3a lens saves migrate: ALL gains the trail, a single-layer preset stays put.
- [x] *(gate fix)* Every evaluation sends the sample, a LINKS-only lens included, and the evaluated
      set is unchanged: `SignalTicker.sendsSample`, pinned by `SignalTickerTest` (meter, ALL,
      TRAIL, LINKS, ANTENNAS, COVERAGE, no lens, band filter, hand-edited flags).
- [x] *(gate fix)* Why that matters, pinned in the pure log: handovers across evaluations that were
      never sent would land as one false HANDOVER on the next sample
      (`DriveTestLogTest.unseenHandoversLandOnTheNextSample`).

Needs a human in game:

- [~] Wearing the lens (ALL or TRAIL preset) leaves coloured markers at eye height where you walked,
      in the meter's colours.
- [~] A white pillar appears where a handover fired; a yellow one where service came back after an
      outage.
- [~] *(gate fix)* On the LINKS preset, walk across two cell boundaries (or A→B→A), then cycle to
      TRAIL or take the meter out: a white pillar at each handover point and **none at the switch
      point**; the exported CSV has `HANDOVER` on those rows only.
- [~] `/rancraftc drivetest export` writes `run/client/rancraft/drivetests/drivetest-*.csv`;
      *(follow-up 0a)* clicking the underlined file name in chat opens the `drivetests` folder in
      Explorer with the new file in it, and does **not** open the CSV in Excel; Excel's Data > From
      Text/CSV then reads the numbers correctly on `es-PE`.
- [~] `/rancraftc drivetest clear` empties the trail and reports the count.
- [~] Through a Nether portal: the Overworld trail is not drawn in the Nether, is still there on
      return, and export writes one file per dimension.
- [~] Regression: the meter HUD draws only while the meter is held (not for a trail-only or, since
      the gate fix, a links-only wearer, who both receive samples now), link rays behave as before,
      and the handover counter still increments while standing still at a boundary.
- [~] The layers key cycles ALL -> Antennas -> Links -> Coverage -> Drive-test trail -> ALL.

## Slice 0a (Vision Step 3a follow-ups) checks

Headless (verified by `./gradlew build`, 224 tests, 1 skipped):

- [x] Logging out clears every dimension's drive-test log (`ClientEvents.onLoggingOut` →
      `ClientDriveTest.clear()`); respawn and dimension change (`onClone`) keep it, per dimension
      (`ClientDriveTestTest`, 4 tests, driving the real handlers).
- [x] After a logout the next world's first Overworld sample starts a fresh one-entry log with no
      event (before: appended to the old trail and classified RESELECTION against it).
- [x] The export chat link shows the file name but its `OPEN_FILE` target is the absolute,
      normalised `drivetests` folder, never the `.csv` (`DriveTestCommandsTest.linkOpensTheFolder`).
- [x] The three new assertions fail with the old behaviour put back temporarily (3 of 8 in the two
      classes), then pass with the fix.
- [x] Checked in the patched sources: every way into or out of a world goes through
      `Minecraft.disconnect`, which fires `LoggingOut`; dimension change and respawn do not (details
      in NOTES.md).

Needs a human in game:

- [~] Export, then click the file name in chat: Explorer opens `run/client/rancraft/drivetests/`
      (Excel does not start). Merged into the slice 0 export check above.
- [~] Walk with the lens on TRAIL in world A, quit to the title screen, open world B: no markers from
      A are drawn, and `/rancraftc drivetest export` in B writes only B's rows (or reports nothing
      recorded before B has samples). A portal trip inside one world still keeps both trails (slice 0
      check above).

## Slice 1 (datapack folders, purity test) checks

Headless:

- [x] Old layout, `./gradlew runGameTestServer`: `harvestgametests.signal_mast` and
      `.sector_antenna` both fail (not in `#minecraft:mineable/pickaxe`, iron pickaxe at bare-hand
      speed 1.0, `canHarvestBlock` false, loot table yields nothing). Gradle exits non-zero (2).
- [x] New layout (`loot_table/blocks/`, `tags/block/mineable/`): both pass, `BUILD SUCCESSFUL`.
- [x] `HarvestGameTests` stays as a regression check, one generated test per `ModBlocks.BLOCKS`
      entry; `runGameTestServer` is in the command tables of `CLAUDE.md` and `README.md`.
- [x] `PackagePurityTest` runs in `./gradlew build`: `rf` has 0 references to `net.minecraft`,
      `net.neoforged`, `com.mojang` or game-side `dev.rancraft` packages; `util` is skipped until it
      exists. A probe file in `rf` turned it red; no probe remains.
- [x] The built jar has only the singular folders and no probe class.

Needs a human in game:

- [~] Survival world, iron pickaxe: a Signal Mast and a Sector Antenna each break in under a second
      (with the crack animation) and pop out as an item. The game test covers the server's
      decisions, not the client side.

## Slice 2 (DeviceRequirement) and slice 3 (Ranging, LocatorSolver) checks

Headless (verified by `./gradlew build`, 259 tests, 1 skipped):

- [x] Test 1: every verdict fires; the tier is read off the serving cell, not the strongest
      (`DeviceRequirementTest`, both directions).
- [x] Tests 2–4: resolution from the shipped JSON matches the table; 44 → 30 and 46 → 60; NLOS bias
      0 at 0 dB and exactly 0.25 × dB, and not in sigma (`RangingTest`).
- [x] Tests 5–12 (`LocatorSolverTest`): perfect ranges within 0.01 (flat, slope, unloaded centroid);
      collinear → POOR GEOMETRY; HDOP 2/√3 within 0.05 (in fact 1e-6); two cells → AMBIGUOUS with
      the right `likely`; one cell → RANGE ONLY with the measured radius; test 10 at ≥ 3× (in an
      edge-of-network geometry, see follow-ups); NLOS pushes the fix away; never NaN/Infinity
      (under a tower, bit-exactly on a tower, degenerate inputs, 5,000 random scenes).
- [x] Each deviation's test fails with the spec's version put back temporarily (`round`; no extra
      starts) and the zero-row guard's test fails without the guard.
- [x] `runGameTestServer`: bands load with `bandwidth_mhz` (4 bands, no parse error), 2 of 2 game
      tests pass.

Nothing in game yet: slices 2 and 3 have no caller until slices 4 and 5.

## 3A done-when

- [ ] Meter and lens behave exactly as before the ticker refactor.
- [ ] Carrying the Locator costs no extra evaluation (EvaluationStats).
- [ ] Triangle of three masts → FIX with a sensible ±; three in a line → POOR GEOMETRY. *(Solver
      side verified headless in slice 3, tests 6 and 7; the item is slice 5.)*
- [ ] Adding a band_3500 site visibly shrinks ±. *(Headless: 1.29× in a good triangle, 4.17× at the
      edge of a network; see the test 10 follow-up.)*
- [ ] Walking behind a hill visibly increases fix error.
- [ ] Rings render at measured ranges and pass through (or near) the player.
- [ ] Emergency record survives death and shows the estimate, not the true position.

## 3B done-when

- [ ] The nine-mast column reads as one cell, (0 co-channel), lobe at the top.
- [ ] Adding a mast to the top of a column keeps its PCI.
- [ ] A block placed 500 blocks away no longer invalidates a cached sample; one on the link path does.
- [ ] 200 radio links on a quiet server cost < 0.1 ms/tick in steady state (measured).
- [ ] A radio link drops updates at POOR and is solid at FAIR.
- [ ] Unloading a chunk stops its fixed devices; reloading resumes them.

## 3C done-when

- [ ] A tier-2 sector cannot use band_3500 until it gets a Wideband Radio Unit.
- [ ] With requireBackhaul on: three microwave-chained sites are on air; breaking the middle dish takes
      the far site off air and the lens greys it.
- [ ] A tree grown into a link path → DEGRADED, cells behind read BH: LIMITED, Storage Terminal stops,
      Radio Link keeps working.
- [ ] A marginal link drops in a thunderstorm and recovers after.
- [ ] Proximity Scanner works on band_3500 at GOOD, refuses on band_1800 with "needs tier 3".
- [ ] With requirePower on, a 30 dBm sector burns fuel ~7× faster than a 20 dBm one.
- [ ] Every block and item is craftable in survival and every block drops itself.
- [ ] With both logistics flags off (default), a Phase 2 world plays exactly as before.

---

## Follow-ups

Anything found along the way that is out of scope for the current slice goes here, with where it
came from.

- (from RF Vision Step 2) The Step 2 adversarial review stopped after round 1 (account usage limit).
  Round 1 fixes are applied; rounds 2+ never ran. Slice 4 rewrites the ticker's link-lens
  plumbing, so the Phase 3A review re-covers that code path.
- (from slice 0 and its gate fixes, **for slice 4: spec vs tree**) §3A.3 says "a player is
  evaluated if they carry any device or wear a link lens", and §3A.2 says the meter "sends
  SignalSamplePayload only when held". The tree's rule is stricter: **every evaluation sends the
  sample** (`SignalTicker.sendsSample`, pinned by `SignalTickerTest`). The drive-test log reads
  handovers off the server's counter between consecutive samples. An evaluation that is not sent
  hides its handovers, and the next sample the client gets shows them as one false HANDOVER pillar.
  The slice 0 gate review found exactly that for a LINKS-only lens; the old rule here,
  `sendSample = meterHeld || lensShowsTrail`, was the bug. For slice 4:
  (a) the trail and the link rays are lens paths, so they stay in `SignalTicker` and do not move
  into a device;
  (b) a device that triggers an evaluation from the hotbar (the Locator "in a pocket") must get the
  sample sent too. "Sends SignalSamplePayload only when held" therefore becomes "the HUD draws only
  when held", which `SignalHudOverlay` already enforces on the client. Send the sample once per
  evaluation from the ticker, not from the meter's `onSample`. Extend `sendsSample` and its test with
  the carried-device input rather than adding a second gate;
  (c) don't replace the rule with a client-side tick-gap check: a cached replay carries the tick of
  the evaluation it replays.
- (from slice 0 gate fixes, for slice 4; pre-existing since Phase 2) A handover candidate armed
  before evaluation pauses survives the pause (meter put away, lens removed or switched to
  ANTENNAS/COVERAGE). `CellSelector` measures time-to-trigger as a tick difference. So if the same
  neighbour still qualifies when evaluation resumes, the handover fires on the first evaluation
  without the A3 condition having been observed for the whole TTT. The log marks it where it fired,
  but the handover may be early. Possible fixes: drop an armed candidate when the ticker skips a
  player, or treat a gap longer than one interval as a fresh arm (needs a last-evaluated tick
  appended to `ReceiverState`).
- (from slice 0, for slice 4) The payload's 4-cell cut now keeps the serving cell
  (`SignalSamplePayload.topCells`). That changed the meter HUD in one edge case (serving cell held at
  rank 5 or lower). The "byte-identical meter" regression gate compares against slice 0 (with its
  gate fixes), not Phase 2. The gate fixes add one more HUD edge case: a LINKS-only wearer who takes
  the meter out sees a current reading at once rather than the last one received (see NOTES.md).
- (from slice 0) §4's `PROTOCOL_VERSION` "+1 from whatever it is at start": Step 3a took it to "4",
  so 3A's bump is "4" -> "5".
- **[x] Done in slice 0a (0afda73).** (from slice 0) The drive-test log is kept until
  `/rancraftc drivetest clear`, per dimension. The client cannot reliably tell which world it joined,
  so joining a different world keeps the old trail under the same dimension names. Possible later
  fix: also key the log by server address or save name. *Fixed instead by clearing it on logging
  out; see the s0 gate item below.*
- (from slice 0, optional slice 17) Adding `fix_x, fix_z, fix_err` to the CSV means appending
  components to `DriveTestLog.Sample` (a public `rf` record, so append only) and columns at the end
  of `CSV_HEADER`; `DriveTestLogTest.csvIgnoresDefaultLocale` pins the current row exactly and will
  need its expected string extended.
- (from slice 1, **for slices 6 and 16; deviation from the brief**) `PackagePurityTest` is
  stricter than "zero net.minecraft / net.neoforged / com.mojang imports": `rf` and `util` also may
  not name any other `dev.rancraft` package (the root package included), because every other
  package is game code and NeoForge is on the test classpath, so the unit tests would not catch
  Minecraft arriving one step removed. `rf` and `util` may use each other. Pure code must live in
  one of the two (e.g. ColumnScan in `util`, as §2 says).
- (from slice 1, for slice 16) `HarvestGameTests` generates one test per `ModBlocks.BLOCKS` entry
  and requires each block to be pickaxe-mineable and to drop itself. A new block needs a loot table
  in `data/rancraft/loot_table/blocks/` and an entry in `data/minecraft/tags/block/mineable/`
  (or an explicit exemption in the test). Run `./gradlew runGameTestServer`; it is not part of
  `build`.
- (from slice 1) A game test run where nothing registered passes ("All 0 required tests passed",
  exit 0). That happens if `neoforge.enabledGameTestNamespaces` leaves the `gameTestServer` run in
  `build.gradle`, or the test template leaves the `rancraft` namespace. When reading a green run,
  check the "N tests are now running" line. Possible later guard: a `RegisterGameTestsEvent`-time or
  post-run count check.
- **[ ] Open, for slice 4.** (from the duplicated s0 gate-fix agent, 2026-09-28) **Stale armed
  handover candidate across an evaluation gap.** When a player stops being evaluated (meter put
  away, lens switched to ANTENNAS/COVERAGE), their ReceiverState freezes, including an armed
  candidate and its candidateSinceTick. On the first evaluation after they resume, CellSelector
  computes a huge heldTicks, so if the same neighbour still qualifies, the handover fires
  immediately: the time-to-trigger was never observed through the gap. Phase 2 behaviour, not a 3a
  regression (the drive-test log records where the server really fired). Candidate fix, fits slice
  4: drop an armed candidate when the gap since the player's last evaluation exceeds about one
  evaluation interval. Needs a headless test in CellSelectorTest or around the ticker. *(Same defect
  as the "handover candidate armed before evaluation pauses" item above, found independently; this
  entry adds the candidate fix and the test to write.)*
- **[x] Done in slice 0a (0afda73).** (from the s0 gate, minor, not fixed by the gate) Drive-test
  log survives disconnect and bleeds across worlds (keyed by dimension only). Simplest fix: clear
  ClientDriveTest on logging out. *Done: `ClientEvents.onLoggingOut` clears it; respawn and dimension
  change still keep it per dimension. Pinned by `ClientDriveTestTest`.*
- **[x] Done in slice 0a (0afda73).** (from the s0 gate, minor, not fixed by the gate) Export chat
  link opens the CSV itself (OPEN_FILE), which on es-PE Excel misparses; point the ClickEvent at the
  drivetests folder instead. *Done: the link text is still the file name; the click opens the
  folder, as vanilla's profiler link does. Pinned by `DriveTestCommandsTest.linkOpensTheFolder`; the
  manual export check above is updated.*
- **[ ] Open, minor.** (from slice 0a) A proxy (Velocity, BungeeCord) that moves a player to another
  backend server without a reconnect fires no `LoggingOut`: a configuration-phase switch goes through
  `Minecraft.clearClientLevel`, and a respawn-packet switch looks like a dimension change. The
  drive-test trail then carries over to the new server, as the meter and lens readouts already do.
  Not reachable on vanilla or NeoForge alone. Possible fix: also clear on
  `ClientPlayerNetworkEvent.LoggingIn`, which `handleLogin` fires again after a reconfiguration
  (checked in the patched sources); the respawn-packet case would remain. Details in NOTES.md.
- **[x] Worked around in slice 3, spec vs tree.** §3A.5 reads the altitude-aiding column as
  `surfaceY(round(x), round(z))`. The tree's convention for "the column a point is in" is `floor`
  (`BlockPos.containing`); `round` reads the neighbouring column for half of all positions, a wrong
  height on any slope. `LocatorSolver` uses `floor`; `perfectRangesOnASlope` fails with `round`.
- **[ ] Open, decision needed (slice 3 deviation from §3A.5's algorithm).** Gauss-Newton from the
  centroid alone lands in a wrong local minimum for 10.5–16.7 % of FIX results once the receiver is
  outside the cells' footprint, **even with perfect ranges**, and reports it with a small ± (measured
  over 20,000 seeded scenes per case; e.g. FIX 390 blocks from the truth, "± 0", HDOP 4.3). Slice 3
  keeps the centroid as the first start and also starts from the circle crossings of the 3 strongest
  cells, taking a run that ends elsewhere with a smaller weighted residual: perfect ranges 0 %,
  band_900 1.3–5.2 % (the rest is genuine mirror ambiguity). When the centroid's run is the best fit,
  results are identical to the spec's. Cost about 19 µs per 8-cell fix plus the in-game surface
  lookups. To revert, delete the alternative-start loop in `LocatorSolver.leastSquares` and
  `notTrappedOutsideTheFootprint`. Details and the table in NOTES.md, slice 3.
- **[ ] Open, spec conflict, decision needed (slice 3): test 10 vs `errorBlocks = HDOP × rms(σ)`.**
  Implemented to the letter. With that formula one band_3500 cell cannot cut the ± 3× on bandwidth
  alone: 1.29× in a good 120° triangle (band_900 in the same spot 1.12×). Test 10 passes (4.17×) in
  an edge-of-network geometry where most of the gain is HDOP (band_900 in the same spot 3.62×); a
  second test pins the 1.29×. Option for the spec owner: report the weighted covariance
  `sqrt(trace((HᵀWH)⁻¹))`, identical for single-band fixes (test 7 unchanged) but crediting the
  weighting: edge 7.0× (band_900 3.6×), triangle 1.40×. Even that cannot give 3× from one cell at
  fixed good geometry: one range constrains one direction. HDOP would stay unweighted either way.
- **[ ] Open, for slice 5 (from slice 3).** (a) `RangeOnly.radius` is the measured *slant* range;
  decide how the ring is drawn (at the cell's height it is the widest circle of the range sphere).
  (b) `Ambiguous.likely` can be `NO_PREFERENCE` (-1): draw both markers alike then. (c)
  `PoorGeometry.hdop` is capped at 99.9 ("99.9 or worse", also for a singular geometry). (d) `Fix.y`
  is the assumed eye height (ground + 1.62), not a measurement. (e) Feed `Ranging.measure` the
  sample's **full** `cells()` (not the payload's 4), also on cached replays, and keep the per-player
  previous fix for `likely`. (f) The game-side `SurfaceProbe` must never load a chunk (reuse
  `CoverageSurveyor`'s), and its cost per fix must be measured: up to 7 runs × 15 lookups. (g)
  `locatorMaxCells` is capped at 8 in the config because the payload carries 8 rings; point the cap
  at the payload's constant once it exists. (h) Quantisation is deterministic: a player standing
  still sees a fixed error, not noise; say so where the ± is explained.
