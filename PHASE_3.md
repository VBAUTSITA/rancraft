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
      and fix happen in slice 1.
- [x] **Spec discrepancy, recorded as §8 of the prompt asks:** the prompt says "extend the existing
      zero-import grep assertion". **No such test exists.** Purity was only ever checked by hand
      with grep. Slice 1 creates the assertion test, covering `rf` now and `util` once it exists.
- [x] Vision Step 3a: landed first (the spec's recommendation) as slice 0. `DriveTestLog` is in
      `src/` with 22 tests; `PROTOCOL_VERSION` is now "4" and `SignalSamplePayload.VERSION` 3.

---

## Slice status (working order from §7 of the prompt)

| # | Slice | Part | Status | Commit |
|---|---|---|---|---|
| 0 | Land Vision Step 3a (drive-test trail) | pre | [~] landed, build green (203 tests); in-game checks below | af20bb4 |
| 1 | Datapack folder fix + runtime check + purity test | pre | [ ] | |
| 2 | DeviceRequirement + tests | 3A | [ ] | |
| 3 | Ranging + LocatorSolver + tests 2–12 | 3A | [ ] | |
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

Headless (verified by `./gradlew build`, 203 tests):

- [x] `DriveTestLog` pure and unit-tested in `src/` (22 tests); `rf` still has 0 Minecraft imports.
- [x] Standing still does not pile up entries; an exact replay of a cached sample adds nothing.
- [x] CSV is RFC 4180 and stays correct under a comma-decimal default locale (`es-PE`).
- [x] `SignalSamplePayload` v3 round-trips byte-exact; the serving cell survives the 4-cell cut.
- [x] The drive-test sample is a field-for-field copy of the server payload (nothing computed).
- [x] Pre-3a lens saves migrate: ALL gains the trail, a single-layer preset stays put.

Needs a human in game:

- [~] Wearing the lens (ALL or TRAIL preset) leaves coloured markers at eye height where you walked,
      in the meter's colours.
- [~] A white pillar appears where a handover fired; a yellow one where service came back after an
      outage.
- [~] `/rancraftc drivetest export` writes `run/client/rancraft/drivetests/drivetest-*.csv`, the chat
      link opens it, and Excel's Data > From Text/CSV reads the numbers correctly on `es-PE`.
- [~] `/rancraftc drivetest clear` empties the trail and reports the count.
- [~] Through a Nether portal: the Overworld trail is not drawn in the Nether, is still there on
      return, and export writes one file per dimension.
- [~] Regression: the meter HUD draws only while the meter is held (not for a trail-only wearer),
      link rays behave as before, and the handover counter still increments while standing still at
      a boundary.
- [~] The layers key cycles ALL -> Antennas -> Links -> Coverage -> Drive-test trail -> ALL.

## 3A done-when

- [ ] Meter and lens behave exactly as before the ticker refactor.
- [ ] Carrying the Locator costs no extra evaluation (EvaluationStats).
- [ ] Triangle of three masts → FIX with a sensible ±; three in a line → POOR GEOMETRY.
- [ ] Adding a band_3500 site visibly shrinks ±.
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
- (from slice 0, **for slice 4: spec vs tree**) §3A.3 says "a player is evaluated if they carry any
  device or wear a link lens", and §3A.2 says the meter "sends SignalSamplePayload only when held".
  Since Step 3a the sample must **also** go to a lens wearer whose settings show the trail
  (`LensSettings.showTrail()`), device or not. The trail is a lens path like the link rays, so it
  stays in `SignalTicker` and does not move into a device. The ticker refactor must keep
  `sendSample = meterHeld || lensShowsTrail`, or the trail silently stops unless the meter is out.
- (from slice 0, for slice 4) The payload's 4-cell cut now keeps the serving cell
  (`SignalSamplePayload.topCells`). That changed the meter HUD in one edge case (serving cell held at
  rank 5 or lower). The "byte-identical meter" regression gate compares against slice 0, not Phase 2.
- (from slice 0) §4's `PROTOCOL_VERSION` "+1 from whatever it is at start": Step 3a took it to "4",
  so 3A's bump is "4" -> "5".
- (from slice 0) The drive-test log is kept until `/rancraftc drivetest clear`, per dimension. The
  client cannot reliably tell which world it joined, so joining a different world keeps the old trail
  under the same dimension names. Possible later fix: also key the log by server address or save
  name.
- (from slice 0, optional slice 17) Adding `fix_x, fix_z, fix_err` to the CSV means appending
  components to `DriveTestLog.Sample` (a public `rf` record, so append only) and columns at the end
  of `CSV_HEADER`; `DriveTestLogTest.csvIgnoresDefaultLocale` pins the current row exactly and will
  need its expected string extended.
