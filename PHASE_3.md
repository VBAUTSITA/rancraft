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
| 4 | SignalDevice + ticker refactor + meter port (regression gate) | 3A | [~] `device/` (`SignalDevice`, `DeviceContext` per §3A.2, `DeviceMemory`, `ReplayGuard`); ticker scans carried devices, dispatches every sample (replays too), keeps "evaluated ⇒ sent"; meter ported (payload byte-identical to bd996d6, checked against the old code); stale armed candidate dropped after a gap of more than one interval. Regression gate passed headless (299 tests, 1 skipped; `runGameTestServer` 2/2); in-game checks below | a51e13a; 85bd051; 6558460 |
| 5 | Locator item, payload, HUD, rings, waypoints, emergency record | 3A | [~] `rancraft:network_locator` ("Network Locator", a `SignalDevice`, requirement NONE); fix from the full cell list every dispatch, replays reuse it; `LocatorFixPayload` v1 only while held; HUD top-left stacked under the meter's detailed readout; rings, FIX marker + error circle, AMBIGUOUS markers; 8 waypoints of the estimate; `copyOnDeath` emergency record; `PROTOCOL_VERSION` 5. Headless green (367 tests, 0 skipped; `runGameTestServer` 4/4, incl. death/clone of the record and the live-level cost: 22 µs per 8-cell solve); in-game checks below. **3A ships here** | bf5eb47; e0fcfcd; 610a79c |
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

## Slice 4 (SignalDevice, ticker refactor, meter port) checks

Headless (verified by `./gradlew build`, 299 tests, 1 skipped; method and checklist in NOTES.md,
slice 4):

- [x] `SignalDevice` and `DeviceContext` exactly as §3A.2; `CarriedDevice` and
      `carried(ServerPlayer)` as §3A.3 (main hand, offhand, hotbar 0-8; the main-hand stack, which is
      also a hotbar slot's object, is not counted twice).
- [x] Evaluated ⇒ sent survives the refactor: `sendsSample` takes the carried devices; a device only
      in the hotbar is evaluated **and** sent the sample (`SignalTickerTest`).
- [x] One sample to every carried device with its own `DeviceRequirement` verdict (LOW_TIER read off
      the serving band), fresh and replayed; a guarded device counts a replay once (`dispatch`,
      `ReplayGuard` tests).
- [x] `forget()` (logout, dimension change, respawn) and server stop also clear device state.
- [x] Meter payload byte-identical to bd996d6: the old ticker, run side by side in a throwaway test,
      produces the stored fixtures (3/3) and the same bytes for 20,000 random samples; `sendsSample`
      identical in all 49 lens states except the intended hotbar case; `chooseLinks` identical on
      5,000 random cases.
- [x] The cache decision (armed candidate skips it, epoch, site registry version, 0.5-block move,
      links never starved, same filter and cap), the stagger and the link cap pinned as pure helpers
      extracted unchanged (`SignalTickerCacheTest`, `SignalTickerTest`).
- [x] Stale armed candidate: dropped after a gap of more than one interval, re-armed at resumption,
      fires one TTT later; continuous evaluation still fires at exactly TTT (`CellSelectorTest`).
- [x] `runGameTestServer`: boots with the refactor, 2 of 2 passed (no player, so not the per-player
      path; a mock `ServerPlayer` cannot receive custom payloads, see follow-ups).

Needs a human in game (the regression gate's in-game half):

- [~] Meter in the main hand, then the offhand: the HUD looks and updates exactly as before (compact
      and detailed; right-click toggles), including NO SERVICE far from any mast.
- [~] Meter moved to a hotbar slot that is not selected: no HUD. Select it again: the HUD shows a
      current reading at once (new: before, NO SERVICE until the next evaluation if the last reading
      was over 5 s old).
- [~] Handover counter (detailed HUD "HO:"): standing still at a cell boundary it still increments
      after the time-to-trigger (the cache skip while a candidate is armed).
- [~] Stale candidate (the logic is pinned headless): set `timeToTriggerTicks` to 200 (10 s) so the
      window is wide. No lens, or the lens on ANTENNAS/COVERAGE. With the detailed HUD out, walk well
      into a neighbour's area (its RSRP clearly above the serving cell's) and stand for about 3 s, so
      the candidate is armed but has not fired. Move the meter out of the hotbar (main inventory or a
      chest) for 15 s, then take it back and watch "HO:". Fixed: it goes up about 10 s later (one full
      TTT from the resumption). Old behaviour: on the first reading. The meter must leave the hotbar:
      in the hotbar it keeps the player evaluated. Restore `timeToTriggerTicks` (40) afterwards.
- [~] Lens on LINKS or ALL: link rays as before, serving ray highlighted, at most `lensMaxLinks`
      rays; retilting an antenna while standing still updates the rays (site registry version key).
- [~] Drive-test trail (lens on TRAIL): with the meter only in the hotbar and walking across a
      boundary, the white HANDOVER pillar lands where the handover fired, not at the point where you
      later take the meter out.

## Slice 5 (Network Locator) checks

Headless (verified by `./gradlew build`, 367 tests, 0 skipped, and `./gradlew runGameTestServer`,
4 of 4; details in NOTES.md, slice 5):

- [x] The fix is computed from the evaluation's **full** cell list (not the payload's 4), at most
      `locatorMaxCells` strongest, each with a ring; below `locatorMinRsrpDbm` not ranged
      (`LocatorTrackerTest`).
- [x] A cached replay reuses the stored fix (no solve, no ground read); under `/tick freeze` a fresh
      evaluation with other cells is still solved; the previous fix marks the likely candidate
      (`LocatorTrackerTest`).
- [x] Per-player reading in a `DeviceMemory` (cleared by `forget()`); a dead player's Locator does
      nothing.
- [x] `LocatorFixPayload`: version first, every fix type round-trips, at most 8 rings, band ids
      clamped to `MAX_BAND_ID_LENGTH`, counts checked before allocation, malformed input rejected
      (`LocatorFixPayloadTest`); one payload per evaluation, only from a held Locator, main hand
      first (`NetworkLocatorTest`). `PROTOCOL_VERSION` 4 → 5.
- [x] HUD text is the §3A.6 layout line for line, all five states, metres at the server's scale,
      "99.9+" at the HDOP ceiling, no bearing without a FIX or across dimensions
      (`LocatorHudTextTest`); the top-left rule with the meter (`HudStackTest`).
- [x] Waypoints: at most 8, save selects, a full list overwrites the selected entry, cycle wraps,
      hostile data sanitised, codecs round-trip (`LocatorWaypointsTest`); bearing and distance
      (`NavigationTest`).
- [x] Emergency record: freezes a fix younger than the limit (1199 yes, 1200 no), clears the last
      fix, survives until the next death (`EmergencyRecordTest`); **at runtime**, a
      `LivingDeathEvent` on the real bus freezes the estimate (not the player's position), NeoForge's
      clone copies it (`copyOnDeath`), a too-old fix freezes nothing and is not written
      (`LocatorGameTests.emergency_record_survives_death`).
- [x] Live-level cost (`LocatorGameTests.surface_probe_cost`): 77.9 ns per ground lookup, worst-case
      fix 105 lookups = 8.2 µs, one 8-cell solve 22.2 µs; no chunk is ever loaded (`getChunkNow`).
- [x] Drawing rules: ring = cross-section of the range sphere at the slice height, likely candidate
      brighter and alike with no preference, circle segmentation (`LocatorStyleTest`).

Needs a human in game (`runClient`, creative tab "RANCraft"):

- [~] The item is "Network Locator" with the compass-face placeholder icon and a three-line tooltip
      (what it is, not GPS; use / sneak + use; waypoints n/8). Holding it shows the HUD top-left;
      in the hotbar (not selected) it shows nothing.
- [~] Meter (detailed mode, right-click) in one hand and Locator in the other: the Locator HUD sits
      under the meter's readout with no overlap; meter compact: the Locator starts at the corner.
      The meter's own HUD looks exactly as before.
- [~] Far from any mast: NO SIGNAL ("No cell at or above -100 dBm to range to"). Near one mast:
      RANGE ONLY and one ring round that mast passing near you. Two masts: AMBIGUOUS, two yellow
      markers (one on your side), the likely one brighter once you had a FIX before.
- [~] Triangle of three masts round you: FIX with a sensible ± (all band_900: about ± 10 m at 1 m
      per block) and HDOP near 1.2; the three rings cross at the green marker, the green error circle round it, all on
      the ground near your feet. Then three masts in a line (you off the line): POOR GEOMETRY
      (HDOP x).
- [~] Add a band_3500 sector nearby: "best res 3.0 m (band_3500)" and the ± shrinks (most at the edge
      of the network; about 1.3x only inside a good triangle, see the test 10 follow-up).
- [~] Walk behind a hill from a mast: the fix moves away from that mast (NLOS bias) and the rings no
      longer meet at your feet. The ± does **not** grow with the bias (it is quantisation only); it
      grows only if a cell drops below -100 dBm and the geometry gets worse.
- [~] Sneak + right-click with a FIX: "Waypoint 1/1 saved ..." and the HUD shows "WP 1/1 (saved ±...)
      0 m bearing ...". Walk away: distance and bearing follow the estimate. Right-click cycles
      between several. Without a FIX: the save is refused with the fix type named. Survives relog.
- [~] Die with the Locator carried (FIX within the last minute): after respawn, holding a Locator
      shows "Last fix before death: x .. z .. ±.. m, N s before" at the **estimate**, not your death
      spot (F3 on death screenshot to compare). Die again with no FIX in the last minute: the line is
      gone. Survives relog.
- [~] In the Nether with masts: the fix works (height falls back, labelled in NOTES.md); through a
      portal the rings and HUD of the old dimension are not drawn.
- [~] Carrying the Locator costs no extra evaluation: with the meter out, adding the Locator to the
      other hand or the hotbar changes nothing in the meter's update rate or the server's tick time
      (`/tick query`). See the follow-up on the EvaluationStats log.

## 3A done-when

- [~] Meter and lens behave exactly as before the ticker refactor. *(Headless half verified in slice
      4: payload bytes, who is evaluated, cache decision, link cut; in-game half listed above. One
      intended difference: a meter only in the hotbar keeps its carrier evaluated, §3A.3.)*
- [~] Carrying the Locator costs no extra evaluation (EvaluationStats). *(Slice 5, headless: the
      Locator is handed the one sample the ticker already produced, one evaluation per due player
      whatever they carry (`dispatchHandsTheOneSampleToEveryDevice`); its API takes no `WorldProbe`,
      so it cannot march a ray; a replay costs no solve. The EvaluationStats log only prints
      evaluations over 2 ms and counts nothing, so it cannot show this literally; see follow-ups.)*
- [~] Triangle of three masts → FIX with a sensible ±; three in a line → POOR GEOMETRY. *(Solver
      side verified headless in slice 3, tests 6 and 7; the item, payload and HUD in slice 5; in-game
      check above.)*
- [~] Adding a band_3500 site visibly shrinks ±. *(Headless: 1.29× in a good triangle, 4.17× at the
      edge of a network; see the test 10 follow-up. In-game check above.)*
- [~] Walking behind a hill visibly increases fix error. *(Headless: test 11, the NLOS bias pushes the
      fix away from the hidden cell. The **±** does not grow, by design: it is quantisation only. In
      game the error shows as the marker moving off you and the rings not meeting at your feet.)*
- [~] Rings render at measured ranges and pass through (or near) the player. *(Radius rule pinned
      headless (`LocatorStyleTest`); drawn on the ground under the slice height, see NOTES.md slice 5
      decision 9. In-game check above.)*
- [~] Emergency record survives death and shows the estimate, not the true position. *(Verified at
      runtime by `LocatorGameTests` through the real death and clone events; the HUD line is pinned by
      `LocatorHudTextTest`. In-game check above.)*

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
  Round 1 fixes are applied; rounds 2+ never ran. ~~Slice 4 rewrites the ticker's link-lens
  plumbing, so the Phase 3A review re-covers that code path.~~ *Slice 4 did not rewrite it: the link
  path was left in place (only `linkLensOf` / `linkCap` / `canReplay` extracted unchanged, and
  `chooseLinks` checked identical against the old code). A Phase 3A review should still cover it.*
- **[x] Done in slice 4 (85bd051), as proposed here.** (from slice 0 and its gate fixes, **for slice
  4: spec vs tree**) §3A.3 says "a player is
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
- **[x] Done in slice 4 part 1 (a51e13a), see the entry below.** (from slice 0 gate fixes, for
  slice 4; pre-existing since Phase 2) A handover candidate armed before evaluation pauses survives
  the pause (meter put away, lens removed or switched to
  ANTENNAS/COVERAGE). `CellSelector` measures time-to-trigger as a tick difference. So if the same
  neighbour still qualifies when evaluation resumes, the handover fires on the first evaluation
  without the A3 condition having been observed for the whole TTT. The log marks it where it fired,
  but the handover may be early. Possible fixes: drop an armed candidate when the ticker skips a
  player, or treat a gap longer than one interval as a fresh arm (needs a last-evaluated tick
  appended to `ReceiverState`).
- **[x] Done in slice 4:** the meter payload was compared against bd996d6 (slice 0 with its gate
  fixes), byte for byte, by running the old code. (from slice 0, for slice 4) The payload's 4-cell
  cut now keeps the serving cell
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
- **[x] Done in slice 4 part 1 (a51e13a).** *`ReceiverStateStore` records each state's evaluation
  tick; `evaluate()` reads the state through `resume()`, which drops an armed candidate when the gap
  exceeds one evaluation interval (`SignalTicker.staleCandidateGapTicks`, config-free; justified in
  its javadoc and NOTES.md slice 4). Tests in `CellSelectorTest` and `SignalTickerTest`.* (from the
  duplicated s0 gate-fix agent, 2026-09-28) **Stale armed handover candidate across an evaluation
  gap.** When a player stops being evaluated (meter put
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
  lookups (slice 5 measured the whole solve on live ground: 22.2 µs, 37 lookups). To revert, delete the alternative-start loop in `LocatorSolver.leastSquares` and
  `notTrappedOutsideTheFootprint`. Details and the table in NOTES.md, slice 3.
- **[ ] Open, spec conflict, decision needed (slice 3): test 10 vs `errorBlocks = HDOP × rms(σ)`.**
  Implemented to the letter. With that formula one band_3500 cell cannot cut the ± 3× on bandwidth
  alone: 1.29× in a good 120° triangle (band_900 in the same spot 1.12×). Test 10 passes (4.17×) in
  an edge-of-network geometry where most of the gain is HDOP (band_900 in the same spot 3.62×); a
  second test pins the 1.29×. Option for the spec owner: report the weighted covariance
  `sqrt(trace((HᵀWH)⁻¹))`, identical for single-band fixes (test 7 unchanged) but crediting the
  weighting: edge 7.0× (band_900 3.6×), triangle 1.40×. Even that cannot give 3× from one cell at
  fixed good geometry: one range constrains one direction. HDOP would stay unweighted either way.
- **[x] Done in slice 5.** *(a) every ring, RANGE ONLY's included, is the range sphere's horizontal
  cross-section at the slice height (the FIX's assumed eye height, else the viewer's), drawn on the
  ground under it; the HUD prints the measured slant range. (b) both AMBIGUOUS markers alike at -1
  (`LocatorStyle.candidateAlpha`). (c) "HDOP 99.9+". (d) the HUD shows `y ~87`, the assumed surface.
  (e) full `cells()` on every dispatch, replays reuse the fix, previous fix per player in a
  `DeviceMemory`. (f) `LevelSurfaceProbe` (moved unchanged from `CoverageSurveyor`), measured in a
  live level: 77.9 ns per lookup, worst case 8.2 µs per fix, a whole 8-cell solve 22.2 µs. (g) the cap
  is `LocatorFixPayload.MAX_RINGS`. (h) in the `LocatorHudText` javadoc and NOTES.md slice 5.* (from
  slice 3) (a) `RangeOnly.radius` is the measured *slant* range;
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
- **[x] Done in slice 5, with one refinement.** *(b), (d), (e) as proposed. (a) and (c) differently:
  the Locator recognises a replay by the **whole sample** (a replay is the same object, tick and all)
  and reuses the stored fix instead of solving again; state changes are idempotent under that rule,
  so no `ReplayGuard` is used. Under `/tick freeze` fresh evaluations share a tick but not their
  cells, so they are solved and the Locator follows the player. NOTES.md slice 5, decision 1.* (from
  slice 4) How the Locator should use the device framework:
  (a) compute the fix from `ctx.sample()` on **every** dispatch (it is pure and deterministic, so a
  replay gives the same fix) and guard only state changes (the per-player previous fix for
  `likely`, the emergency record stamp) with a `ReplayGuard`; (b) keep per-player state in a
  `DeviceMemory` (forgotten on logout, dimension change, respawn), not in a map of its own; the
  emergency record is a `copyOnDeath` attachment instead, which `forget()` must not touch; (c) under
  `/tick freeze` fresh evaluations share a `timestampTick`, so a tick-keyed guard sees them as
  replays (documented in `ReplayGuard`); with (a) the HUD still follows the player; (d) send
  `LocatorFixPayload` only when `held`; the `SignalSamplePayload` is already sent by the ticker;
  (e) `ReplayGuard` is per player: two Locators on one player act once per evaluation.
- **[ ] Open, for slices 5 and 8 (from slice 4).** A runtime (GameTest) check of the ticker cannot
  use `GameTestHelper.makeMockServerPlayerInLevel` as is: the mock player joins the real player list,
  so the real ticker evaluates it once it carries a device, and its connection negotiated no
  channels, so the first `SignalSamplePayload` throws `UnsupportedOperationException` in
  `NetworkRegistry.checkPacket` (NeoForge 21.1.251 sources). A runtime test needs a fake connection
  with the `rancraft` channels negotiated. Never leave a mock `ServerPlayer` carrying a device on the
  list in a game test. *Slice 5 sidestepped it: `LocatorGameTests` uses vanilla's mock `Player`
  (`makeMockPlayer`, never on the list) and posts the death and clone events itself; the per-player
  ticker path with a Locator is still only checked in game. Still open for slice 8.*
- **[ ] Open, for slice 8 (from slice 4).** `staleCandidateGapTicks` is derived from the player
  ticker's cadence (exactly one interval, guaranteed by the stagger). `FixedReceiverTicker` is
  round-robin under a time budget, "at most once per `evaluationIntervalTicks`", so its gaps can be
  longer while the budget is exhausted. Reuse `ReceiverStateStore.resume` with a threshold derived
  from that ticker's own worst case, or a budget overrun will be read as a pause and delay handovers
  (by at most one TTT each time).
- **[ ] Open, for slice 7 (from slice 4).** `SignalTicker.canReplay` is now the single place the cache
  decision is made, and `SignalTickerCacheTest` pins every current condition. The region-epoch rework
  (§3B.2) replaces the `epoch` condition there; keep the rest, including the armed-candidate skip and
  "never starve links".
- **Accepted, minor (from slice 4).** A live change of `evaluationIntervalTicks` re-phases the stagger:
  the first gap after it can reach `old + new - gcd(old, new)` ticks, so an armed candidate may be
  dropped once and re-armed (a handover delayed by at most one TTT, never early). Pinned by
  `SignalTickerTest.liveIntervalChangeStretchesOneGap`.
- **[ ] Open, spec vs tree (from slice 5).** The 3A done-when says "Carrying the Locator costs no
  extra evaluation (assert via the existing EvaluationStats log)". `EvaluationStats` is only logged
  by `SignalTicker.warnSlow`, for an evaluation over 2 ms, at most once per 30 s, and it counts
  nothing, so it cannot show the number of evaluations. Slice 5 verified the claim structurally
  (one evaluation per due player whatever they carry, pinned since slice 4; the Locator's API has no
  `WorldProbe`; a replay costs no solve) and measured the Locator's own cost (22 µs per fresh solve).
  If a runtime assertion is wanted: a per-dimension evaluation counter behind a debug command, or a
  game test once the fake-connection follow-up above is solved.
- **[ ] Open, minor (from slice 5, deviation from the §3A.6 mock-up).** Waypoints have no names (the
  mock-up's `"base"`): naming needs a text input (an anvil-style screen or a command). The HUD shows
  the number and the "±" it was saved with. A name would be an appended, optional field of
  `LocatorWaypoints.Waypoint` (codec `optionalFieldOf`, stream codec needs a protocol bump).
- **[ ] Open, minor (from slice 5).** The payload carries each AMBIGUOUS candidate's (x, z) but not
  the height the solver assumed there, so the renderer stands both markers at the viewer's feet
  height. Appending `ay, by` to `LocatorFix.Ambiguous` (an `rf` record: append only) and the payload
  (a version bump) would place them exactly.
- **[ ] Open, for slice 17 (from slice 5).** The optional `fix_x, fix_z, fix_err` CSV columns can now
  read the client's `ClientLocatorState.latest()`, but only while the Locator is held (the payload is
  not sent from the hotbar). Either document that the columns are blank then, or send the payload
  whenever a lens shows the trail.
