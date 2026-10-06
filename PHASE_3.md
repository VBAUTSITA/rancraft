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
- [x] Vision Step 3a: landed first (the spec's recommendation) as slice 0 (af20bb4; gate fixes
      3906418; follow-ups 0afda73), before any §3A.3 ticker edit, so the two refactors were never
      interleaved. `DriveTestLog` is in `src/` (22 tests at landing, 25 now); slice 0 took
      `PROTOCOL_VERSION` to "4" (slice 5 then to "5") and `SignalSamplePayload.VERSION` to 3. Its
      remaining checks are in-game only (slice 0 and 0a sections below, and the Part 3A checklist at
      the end).

---

## Slice status (working order from §7 of the prompt)

| # | Slice | Part | Status | Commit |
|---|---|---|---|---|
| 0 | Land Vision Step 3a (drive-test trail) | pre | [~] landed + gate fixes, build green (210 tests); in-game checks below | af20bb4; gate fixes 3906418; tracker 30e5f23, 7b35d4f |
| 0a | Vision Step 3a follow-ups: trail cleared on logout, export link opens the folder | pre | [~] both fixed, build green (224 tests, 1 skipped); two in-game checks below | 0afda73; docs f2f8453; tracker 4a2aae2 |
| 1 | Datapack folder fix + runtime check + purity test | pre | [x] folders fixed, harvest game test red before / green after, purity test in `build` (219 tests, 1 skipped until `util` exists); one quick in-game look below | 212f86c; 1471bf3; tracker ac36a74 |
| 2 | DeviceRequirement + tests | 3A | [x] `rf/DeviceRequirement` per §3A.1, verdict order NO_SERVICE → LOW_QUALITY → LOW_TIER on the serving cell; test 1 green (7 tests, 231 total, 1 skipped) | 4ab5967 |
| 3 | Ranging + LocatorSolver + tests 2–12 | 3A | [x] `Band.bandwidthMhz` + JSON 10/10/20/100, `Ranging`, `RangeMeasurement`, `LocatorFix`, `LocatorParams`, `LocatorSolver`; 5 locator tunables in `RanCraftConfig`, 4 of them in `RfConfig`; tests 2–12 green (259 total, 1 skipped). Two recorded deviations (floor not round; extra solver starts) and one spec conflict (test 10 vs `errorBlocks`), see follow-ups (both decided by the owner, row 3a) | 0837945; tracker 99fc694, bd996d6 |
| 3a | Owner decisions on the slice 3 follow-ups: keep the extra solver starts; weighted-fit `errorBlocks` | 3A | [x] extra starts kept and labelled a deliberate deviation; `errorBlocks = sqrt(trace((HᵀWH)⁻¹))`, HDOP unchanged; test 10 6.93x, triangle 1.40x pinned; equal-σ equivalence pinned (369 tests, 0 skipped) | f61a739; b47bcbf; tracker 27a2328 |
| 4 | SignalDevice + ticker refactor + meter port (regression gate) | 3A | [~] `device/` (`SignalDevice`, `DeviceContext` per §3A.2, `DeviceMemory`, `ReplayGuard`); ticker scans carried devices, dispatches every sample (replays too), keeps "evaluated ⇒ sent"; meter ported (payload byte-identical to bd996d6, checked against the old code); stale armed candidate dropped after a gap of more than one interval. Regression gate passed headless (299 tests, 1 skipped; `runGameTestServer` 2/2); in-game checks below | a51e13a; 85bd051; 6558460; tracker 6e1b982 |
| 5 | Locator item, payload, HUD, rings, waypoints, emergency record | 3A | [~] `rancraft:network_locator` ("Network Locator", a `SignalDevice`, requirement NONE); fix from the full cell list every dispatch, replays reuse it; `LocatorFixPayload` v1 only while held; HUD top-left stacked under the meter's detailed readout; rings, FIX marker + error circle, AMBIGUOUS markers; 8 waypoints of the estimate; `copyOnDeath` emergency record; `PROTOCOL_VERSION` 5. Headless green (367 tests, 0 skipped; `runGameTestServer` 4/4, incl. death/clone of the record and the live-level cost: 22 µs per 8-cell solve); in-game checks below. **3A ships here** | bf5eb47; e0fcfcd; 610a79c; 6c5d391; tracker 334edff |
| 5a | Phase 3A review round 1: fixes (11 reported; 9 confirmed and fixed, 7 distinct; 2 rejected) | 3A | [~] Locator counts sites, not cells (`locatorSiteMergeBlocks` 3.0; wrong-mirror FIX 921/2000 → 0, triangle ± 6.45 vs rms 10.21 → 10.42 vs 10.44); drive-test "stationary" measured from the still period's start; solver prior only while fresh; client drops the reading when no Locator is held, learns the stale limit from the payload cadence, uses the server's scale for a waypoint's ± or none; a dead player's Locator verified inert at runtime. Headless green (382 tests, 0 skipped; `runGameTestServer` 5/5); in-game checks below; spec conflict in follow-ups | 5221c46; 62fef10; 2f1306f; a755f7f; docs a998f3f; tracker c54e86f |
| 5b | Phase 3A docs and tracker: gate findings closed or logged, summary, in-game checklist | 3A | [x] the gate reviews' open findings checked against the code: two abstractions labelled at their code sites (stale candidate, device measures only while carried) plus two more NOTES.md claimed were there (slant σ, near-collinear towers); worst-case ground lookups per fix corrected 105 → 113 (7 × 16 + 1) in the game test and the docs; the near-collinear wrong-side FIX measured (32 % at 10 blocks off the line) and logged open; the review round's 2 rejected findings recorded; every follow-up marked done or open; NOTES.md "Phase 3A summary"; VISION_STEP3.md Part 3a; README; "How to test Part 3A in game" at the end of this file, its numbers checked against the solver with a scratch program. Headless green (382 tests, 0 skipped; `runGameTestServer` 5/5 after part 1) | 0482a74; docs 2cc912f; tracker 994c9fd |
| 6 | ColumnScan + mast columns + lens column/on-air | 3B | [~] `util/ColumnScan` (pure, 12 tests); a contiguous mast column is one cell owned by its base (`cellId = base.asLong()`), radiating from `top.above()`, structure never registers, a sector on the highest mast silences it, any powered mast powers it, `maxMastHeight` 64 caps the signal part; re-scan on `updateShape`/`neighborChanged` refreshes only the mast and the base; fresh PCI plan on promotion; `OnAir` in the update tag (not saved), lens draws one lobe per cell at the top and greys off-air cells; one census log line; `PROTOCOL_VERSION` 6. Headless green (398 tests, 0 skipped; `runGameTestServer` 13/13, 8 new: 624 ns per nine-mast scan); in-game checks below; three recorded deviations in follow-ups | 703c3a1; 5299fb4; tracker 1b8e253 |
| 7 | BinTraversal + region epochs + cache rework | 3B | [~] `rf/BinTraversal` (pure 2D DDA over bins, both side bins through a corner; the correctness argument in its javadoc); `RfEngine.Evaluation.marched` (appended) and `dependencyBins`; `world/RegionEpochs`: per-bin (128) epochs bumped by break/place (multi-block too), explosions, pistons (at once and after the blocks settle) and tree growth, plus the dimension-wide sum (`CoverageSurveyor` untouched); `canReplay`'s epoch condition is now "every dependency bin unchanged", the rest unchanged; no version bump. Headless green (429 tests, 0 skipped; `runGameTestServer` 17/17, 4 new: a block 500 blocks away keeps the cache, one on the link path does not; +3-7 µs per fresh evaluation, 0.4 µs per replay check); in-game checks below; gaps recorded in follow-ups | 97489e6; e0383ee; 9c6d39f; tracker 8f0325a |
| 8 | Fixed receiver registry + ticker | 3B | [x] `device/FixedDevice` (`requirement()`, `onSample(ServerLevel, BlockPos, DeviceContext)`); `block/FixedDeviceBlockEntity` (paths 1-2); `world/FixedReceiverRegistry` per dimension, keyed by `BlockPos.asLong()`, `ReceiverStateStore<Long>`, chunk load/unload paths (unload by position, not type), unregister by identity; `world/FixedReceiverTicker`: round robin under `fixedReceiverTickBudgetMs` (0.5, new), batches of 4 with the clock read between, rotating first dimension, at most once per interval, block-centre receiver, replay while the site version and every dependency bin are unchanged, its own stale-candidate threshold (interval + lag). Headless green (449 tests, 0 skipped; `runGameTestServer` 21/21, 4 new: 200 receivers 29-42 µs/tick in steady state). No device block of its own, so its in-game check is the Radio Link's (slice 9); the two done-when items it fed were closed with real Radio Links in slice 9 | 02d1790; 62b54c7 (slice 7 game test fix); 7866ca9; tracker 2bc36cc |
| 9 | BlerModel + Radio Link | 3B | [~] `rf/BlerModel` (the §3B.4 sigmoid, `P(deliver)`, the table within 0.001, monotonic) and `rf/SplitMix64` (seed `pos.asLong() ^ gameTime`); `blerSinr50Db` 0 / `blerSlopeDb` 2 (COMMON, appended to `RfConfig`); blocks `radio_link_transmitter` / `radio_link_receiver` (FixedDevices, POOR tier 1, address 0-15 by use / sneak + use with the action bar, `LIT` while served, the receiver outputs weak power 15, a per-transmitter memory: a lost message is a stale state, a broken or re-addressed transmitter is forgotten at once, memory from a save is verified on the receiver's turns); `device/RadioLinkNetwork` per dimension (both ends served, each end's BLER, deterministic draws); tooltip "about once a second, by design"; loot tables, pickaxe tag, creative tab, lang, placeholder models; `FixedDeviceBlockEntity.clearRemoved` registers (closes the slice 8 `onPlace` follow-up). Headless green (482 tests, 0 skipped; `runGameTestServer` 27/27, 4 new + 2 generated harvest tests: POOR 49-56 % delivered vs 52 % modelled, FAIR 1,400 of 1,400; a real chunk reload resumes; 200 radio links (400 blocks) 20-53 µs/tick, medians 27-44). No version bump. **3B ships here** (with the review fixes, row 9a); in-game checks below | 10c1de7; tracker f3948b4 |
| 9a | Phase 3B review round 1: fixes (4 reported, 4 confirmed and fixed, 0 rejected) | 3B | [~] region epochs move when a chunk reaches or leaves FULL (`ChunkEvent.Load` + `ChunkTicketLevelUpdatedEvent` across 33; not in `total()`); a Radio Link receiver outside FULL updates its memory but writes no block (no forced chunk promotion), catching up on its next turn; a migrated Phase 1 mast plans in `onLoad` again (only a promotion plans in `refreshRegistration`); `RadiatingY` in the antennas' update tag, the lens draws at the server's height (closes the slice 6 `maxMastHeight` follow-up), `PROTOCOL_VERSION` 7. Headless green (485 tests, 0 skipped; `runGameTestServer` 30/30 in five runs, 3 new, each failing with its fix disabled); game-test helpers load the chunks a forced chunk makes FULL at once; in-game checks below | 8b42665; 48e4da8; tracker 980e858 |
| 9b | Phase 3B docs and tracker: code-site labels, done-when re-checked, follow-ups, summary, in-game checklist | 3B | [x] five abstractions NOTES.md called labelled at their code sites were not, or only in part, and now are (`SignalMastBlock`: a column is one site, the mounting-pole rule; the `maxMastHeight` comment, which also still described the pre-review lens; `RegionEpochs`: bins are a cache granularity; `SignalTicker.Cached`: a replay is the evaluation at the cached point; `RadioLinkBlock`: `LIT` is public state), and `LensRenderer` now says the lobe's height is the server's; the 3B done-when re-checked against the code and tests (4 `[x]`, 2 `[~]`); every 3B follow-up marked; NOTES.md "Phase 3B summary"; README; MILESTONES; "How to test Part 3B in game" at the end of this file. Headless green (485 tests, 0 skipped; `runGameTestServer` 30/30 in each of two runs after part 1; 200 radio links medians 58.9 and 48.9 µs/tick there, above every earlier run's 26-48 and still under 0.1 ms) | 6d99b43; docs b8c9299; tracker 2868578 |
| 10 | Radio tiers + v3 migration | 3C | [~] `rf/RadioTier` (pure; one rule for the server check, the migration and the screen); `AntennaBlockEntity.radioTier` (mast 1, sector 2), saved: `DATA_VERSION` 2 → 3, v2 → v3 `max(blockDefault, tierOf(currentBand))` grandfathers a band_3500 sector, and the v1 "PCI 0 is unassigned" rule now applies to v1 saves only (it would have re-planned every Phase 2 PCI 0); item `wideband_radio_unit` (use on a sector: tier 3, consumed; breaking drops it, a command does not); `applyOn` rejects a band above the radio tier; `OpenAntennaConfigPayload` appends `radioTier` and the bands' tiers, `PROTOCOL_VERSION` 8; screen greys locked bands ("needs Wideband Radio Unit") and disables Apply on one; RF Lens not tier-gated (VISION.md Q1). Headless green (499 tests, 0 skipped; `runGameTestServer` 32/32, 2 new, each failing with its fix removed); in-game checks below; one spec deviation (payload also carries band tiers) and two owner decisions in follow-ups | 71288f0; 4b3ce10; tracker 4a242af |
| 11 | MicrowaveLink + BackhaulGraph + tests | 3C | [x] `rf/MicrowaveLink` (pure: the §3C.2 budget, FSPL with n = 2, RSL, UP / DEGRADED / DOWN at −50 / −70, the 60 % Fresnel check over four offset paths with a flat 6 dB (simplified knife-edge), approximate rain fade (thunder the higher figure, snow nothing); plus `withWeather`, `fresnelOffsetMidpoints` and `dependencyBins` for slice 12), its figures from `rf/backhaul/microwave.json` through `RfDataLoader` and never a cellular band; `rf/BackhaulGraph` (pure: implicit fiber by horizontal radius, dish-to-cell site edges through which a site relays, hops with their state; two BFS passes give FULL / LIMITED / NONE); config `requireBackhaul` false, `fiberRadiusBlocks` 24, `siteRadiusBlocks` 8, `backhaulRecomputeTicks` 100, `enableRainFade` true (the radii and rain fade appended to `RfConfig`). Headless green (539 tests, 0 skipped; `runGameTestServer` 32/32, the microwave figures loaded at runtime beside 4 bands); no version bump; nothing in game reads it until slice 12; two interpretations, an owner decision and notes for slice 12 in follow-ups | 0d45aca; docs 53ce35f; tracker f8c44fa |
| 12 | Core site, dish, link tool, backhaul state, lens lines, `/rancraft backhaul status` | 3C | [~] blocks `core_site` and `backhaul_dish` (drop themselves, pickaxe tag, placeholder art) and item `link_tool` (select, pair, sneak + use unpairs; both dish entities store the partner; alignment automatic, labelled); `world/BackhaulNetwork` (a per-dimension `SavedData` of cores, dishes, pairings and eligible cells, so unloaded relays count; marches each hop with the live `WorldProbe` in one fixed order with a cap it cannot run out of, weather from `Biome.getPrecipitationAt` at the midpoint and the level's rain and thunder; solves `BackhaulGraph`; recomputes on the site registry version, a pairing, a region epoch on a hop's bins or the weather, at most once per `backhaulRecomputeTicks`). With `requireBackhaul` on, NONE → not transmitting (unregistered, `OnAir` false, through `refreshRegistration`), LIMITED → devices capped at FAIR in `DeviceContext` and their verdicts with the `SignalSample` untouched, meter "BH: LIMITED (capped FAIR)" (`SignalSamplePayload` v4); off (default) changes nothing *(row 16a: the LIMITED cap now applies with the flag off too; only NONE's effects need it)*. `BackhaulLinksPayload` v1 (cap 64) to lens wearers, hops drawn with the lobes layer; `/rancraft backhaul status [radius]`; `PROTOCOL_VERSION` 9. Headless green (559 tests, 0 skipped; `runGameTestServer` 36/36: the chain and weather tests, both new blocks in the harvest tests); a hop 0.14 µs per block live, quiet tick about 1 µs; ten decisions in NOTES.md; in-game checks below | ea9c98d; docs 8f2a31a; tracker b5def28 |
| 13 | Storage Terminal | 3C | [~] item `storage_terminal` (a `SignalDevice`, GOOD tier 2): sneak + use binds a chest or barrel within `fiberRadiusBlocks` of a Core Site (the graph's fiber rule, any core of the dimension), saved as a `GlobalPos` data component; use opens a `RemoteContainerMenu` (a vanilla `ChestMenu` on the 9x3 / 9x6 menu types: nothing new on the wire) on an OK verdict, a FULL chunk and 27 or 54 slots; its `stillValid` reads the player's last verdict (`TerminalLink`, at most two intervals old), never a fresh evaluation, and a failed rule closes it with "connection lost (reason)"; same dimension only; never loads a chunk ("storage unreachable"); the LIMITED cap ends a session ("backhaul limited", told apart from a weak signal); the lid is never lifted (opener counter safe). Headless green (567 tests, 0 skipped; `runGameTestServer` 39/39, 3 new, including the 3C done-when: a LIMITED cell closes the session while a Radio Link on it keeps working); session check 0.44 µs/tick; no version bump; in-game checks below | 6a0ab89; docs b1735d2; tracker 744103a |
| 14 | Proximity Scanner | 3C | [~] item `proximity_scanner` (a `SignalDevice`, GOOD tier 3: band_3500's first use); `device/ProximityScanner`: held with an OK verdict, one `ScannerPayload` v1 per dispatch (the main hand's scanner, else the offhand's; none from the hotbar, none to a dead player) listing at most 16 hostile mobs (vanilla's `Enemy`) within `scannerRangeBlocks` 24 (new COMMON config, not in §5's list: follow-ups), straight line from the feet, nearest first, with entity type, distance and compass bearing; on any other verdict the payload carries the reason (no service, weak signal, backhaul limited, low tier) by the Storage Terminal's rule (`TerminalLink.reasonOf`, shared) and no list; `util/ProximityScan` (pure list); HUD list top-right under the meter's compact readout (`HudStack`), no world render, "needs tier 3, you're on band_1800 (tier 2)"; the scan labelled not RF physics at the code sites; `PROTOCOL_VERSION` 10. Headless green (594 tests, 0 skipped; `runGameTestServer` 40/40, 1 new: band_3500 lists the right mobs, 16 of 19, band_1800 refuses with "needs tier 3"); 23.7 µs per scan with 20 mobs; in-game checks below | 465d40e; docs 9e3fab5; tracker a1e9611 |
| 15 | Power + generator | 3C | [~] `rf/PowerModel` (pure: §3C.5's formula and figures, the table and 10× per 10 dB pinned); `util/EnergyBuffer` (pure: whole FE with the fraction carried, out of energy → latch off, back strictly above `powerRestartFraction` 10 %); antennas expose `Capabilities.EnergyStorage.BLOCK` (`ModCapabilities`, receive-only, nothing with `requirePower` off), any mast of a column feeds its base (a pole's masts feed the sector on top; a demoted base hands its energy down), 10,000 FE; `isTransmitting()` = eligible ∧ backhaul ∧ power, `refreshRegistration` still the one `OnAir` writer; `world/SitePower` draws each cell on the air once a tick where block entities tick, refreshes on a latch change or a flag flip; block `site_generator` (furnace fuel by NeoForge's burn-time lookup, 40 FE/t only while taken, pushes to its six sides, one slot for hoppers: fuel in, a bucket out below; use with fuel / sneak + use / status; loot table, pickaxe tag); seam `util/ServedReceivers` per cell over `servedWindowMinutes` (fixed receivers throttled to a report per 20 s); `/rancraft power status [radius]`; config `requirePower` (false) and nine figures; `AntennaBlockEntity.DATA_VERSION` 4. Headless green (619 tests, 0 skipped; `runGameTestServer` 48/48, 7 new + 1 generated harvest test: 30 dBm 2,120 vs 20 dBm 320 burn ticks, model exact, 6.625×; the draw 0.06-0.2 µs per cell). No wire change. In-game checks below; the Radio Link cost gate's sensitivity to machine load (measured against slice 14's code: this slice adds nothing to it) and two owner decisions in follow-ups | 797d641; docs bce3a8a; tracker e50a0fb |
| 16 | Recipes + loot tables + survival playthrough | 3C | [~] fourteen shaped recipes in `data/rancraft/recipe/` (1.21.1 format, `c:` tags for raw materials), one per item, from §3C.6's table and tiers: RF Lens early and cheap (2 copper, 1 amethyst, 2 glass), Signal Mast 4 per craft, the Radio Link Receiver taking a comparator **or a repeater** (a comparator needs Nether quartz, which is not early: spec conflict in follow-ups); a recipe-book unlock advancement per recipe (`data/rancraft/advancement/recipes/`, not in the spec: follow-ups); loot tables and the pickaxe tag already complete for all 7 blocks, every item already in the creative tab; `RecipeGameTests` (one per item, generated: a recipe loads, its grid from real stacks is matched by it alone with every ingredient alternative tried, and crafts the item, a RANCraft ingredient is craftable from non-RANCraft items, an advancement unlocks it; plus the creative tab), each check seen failing on broken data. Headless green (619 tests, 0 skipped; `runGameTestServer` 63/63, 15 new; recipes 1,291 → 1,305, advancements 1,400 → 1,414, no error in the log); 0 collisions with the 887 vanilla crafting recipes; no version bump. **3C ships here** (§7); the survival playthrough is the in-game check below | 36cd29e; docs 2350ce9; tracker ad91e3a |
| 16a | Phase 3C review round 1: fixes (2 reported, 2 confirmed and fixed, 0 rejected) | 3C | [~] the LIMITED cap (FAIR) applies whatever `requireBackhaul` says, NONE caps only with it on (`BackhaulGraph.serviceCap`; slice 12 decision 1's reason was wrong: with no core every cell is NONE, so a world without backhaul is unchanged); the status command always says "devices capped at FAIR"; the Storage Terminal's 3C done-when game test now runs with the flag off (the default). Backhaul re-marches spread over ticks: new pure `util/BudgetedQueue` (clock read before each item, at least one per drain), `BackhaulNetwork` recompute split into begin / advance (marches under `backhaulMarchBudgetMs`, new COMMON 0.25 ms shared by every dimension) / finish (weather, solve, effects, in a tick of its own, all states published at once); the status command says "measuring" during the first one. Headless green (625 tests, 0 skipped; `runGameTestServer` 64/64 in three runs, 1 new: 64 dirty 1000-block hops, worst tick 0.31-0.76 ms against 3.3-4.6 ms in one tick; both fixes fail their tests when disabled); no wire or save change; one owner decision and two notes in follow-ups; in-game checks below | 3e5bdd1; docs 23bb7c8; tracker e417e0b |
| 16b | Phase 3 final docs: code-site labels, 3C done-when re-checked, follow-ups, Part 3C summary, in-game checklist, README, MILESTONES | 3C | [~] **partly done; the rest deferred on purpose (owner: skip what can wait).** Done: four sites that NOTES.md called labelled described their abstraction without marking it, and now mark it (`SitePower`: no standby load, power stands still where block entities do not tick, the seam counts what the server evaluates; `ServedReceivers`: the same count; `SignalMastBlockEntity.bufferOwner`: a pole carrying power up is a game rule; `TerminalLink`: the session lives on the last verdict); `MicrowaveLink` now says its rain figures are 18 GHz ones that do not follow `frequency_mhz` (how §6's "rain fade rising with frequency" is shown); the `BackhaulNetwork` javadoc's tick counts now cover every recorded run; the slice rows' tracker hashes filled in; the 3C done-when re-checked against the code and the tests (2 `[x]`, 6 `[~]`); two slice check steps made current with the review (slice 12 step 5, slice 13 step 4); every 3C follow-up marked. **Deferred, not written yet** (independent docs-only work, see Follow-ups): NOTES.md "Phase 3C summary"; README's 3C section; a consolidated "How to test Part 3C in game" checklist (until then, use each 3C slice's own check steps in its section above). MILESTONES.md was updated by hand instead. Headless green (625 tests, 0 skipped; `runGameTestServer` 64/64 after part 1: fuel 320 vs 2,120 burn ticks, ratio 6.625 as in every run; worst backhaul march tick 0.68 ms; 200 radio links median 32.5 µs/tick) | 48393aa; docs "Phase 3: final docs"; tracker: the hash-recording commit after it |
| 16c | Deferred docs (owner: skip what can wait, then finish the pendings): README 3C section, NOTES.md "Phase 3C summary", "How to test Part 3C in game" | 3C | [x] README "Turn on backhaul and power" (where `rancraft-common.toml` is, the two flags, what each changes) and the test counts; NOTES.md "Phase 3C summary" (commits, test counts, one paragraph per feature, config and versions, measured numbers, review outcome, owner decisions); the 3C in-game checklist at the end of this file, checked against the code's thresholds. Found while writing it: row 16a's in-game step 1 cannot work as written (one stone leaves a 30-block hop UP: a clear hop reads about −3 dBm there and one stone costs 36 dB; at 1000 blocks it reads −69.55), annotated there; the checklist uses a 1000-block hop, clear weather, and a second Core Site so the chest stays loaded. Docs only, no build needed | 403fcc7; docs "Phase 3: deferred docs" |
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

## Row 3a (owner decisions on the slice 3 follow-ups) checks

Headless (verified by `./gradlew build`, 369 tests, 0 skipped; details in NOTES.md, "Owner
decisions on the slice 3 follow-ups"):

- [x] Extra Gauss-Newton starts kept; the class javadoc, the `leastSquares` comment and NOTES.md
      call them a deliberate deviation from §3A.5 and say why (10.5-16.7 % wrong fixes with a small
      ± outside the footprint from the centroid alone). `notTrappedOutsideTheFootprint` still pins it.
- [x] `errorBlocks = sqrt(trace((HᵀWH)⁻¹))`, W = diag(1/σ²), same H as HDOP; HDOP stays unweighted
      (`errorIsTheWeightedCovarianceAndHdopStaysUnweighted`: 2,000 seeded scenes, both checked
      against a direct 2x2 inversion).
- [x] Equal σ gives exactly HDOP × σ (`equalSigmasGiveHdopTimesSigma`, 2,000 seeded single-band
      scenes); test 7 unchanged.
- [x] Test 10 at the edge of a network: 35.49 → 5.12 (6.93x, ≥ 3x); good triangle pinned at 1.40x
      (below 3x, accepted by the owner).
- [x] The tests bite: the spec's `HDOP × rms(σ)` put back fails test 10, the triangle pin and the
      covariance test; a `1/σ` weight fails those plus the equivalence and test 7.
- [x] No wire or save change (`PROTOCOL_VERSION` stays "5"); the only game-side edit is the
      `LocatorHudText` javadoc.

In game: see the slice 5 band_3500 check below (numbers updated).

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
- [x] Per-player reading in a `DeviceMemory` (cleared by `forget()`) (`DeviceMemoryTest`).
- [x] *(Phase 3A review round 1: was marked here without a test.)* A dead player's Locator does
      nothing: **at runtime**, `LocatorGameTests.dead_player_locator_is_inert` (a NeoForge
      `FakePlayer` with 0 health keeps no reading and gets no emergency stamp from a FIX sample;
      alive, the same call does both; fails with the `isAlive()` guard removed).
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
      fix ~~105 lookups = 8.2 µs~~ **113 lookups (7 × 16 + 1) = 8.8 µs** *(corrected in row 5b: the
      slice 5 gate found 7 × 15 undercounts; the unit test already asserted 113)*, one 8-cell solve
      22.2 µs; no chunk is ever loaded (`getChunkNow`). Later runs: 80.3 ns / 29.0 µs (row 5a),
      36.2 ns / 29.4 µs (row 5b); the per-lookup time varies about 2x between runs.
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
      of the network, about 7x in the test 10 geometry; about 1.4x only inside a good triangle,
      accepted by the owner; see row 3a).
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

## Phase 3A review round 1 (row 5a) checks

Headless (verified by `./gradlew build`, 382 tests, 0 skipped, and `./gradlew runGameTestServer`,
5 of 5; details and numbers in NOTES.md, "Phase 3A review, round 1"):

- [x] Sites, not cells: `LocatorSolver.siteRepresentatives` groups antennas within
      `locatorSiteMergeBlocks` (default 3.0; new in `LocatorParams`, `RfConfig`, `RanCraftConfig`)
      by union-find, one range per site (smallest sigma, then range, then first given), `maxCells`
      counts sites; `solve` and `cellsUsed` share it. One two-sector site → RANGE ONLY; two
      three-sector sites → AMBIGUOUS, identical to one cell per site; a triangle of three-sector
      sites → FIX with `cellsUsed` 3 and the one-cell-per-site ±; `siteMergeBlocks` 0 → the old
      per-cell answers (`LocatorSolverTest`, +5). Scratch before/after: wrong-mirror FIX 921 → 0 of
      2000, triangle ± 6.45 vs rms 10.21 → 10.42 vs 10.44.
- [x] Drive-test log: "stationary" is measured from the still period's first sample; 0.4 blocks per
      sample over 100 blocks gives 126 entries (was 1), standing still stays 1 entry
      (`DriveTestLogTest`, +2).
- [x] The solver's `previous` only while fresh (`freshForTicks`): 4020 and 4040 mark the candidate
      near the truth, 4041 gives `NO_PREFERENCE` (`LocatorTrackerTest.stalePreviousPicksNothing`).
- [x] Client: the Locator reading is dropped every tick no Locator is held (`ClientEvents.onClientTick`
      → `ClientLocatorState.putAway`, keeps the learned cadence); the stale limit is 2.5 payload gaps
      in [5 s, 30 s] (a 10 s cadence gives 25 s) (`ClientLocatorStateTest`, 4, new).
- [x] HUD: with no current reading a waypoint's saved ± uses the last payload's scale ("saved ±18 m"
      at 2 m/block), and is left out before any payload (`LocatorHudTextTest`).
- [x] A dead player's Locator does nothing, at runtime (`LocatorGameTests.dead_player_locator_is_inert`;
      fails with the guard removed).

Needs a human in game:

- [~] A three-sector site (sectors on three sides of one mast column), stand 60-150 blocks away
      hearing two or three of its sectors and no other cell: RANGE ONLY, one ring, "Cells 1" (before:
      AMBIGUOUS with two markers on a line through the site).
- [~] Two such sites about 200 blocks apart, stand between and to one side: AMBIGUOUS, one yellow
      marker on your side (before: a green FIX on the far side with a small ±).
- [~] Three such sites round you: FIX with a ± comparable to three single masts (about ± 10 m on
      band_900 at 1 m per block), not smaller.
- [~] Set `evaluationIntervalTicks` to 2 and `enableSampleCaching` to false, lens on TRAIL, walk
      slowly (or sneak) in a straight line: markers keep appearing along the path about every
      0.5-1 block, and the CSV has one row per such step (before: one marker sliding along with you).
      Restore both settings afterwards.
- [~] With a FIX, switch to another hotbar item, walk 20 blocks, switch back: "No reading from the
      network yet" and no rings until the next update (at most one interval), never the old FIX at
      the old place. Same with F1 (hidden HUD): no old rings drawn after switching back.
- [~] Set `evaluationIntervalTicks` to 200 and hold the Locator with a FIX: the HUD and rings stay on
      between updates (no NO SIGNAL blink every 5 s) once two updates have arrived.
- [~] Set `metersPerBlock` to 2.0, save a waypoint under a FIX: the chat's "±N m" and the HUD's
      "saved ±N m" agree, also in the first second after taking the Locator back into the hand
      (then the ± is either the same number or left out, never half of it).
- [~] Put the Locator in a chest, travel far (or `/tp` with it outside the hotbar), take it out
      where only two cells are heard: AMBIGUOUS with "No last estimate to choose between them", both
      markers alike.

## 3A done-when

Reviewed in row 5b against the code and the tests. None is `[x]`: each has a part only a player can
see. The reason is one line; the evidence is in the sections above and NOTES.md "Phase 3A summary";
the step numbers point into "How to test Part 3A in game" at the end of this file.

- [~] Meter and lens behave exactly as before the ticker refactor. *Headless half passed (slice 4
      regression gate: 20,000 payloads byte-identical to bd996d6); the HUD, rays and counter need a look
      (how-to steps 1 and 9).*
- [~] Carrying the Locator costs no extra evaluation (EvaluationStats). *Proven structurally headless
      (one evaluation per due player, pinned); EvaluationStats cannot count (open follow-up), so the
      runtime half is the `/tick query` check (step 2).*
- [~] Triangle of three masts → FIX with a sensible ±; three in a line → POOR GEOMETRY. *Tests 6 and 7
      and the site tests pass headless; seeing it needs masts in a world (and "in a line" means exactly
      in line, see the near-collinear follow-up; steps 2 and 3).*
- [~] Adding a band_3500 site visibly shrinks ±. *Pinned headless (1.40× in a good triangle, 6.93× at
      the network's edge, owner-approved weighted ±); "visibly" needs the HUD in game (step 4).*
- [~] Walking behind a hill visibly increases fix error. *Test 11 pins the NLOS push headless (the ±
      stays, by design); the visible marker offset needs real terrain (step 5).*
- [~] Rings render at measured ranges and pass through (or near) the player. *The radius and slice
      rule are pinned headless (`LocatorStyleTest`); the drawing itself needs the client (steps 2 and 6).*
- [~] Emergency record survives death and shows the estimate, not the true position. *Verified at
      runtime through the real death and clone events (`LocatorGameTests`); the HUD after a real
      respawn needs the client (step 8).*

## Slice 6 (mast columns) checks

Headless (verified by `./gradlew build`, 398 tests, 0 skipped, and `./gradlew runGameTestServer`,
13 of 13; details and numbers in NOTES.md, slice 6):

- [x] `ColumnScan.bounds(y, isMast, maxHeight)` is pure (in `util`, `PackagePurityTest` green) and
      pinned with a fake predicate: single block, 9-stack (the same column from every mast), gap,
      antenna on top, height cap, plus negative heights, the power gate, a bounded walk and the read
      count (`ColumnScanTest`, 12).
- [x] **At runtime** (`MastColumnGameTests`, live server level): nine stacked masts register one
      cell, id = the base's position, radiating from above the ninth mast; the eight others are not
      transmitting and their `OnAir` is false.
- [x] **At runtime**: a mast added on top keeps the cell's id and PCI and lifts the radiating point
      by one; breaking the base promotes the next mast (new id, same radiating point, freshly
      planned); a gap makes two cells and filling it one again; a mast placed under the base takes
      over and the old base goes quiet.
- [x] **At runtime**: a sector antenna on the highest mast silences the column (only the sector's
      cell is registered) and removing it puts the column back; with `maxMastHeight` 3, six masts
      radiate from above the third and a sector on the sixth still silences them.
- [x] **At runtime**: with `requireRedstone` on, a redstone block beside the top mast only puts the
      column on the air (base itself unpowered), and removing it takes it off (`OnAir` follows).
- [x] `OnAir` is in the update tag (true for a transmitting base, false for structure) and never in
      the saved data (`nine_masts_are_one_cell`); no `DATA_VERSION` change. `PROTOCOL_VERSION` 5 → 6
      (see follow-ups).
- [x] Off-air lobes are grey at half opacity whatever the band (`LensStyleTest`).
- [x] The census line, logged once per settled batch of loads: "9 stacked masts now form 1 column; 8
      masts stopped transmitting." (`saved_stack_is_logged_once`, which feeds each mast its saved data
      before `onLoad`, as a chunk load does; the sentence rules in `MastColumnCensusTest`).
- [x] Cost measured on live ground: 624 ns per nine-mast scan from the base (11 reads), 812 ns from
      the sixth mast (16), 124 ns per two-read base check; never per evaluation.

Needs a human in game (`runClient`, creative tab "RANCraft", lens on Antennas or ALL, meter detailed
with a right-click):

- [~] Build a column of nine Signal Masts in open ground, no other band_900 cell nearby. Lens: **one**
      lobe, just above the top mast (before: nine overlapping lobes). Meter beside it: "Serving: PCI n
      @ x, top+1, z" and "(0 co-channel)" (before: "(8 co-channel)" or so).
- [~] Add a tenth mast on top: the lobe moves up one block, the meter's "@ ... y" goes up by one and
      its PCI stays the same; the server log shows no new "PCI plan" line.
- [~] Put a Sector Antenna on the top mast: the column's lobe disappears and the sector's lobe
      appears on top of it; the meter now serves from the sector. Break the sector: the column's lobe
      comes back at the top.
- [~] Break the lowest mast: the lobe stays where it was (same top); the server log shows a new "PCI
      plan" for the mast above it (a fresh plan: known behaviour).
- [~] Set `requireRedstone = true` (config, game closed), rebuild a column: its lobe is **grey**
      (off the air) and the meter says NO SERVICE near it. Place a Block of Redstone touching any one
      mast of the column (a middle one will do; a lever cannot hang on the thin mast): the lobe turns
      band-coloured and the meter reads the column; break it: grey again. Restore
      `requireRedstone = false`.
- [~] Save and quit with a stacked column in the world, reopen: the server log has "RANCraft mast
      columns (Phase 3, loaded this run): N stacked masts now form M columns; N-M masts stopped
      transmitting." about 5 s after the world loads. (A Phase 2 world with stacks shows the same
      line the first time; the column's lowest mast keeps its old PCI.)
- [~] A 64-mast column (`/fill` a 1x64x1 pillar of `rancraft:signal_mast`) with the lens on: one
      lobe above the 64th mast, no frame-rate drop; add masts above it: the lobe stays at the 64th
      (the cap); a sector on the very top still silences it.

## Slice 7 (region epochs) checks

Headless (verified by `./gradlew build`, 429 tests, 0 skipped, and `./gradlew runGameTestServer`,
17 of 17; details and numbers in NOTES.md, slice 7):

- [x] `BinTraversal.binsAlong(x0, z0, x1, z1, binSize)` is pure (in `rf`, `PackagePurityTest` green)
      and pinned: axis-aligned, diagonal, negative coordinates, start and end in the same bin
      (`BinTraversalTest`, 10). On 1,200 random rays it returns exactly the bins of the voxels
      `RayMarcher` reads plus the two end bins; on 2,250 rays through exact bin corners it covers
      every voxel read whichever axis the march steps first.
- [x] The dependency set is the union of bins along every marched ray
      (`RfEngine.Evaluation.dependencyBins`). **A pruned cell contributes no bins**, asserted with a
      counting probe (no voxel read in its bins); nor does a cell cut by `maxCellsEvaluated`; open air
      and a world of walls march the same rays; a marched cell that ends unheard is still a
      dependency (`RfEngineDependencyTest`, 6).
- [x] The correctness argument (marched cells chosen with no obstruction term; pruned cells cannot
      be revived by any block change) is in `BinTraversal`'s class javadoc, pointed to from
      `RfEngine`, `RegionEpochs` and `SignalTicker.Cached`.
- [x] `canReplay` is still the single cache decision: site registry version unchanged **and** every
      dependency bin's epoch unchanged **and** moved less than 0.5 blocks, plus the unchanged
      conditions (caching on, no armed candidate, links not starved), all pinned
      (`SignalTickerCacheTest`, 14).
- [x] **At runtime**: a mock player's real placement bumps the block's bin once, a bed (the
      multi-block event) and a posted break and tree growth once each, and a bin 500 blocks away never
      moves; an explosion bumps its bin; a piston bumps exactly twice, at once and after the stone
      lands (`RegionEpochGameTests`).
- [x] The dimension-wide sum (`SignalTicker.blockEpochOf` = `RegionEpochs.total()`) moves on every
      bump, so `CoverageSurveyor` works with no diff.
- [x] Cost measured: +2.6 to 6.1 µs for the dependency bins and 0.5 to 1.1 µs for the snapshot per
      fresh evaluation (6 rays, 17-19 bins; the evaluation itself 53-92 µs in open air), 0.35 to 0.45
      µs per replay check.
- [x] No wire or save change: `PROTOCOL_VERSION` stays "6", `DATA_VERSION` 2.

Needs a human in game (`runClient`, meter detailed with a right-click, lens on LINKS):

- [~] Stand still about 40 blocks from a Signal Mast in open ground with the meter held. Place a row
      of stone on the link (the lens line shows where): within a second the meter's RSRP drops and
      the line reddens at the stone. Break it: back within a second. (Unchanged behaviour; this
      checks the new key still invalidates what it must.)
- [~] Same spot: light TNT on the link path (away from the mast), or let a creeper blow a hole in
      a wall on the path: the reading changes within a second of the explosion. Before slice 7 it
      could stay until you moved (explosions bumped nothing).
- [~] Same spot: a piston pushing a stone block into the link path (lever-powered): within about a
      second the reading shows the stone, and it stays showing it (it does not stay at the clear
      reading taken while the block was moving). Retract: clear again. Before slice 7 neither
      invalidated anything.

(A replay is by design indistinguishable from a fresh evaluation on screen, so "a block far away no
longer invalidates" has no in-game check; it is verified headlessly and at runtime above.)

## Slice 8 (fixed receivers) checks

Headless (verified by `./gradlew build`, 449 tests, 0 skipped, and `./gradlew runGameTestServer`,
21 of 21; details and numbers in NOTES.md, slice 8):

- [x] `FixedDevice` has `requirement()` and `onSample(ServerLevel, BlockPos, DeviceContext)`; each device
      gets its own verdict on the same sample (**at runtime**: POOR tier 1 OK, POOR tier 3 LOW_TIER).
- [x] `FixedReceiverRegistry` is per dimension, keyed by `BlockPos.asLong()`, with a
      `ReceiverStateStore<Long>`; registering is idempotent, unregistering is by identity and forgets
      the handover state (`FixedReceiverRegistryTest`, 7).
- [x] **At runtime** (`FixedReceiverGameTests`, real ticker on the real server tick): evaluated at the
      block centre, then the same sample replayed exactly one interval later; a block change 500
      blocks away keeps the replay; stone on the link path makes the next one fresh and it reads the
      stone's loss; every dispatch exactly one interval apart; unregistered, nothing more.
- [x] Round robin under `fixedReceiverTickBudgetMs` (0.5, `RanCraftConfig`): batches with the clock read
      between, the first batch always runs, over budget the rest wait in order and carry their lag,
      the first dimension rotates, at most once per interval, a staggered first turn
      (`FixedReceiverTickerTest`, 13, fake clock).
- [x] Its own stale-candidate threshold, `interval + lag` (the slice 4 follow-up): a budget overrun
      keeps an armed candidate where the player threshold would drop it; a skipped turn drops it;
      every gap on the real scheduler is exactly `interval + lag`.
- [x] Lifecycle on all four paths, **at runtime**: `onLoad` registers and `setRemoved` unregisters
      (a replaced entity's late removal keeps its successor); the chunk load event registers a device
      entity; a real chunk unload drops it by position and the game's `setRemoved` follows; an unloaded
      device hears nothing.
- [x] Cost measured on the live server: 200 receivers in steady state 29-42 µs/tick (4 runs; replays
      only, about 2 µs each with the dispatch), asserted under 100 µs; an interval with nothing cached
      (every receiver evaluated once, as after any antenna change in the dimension) 200-390 µs/tick.
- [x] No wire or save change (`PROTOCOL_VERSION` "6", `DATA_VERSION` 2); no block added.

Needs a human in game: nothing yet. Slice 8 adds no device block; the Radio Link (slice 9) brings the
first in-game checks of fixed receivers (place one, unload and reload its chunk, watch it resume).

## Slice 9 (Radio Link) checks

Headless (verified by `./gradlew build`, 482 tests, 0 skipped, and `./gradlew runGameTestServer`,
27 of 27 in each of eight runs; details and numbers in NOTES.md, slice 9):

- [x] `rf/BlerModel` matches the §3B.4 table within 0.001 and is monotonic in SINR;
      `P(deliver) = (1 - BLER(tx)) x (1 - BLER(rx))` (`BlerModelTest`, 9).
- [x] Draws come from SplitMix64 seeded with `pos.asLong() ^ gameTime` (the transmitter's position, the
      dispatch tick), identical across two runs with the same seed and different across ticks
      (`SplitMix64Test`, 5; `RadioLinkNetworkTest.deterministicDraws`, `drawOrder`). No `Math.random()`.
- [x] `blerSinr50Db` 0.0 and `blerSlopeDb` 2.0 in `RanCraftConfig` (COMMON), appended to `RfConfig`.
- [x] Both ends must be served, each end's BLER applies, and the delivered fraction matches the model
      (`RadioLinkNetworkTest`, 11).
- [x] A receiver outputs 15 while a transmitter on its address was last heard powered; a lost message
      holds the old state (`RadioLinkMemoryTest`, 8). **At runtime**: it follows within one interval
      with redstone 15 beside it; another address hears nothing; it holds 15 through a service loss
      while its transmitter, unpowered, sends into no service; a lever on stone beside the transmitter
      works; a broken transmitter turns it off at once.
- [x] Address 0-15 by use and sneak + use, wrapping (**at runtime**, through the block's use).
- [x] `LIT` while served, set by the server (**at runtime**: lit when served, dark when the cell goes).
- [x] **At runtime**, POOR drops and FAIR is solid: both ends at POOR (SINR 0.7-1.0 dB) delivered 49-56 %
      of 200 messages against 52.4 % modelled; both ends at FAIR (8.9-9.3 dB) 200 of 200 in each of 7
      runs.
- [x] **At runtime**, a real chunk unload and reload: unregistered and removed on unload, while a
      receiver elsewhere holds the unloaded transmitter's state; reloaded from disk, registered at once
      with its address and memory as saved, then served again; a receiver that missed a transmitter's
      removal while unloaded finds it gone on its first turn.
- [x] Cost: 200 radio links (200 transmitters + 200 receivers) in steady state, 20-53 µs/tick per
      100-tick window, medians 27-44 µs over 3 runs; the game test asserts the median under 100 µs.
- [x] Both blocks drop themselves and are pickaxe-mineable (`HarvestGameTests`, generated); loot tables,
      tag, creative tab, lang, models.
- [x] A device placed where block entities are not ticking registers at once (`clearRemoved`; **at
      runtime** for a `setBlock` placement).
- [x] No version change (`PROTOCOL_VERSION` "6", `DATA_VERSION` 2); the Radio Link entities save their
      own `DataVersion` 1.

Needs a human in game (creative is fine unless noted):

- [ ] Place a Signal Mast (with a redstone signal if `requireRedstone` is on), then a Radio Link
      Transmitter and a Radio Link Receiver a few blocks away. Both lamp tops light within about a
      second. Break the mast: both go dark within about a second.
- [ ] Right-click each: the action bar reads "Radio Link Transmitter: address 1" (then 2, and so on).
      Sneak + right-click with an empty hand counts down; 0 wraps to 15. Set both to the same address.
- [ ] Put a lever on the transmitter and a redstone lamp beside the receiver. Flip the lever: the lamp
      follows within about a second, both ways. A receiver on another address does not react.
- [ ] Redstone dust run up to the transmitter bends into it and powers it.
- [ ] The tooltip of either item (creative tab) says updates arrive about once a second, by design,
      and that both ends need POOR service or better.
- [ ] POOR against FAIR (done-when): with the Field Test Meter, find a spot that reads POOR with SINR
      0 to 5 dB (between two masts on the same band, for example) and put the transmitter there, the
      receiver where the meter reads FAIR or better. Flip the lever every few seconds: the lamp misses
      or lags some flips by a second or more. Move the transmitter to a FAIR spot: every flip arrives
      within about a second.
- [ ] Unload and reload (done-when): with the lever on, go far enough away that the radio links' chunks
      unload (beyond the view distance), then come back: the receiver's lamp is still on, and it still
      follows the lever.
- [ ] Break a powered transmitter: its receivers turn off at once.
- [ ] Survival, iron pickaxe: both blocks drop themselves.

## Phase 3B review (row 9a) checks

Headless (verified by `./gradlew build`, 485 tests, 0 skipped, and `./gradlew runGameTestServer`,
30 of 30 in each of five runs; details in NOTES.md, "Phase 3B review, round 1"):

- [x] **Finding 1, at runtime**: a fixed receiver whose ray crosses a chunk kept in memory but not FULL
      reads it as air and replays; the chunk forced back to FULL (the same `LevelChunk`, so no load
      event) makes the next turn fresh, reading the stone wall in it; released again, fresh through air
      (`FixedReceiverGameTests.a_chunk_on_its_ray_reaching_or_leaving_full_reevaluates_it`; fails with
      the ticket hook disabled). `bumpChunk` moves the chunk's bin and not `total()`; only crossings of
      level 33 count (`RegionEpochsTest`, +2).
- [x] **Finding 2, at runtime**: a receiver in a chunk outside FULL (still in memory, attached,
      registered, not served) hears a departure and new messages in its memory, writes no block and
      does not pull the chunk back to FULL; forced again, its next turn writes the output
      (`RadioLinkGameTests.a_receiver_outside_full_hears_but_does_not_write_its_block`; fails with the
      old writes).
- [x] **Finding 3, at runtime**: a migrated Phase 1 mast refreshed at "chunk load" before a neighbour
      holding PCI 0 registered plans in `onLoad` around it (PCI 1); the old branch planned at once and
      took 0 (`MastColumnGameTests.migrated_mast_plans_after_the_whole_load_registered`).
- [x] **Finding 4**: `RadiatingY` is in the update tag (above the ninth mast; up one after extending; the
      capped height under `maxMastHeight` 3) and never saved (game tests); the client's choice between
      the server's height and its own scan is pinned (`ColumnScanTest.reportedRadiatingHeight`).
      `PROTOCOL_VERSION` 6 → 7; `DATA_VERSION` 2.
- [x] Costs unchanged within run-to-run spread: 200 fixed receivers 18.6-27.4 µs/tick (slice 8: 29-42); 200 radio links
      medians 26.1, 29.3 and 45.3 µs/tick (slice 9: 27-44).

Needs a human in game:

- [~] (Finding 4, needs a dedicated server, so optional) Start `runServer` with `maxMastHeight = 4` in
      the server's `rancraft-common.toml`, connect a `runClient` whose own config keeps 64, build a
      column of eight masts with the lens on: the lobe sits above the **fourth** mast (where the meter's
      "Serving ... @ y" says the cell is), not above the eighth. Before the fix it sat above the eighth.
- [~] (Finding 4, single player) A column of nine masts, lens on: one lobe above the ninth mast; add a
      tenth on top: the lobe moves up one block at once (the server re-sends the height).
- [~] (Finding 1, best effort: it shows only if the wall's chunk loads after your first reading) Far
      from spawn, stand 150 blocks from a Signal Mast with a stone wall 3 thick across the link about
      120 blocks from you (lens on LINKS shows the line), meter held in detailed mode. Save and quit,
      reopen, and do not move: the reading settles on the walled RSRP within a second or two of the world
      appearing. Before the fix it could keep the clear reading, taken before the wall's chunk had
      loaded, until you moved half a block.
- [~] (Finding 2, optional: only its absence of side effects shows) Put a transmitter on a slow redstone
      clock in a chunk you `/forceload add`, its receiver (with a lamp) 30 blocks away in a chunk you do
      not force. Walk until the receiver is about 13 to 23 chunks away (view distance 10), wait a minute:
      no hitch in the server's tick (F3 / `/tick query`), and back near it the lamp shows the clock's
      current state within about a second. `/forceload remove` afterwards.

## 3B done-when

Re-checked in row 9b against the code, the tests and two fresh `runGameTestServer` runs (30 of 30
each). `[x]` means verified headlessly or at runtime on the game-test server; `[~]` has a part only a
player at the client can see, with the reason in one line. The step numbers point into "How to test
Part 3B in game" at the end of this file.

- [~] The nine-mast column reads as one cell, (0 co-channel), lobe at the top. *Headless half
      verified at runtime: one registered cell owned by the base, radiating from above the ninth mast,
      the eight others off the air, and the update tag carrying that height (`RadiatingY`, review fix
      4) for the lens (`nine_masts_are_one_cell`). The meter's "(0 co-channel)" and the lobe need the
      client (step 1).*
- [x] Adding a mast to the top of a column keeps its PCI. *Verified at runtime in a live server
      level: same cell id and PCI, radiating point up one, and the base's update tag carries the new
      height (`extending_keeps_id_and_pci`).*
- [x] A block placed 500 blocks away no longer invalidates a cached sample; one on the link path does.
      *Verified headlessly on a real engine evaluation through `canReplay`
      (`SignalTickerCacheTest.farBlockKeepsTheCacheLinkPathBlockDoesNot`: a mast 300 blocks away, a
      block 500 blocks off keeps the entry, one in the link's middle bin invalidates it), at runtime in
      a live level (`RegionEpochGameTests`: a mock player's real placements 500 blocks south and 150
      blocks along the link, the snapshot the ticker keeps, and the march reading the same obstruction
      vs 12 dB more), and on the real fixed-receiver ticker (`FixedReceiverGameTests`: a change 500
      blocks away keeps the replay, stone on the ray makes the next turn fresh and it reads the
      stone). Since review fix 1 a chunk on the ray reaching or leaving FULL also invalidates
      (verified at runtime). The per-player ticker path itself runs only in game (the fake-connection
      follow-up); step 3 covers it.*
- [x] 200 radio links on a quiet server cost < 0.1 ms/tick in steady state (measured). *Measured and
      asserted at runtime with 200 real radio links, each a transmitter and a receiver (400 blocks,
      every message reaching 12-13 receivers), replays only, the Radio Links' own work included
      (`RadioLinkGameTests.two_hundred_radio_links_in_steady_state`, median of three 100-tick windows
      asserted under 100 µs). Medians over every recorded run: 27.5, 43.5, 41.5 (slice 9), 26.1, 29.3,
      45.3 (row 9a; 47.5 in one earlier row 9a run), 58.9, 48.9 µs/tick (row 9b); single windows
      19.6-66.4 µs/tick. The same code varies by up to 2.3x between runs on the development machine,
      so the margin is 1.7x at the worst run, not the 2-4x the slice 9 runs suggested (NOTES.md,
      "Phase 3B summary"; open follow-up).*
- [~] A radio link drops updates at POOR and is solid at FAIR. *Verified at runtime with real blocks on
      the real ticker (`poor_link_drops_updates_fair_link_is_solid`): both ends at POOR (SINR about
      1 dB) delivered 49-56 % of 200 messages against 52.4 % modelled (99 and 105 in row 9b's runs);
      both ends at FAIR (about 9 dB) 200 of 200 in each of the 9 runs whose counts were recorded
      (1,800 messages; the test allows 2 lost). "Visibly" (a lamp missing lever flips) needs the user
      in game (step 5).*
- [x] Unloading a chunk stops its fixed devices; reloading resumes them. *Verified at runtime with real
      Radio Links and a real chunk unload and reload from disk
      (`unloading_stops_it_reloading_resumes_it`): unregistered, removed, no more dispatches; reloaded,
      registered at once with address and memory as saved, served again, messages arrive. Since review
      fix 2 a receiver whose chunk sits in memory outside FULL hears but writes no block, and catches
      up when FULL again (verified at runtime). The walk-away-and-back check is step 6.*

## Slice 10 (radio tiers) checks

Headless (verified by `./gradlew build`, 499 tests, 0 skipped, and `./gradlew runGameTestServer`,
32 of 32; details in NOTES.md, slice 10):

- [x] `rf/RadioTier` (pure): a radio of tier n takes bands of `capacityTier` ≤ n; an unknown band is
      refused; Signal Mast 1, Sector Antenna 2, with a Wideband Radio Unit 3 (`RadioTierTest`, 10).
- [x] 3C test: a band_3500 request on a tier-2 sector is rejected (`RadioTierTest`; **at runtime**
      through the real `UpdateCellParamsPayload.applyOn`: refused with the band unchanged, band_1800
      taken, band_3500 taken once the unit is fitted).
- [x] 3C test: the v2 → v3 migration grandfathers it (`RadioTierTest`; **at runtime** on real entities
      handed v2 data before `onLoad`: a band_3500 sector comes back tier 3 and still accepts band_3500,
      a band_1800 sector 2, a mast 1, a v3 tier kept).
- [x] `DATA_VERSION` 2 → 3 (`RadioTier` saved); a v2 PCI 0 is kept, only a v1 PCI 0 is re-planned
      (**at runtime**; the game test fails with the old rule).
- [x] **At runtime**, the Wideband Radio Unit through the real `ServerPlayerGameMode.useItemOn`: the
      sector's screen does not open instead, tier 3, one of two units consumed; a second unit refused
      and kept; a Signal Mast untouched.
- [x] **At runtime**, a survival pickaxe break (`ServerPlayerGameMode.destroyBlock`) drops the sector
      and the unit; a grandfathered sector drops one too; a `/setblock`-style replacement drops none.
- [x] `OpenAntennaConfigPayload` appends `radioTier` and each band's capacity tier, round-trips, caps
      at 64 and rejects a larger tier count; the screen's lock rule is the server's
      (`OpenAntennaConfigPayloadTest`, 4). `PROTOCOL_VERSION` 7 → 8.
- [x] The RF Lens is not tier-gated (`RfLensItem` javadoc; VISION.md open question 1 marked resolved).
- [x] Both new game tests fail with their fix removed (the `applyOn` check; the v1-only PCI rule).
- [x] No hot path touched; the cost lines of the run stay in their recorded ranges (200 radio links
      median 53.5 µs/tick, 200 fixed receivers 23.0 µs/tick).

Needs a human in game (creative unless noted):

- [ ] Place a Sector Antenna and right-click it with an empty hand. The screen says "Radio tier: 2"
      under Apply. Cycle the band: band_3500 reads "band_3500 (locked)" in grey, hovering it says
      "needs Wideband Radio Unit", Apply is greyed and the same reason shows under it. band_700,
      band_900 and band_1800 are white and Apply works.
- [ ] Right-click the sector with a Wideband Radio Unit (creative tab): the action bar says it was
      fitted, a smithing sound plays, and the screen does not open. Open the screen: "Radio tier: 3",
      band_3500 is white; apply it, and the Field Test Meter near the sector shows band_3500.
- [ ] Right-click it again with a unit: "already has a Wideband Radio Unit", the unit stays in hand.
      A unit used on a Signal Mast does nothing.
- [ ] Survival, iron pickaxe: fitting consumes one unit; breaking the sector drops the sector and the
      unit; placed again it is tier 2 until a unit is fitted.
- [ ] (Optional, needs a Phase 2 world) A world saved on `main` with a sector set to band_3500: loaded
      on this branch, its screen shows "Radio tier: 3" and band_3500 unlocked; breaking it drops a unit.
      A sector there with PCI 0 keeps PCI 0.

## Slice 11 (microwave link, backhaul graph) checks

Headless (verified by `./gradlew build`, 539 tests, 0 skipped, and `./gradlew runGameTestServer`,
32 of 32; details in NOTES.md, slice 11):

- [x] `rf/MicrowaveLink` and `rf/BackhaulGraph` are pure (`PackagePurityTest`).
- [x] 3C test: FSPL at 18 GHz / 1000 m is 117.55 dB within 0.01 (`MicrowaveLinkTest.fspl`); the clear
      1000 m hop is −33.55 dBm, 16.45 dB above UP (§3C.2's sanity check).
- [x] 3C test: one stone block on the path is DEGRADED (36 dB, −69.55 dBm); two are DOWN (72 dB). About
      six leaves (18 dB) is DEGRADED.
- [x] 3C test: the first Fresnel radius at the midpoint of 1000 m at 18 GHz is 2.04 m within 0.01. A
      block inside 60 % of it costs the 6 dB penalty in each of the four directions; one beyond costs
      nothing.
- [x] 3C test: rain adds exactly `rain_db_per_km × d_km`, thunder the higher figure, snow nothing; a
      marginal hop drops to DOWN in a thunderstorm and is DEGRADED again after.
- [x] 3C test: `BackhaulGraph` FULL via fiber, FULL via an UP chain, LIMITED via one DEGRADED hop, NONE
      when isolated, and a DEGRADED path loses to an UP path when both exist.
- [x] `microwave.json` is the §3C.2 JSON, loads through `RfDataLoader` (also at runtime: the game-test
      server's log shows its figures beside 4 bands) and never appears as a cellular band
      (`RfDataLoaderMicrowaveTest`).
- [x] Config: `requireBackhaul` false, `fiberRadiusBlocks` 24, `siteRadiusBlocks` 8,
      `backhaulRecomputeTicks` 100, `enableRainFade` true (checked in the game-test server's config
      file); the three that `rf` reads are appended to `RfConfig`, the other two stay out of it.
- [x] For slice 12: `withWeather` equals a fresh evaluation and reads no block; `dependencyBins` holds
      every voxel an evaluation reads (300 random hops).
- [x] No version moved and no hot path touched (the game tests' cost lines: 200 radio links median
      48.6 µs/tick, 200 fixed receivers 25.1 µs/tick).

Needs a human in game: nothing yet. No block uses the link or the graph until slice 12, so a world
plays exactly as before (`requireBackhaul` is read by nothing).

## Slice 12 (backhaul in game) checks

Headless (verified by `./gradlew build`, 559 tests, 0 skipped, and `./gradlew runGameTestServer`,
36 of 36; details in NOTES.md, slice 12):

- [x] `core_site` and `backhaul_dish` drop themselves when mined with an iron pickaxe in survival and are
      pickaxe-mineable (`HarvestGameTests`, 6 tests now, no edit): loot tables in `loot_table/blocks/`,
      both ids in `tags/block/mineable/pickaxe.json`.
- [x] **At runtime**, the Link Tool through the real `ItemStack.useOn` (NeoForge's item-use hook): the
      first dish is selected (dimension and position), the same dish again changes nothing, the second
      pairs and clears the selection, sneak + use unpairs both ends, re-pairing works; both entities
      store and save the partner's position (§3C.2).
- [x] **At runtime**, the server measures each hop on the live level: the network's budget equals a fresh
      `MicrowaveLink.evaluate` with `LevelWorldProbe`; a 1000-block hop reads FSPL 117.55 dB and
      −33.55 dBm clear, and −69.55 dBm (DEGRADED) with one stone on it (§3C.2's sanity check, live).
- [x] **At runtime**, the recompute triggers: a stone placed with its event on a hop is picked up at the
      next allowed recompute (at least `backhaulRecomputeTicks` after the last) and only that hop is
      marched again; breaking a dish (topology); the weather (re-budgeted, no march); the flag itself.
- [x] 3C done-when, server half, **at runtime** with `requireBackhaul` on: three sites chained by
      microwave from a core are FULL, registered and on the air; breaking the middle dish unpairs its
      partner and takes the far sites off the air (unregistered, `onAir()` false, `OnAir` false in the
      update tag, which the lens greys); site 1 is untouched; turning the flag off puts them back on the
      air, uncapped.
- [x] 3C test: LIMITED caps `DeviceContext` service at FAIR while the underlying `SignalSample` is
      untouched (`SignalTickerTest.backhaulCapIsAppliedInTheNetworkLayerOnly`; **at runtime** on a live
      evaluation of a LIMITED cell: a GOOD requirement refused, a POOR one kept, the sample's own level
      GOOD or better).
- [x] The meter's "BH: LIMITED (capped FAIR)": `SignalSamplePayload` v4 carries the server's cap
      (`SignalSamplePayloadTest.backhaulCap`; built from the live cap in the chain test). The captured v3
      bytes still hold with the version byte 04 and the cap byte appended.
- [x] 3C done-when, server half, **at runtime**: a marginal 1000-block hop (one stone, DEGRADED) drops to
      DOWN in a thunderstorm (6.0 dB) and in rain (2.5 dB) and recovers when the sky clears, each picked up
      by the server's own recompute without a march.
- [x] `requireBackhaul` off (the default) changes nothing: a NONE cell stays on the air, uncapped, and
      three recomputes never move the site registry (weather test;
      `BackhaulGraphTest.requireBackhaulOffChangesNothing`). *(Revised in the Phase 3C review, row 16a:
      with the flag off a LIMITED cell is now capped at FAIR too, as §3C.2 states; nothing goes off the
      air and a NONE cell stays uncapped, so a world without a Core Site is still unchanged.)*
- [x] `BackhaulLinksPayload` v1: round trip, cap 64 on build and on read, an unknown state rejected
      (`BackhaulLinksPayloadTest`); `linksNear` gives a site's hops at runtime. Not a `LensSettings` field.
- [x] `/rancraft backhaul status 2000` **at runtime** through the server's dispatcher: two cells off the
      air, none LIMITED, two links with RSL, margin, Fresnel state and rain loss.
- [x] `PROTOCOL_VERSION` 8 → 9 (one bump), `SignalSamplePayload.VERSION` 3 → 4, `BackhaulLinksPayload`
      v1; `AntennaBlockEntity.DATA_VERSION` 3 unchanged; `rf` stays pure (`PackagePurityTest`).
- [x] Costs measured (NOTES.md, slice 12): a hop 0.14 µs per block over loaded ground, a recompute
      marching three hops 192 µs and marching none 36 µs, the quiet per-tick check 0.08-1.25 µs; the
      existing cost lines hold (200 radio links median 37.3 µs/tick, 200 fixed receivers 25.5 µs/tick).

Needs a human in game (creative unless noted). Steps 2-5 need `requireBackhaul = true` in
`run/client/config/rancraft-common.toml` (game closed); put it back to `false` afterwards. The hop
checks need a **long** hop: a short one has about 45 dB of margin, so leaves (3 dB each) cannot degrade
it; at 1000 blocks the margin is 16.45 dB, as §3C.2's numbers assume.

- [~] **1. Blocks and tool (flag off).** Place a Core Site and right-click it: the action bar gives the
      fiber radius (24). Place two Backhaul Dishes 40 blocks apart on pillars 10 blocks high; right-click
      one with an empty hand: "not paired". Use a Link Tool on one dish (it glints, the tooltip names the
      dish), then on the other: "paired ... (40 m)". Within 5 s, right-click a dish: "UP, RSL ... dBm,
      margin +... dB, Fresnel clear, rain 0.0 dB". Sneak + use the tool on a dish: "unpaired". *Needs
      the client (screens, chat).*
- [~] **2. Three sites chained.** Core Site; dish D0 within 24 blocks of it; site 1 (a Signal Mast) about
      50 blocks out with two dishes within 8 blocks of its base; site 2 the same about 50 blocks further,
      site 3 one more mast and dish 50 blocks further; dishes on pillars so the hops clear the ground.
      Pair D0 with site 1's first dish, site 1's second with site 2's first, site 2's second with site
      3's dish. With the RF Lens on (lobes layer), the three hops are green lines labelled
      "UP · ... dBm (+... dB)", and all three lobes are lit. Break site 2's first dish: within 5 s the
      lobes of sites 2 and 3 turn grey, the middle line disappears, and the meter beside site 3 loses
      it. `/rancraft backhaul status` lists sites 2 and 3 under "Off the air". Put the dish back and pair
      it again: lit again within 5 s. *Needs the client (lens, meter).*
- [~] **3. A tree in the path.** Make one hop about 1000 blocks long (`/tp` along it; the far end's
      chunk may unload, it still counts). Plant an oak sapling under the hop's midpoint so its canopy
      will reach the line, and bone-meal it. Within 5 s of the tree growing: the line is orange
      ("DEGRADED"), the dish reads DEGRADED with the leaves' loss, and the Field Test Meter served by a
      site behind the hop shows "BH: LIMITED (capped FAIR)" (its bars stay the radio's own). Cut the
      leaves out of the line: green again. *Needs the client; the Storage Terminal half is slice 13
      (slice 13 checks, step 4).*
- [~] **4. A storm.** On the 1000-block hop, put one stone block on the line (the label reads about
      −69.5 dBm, orange). `/weather thunder`: within 5 s the line turns red (DOWN), the dish reads "rain
      6.0 dB". `/weather clear`: orange again. (The midpoint must be in a biome where it rains: not a
      desert, not a snowy biome.) *Needs the client.*
- [~] **5. Flag off.** Quit, set `requireBackhaul = false`, reopen: every site is lit whatever its
      backhaul, the meter shows no "BH:" line, and `/rancraft backhaul status` says "still on the air
      because requireBackhaul is off". *Needs the client.* *(Row 16b: since the Phase 3C review a cell
      behind a DEGRADED hop is capped at FAIR with the flag off too, so take step 4's stone off the line
      first; with it there, the meter behind it reads "BH: LIMITED (capped FAIR)", which is correct.)*
- [~] **6. Survival.** `/gamemode survival`, iron pickaxe: a Core Site and a Backhaul Dish each break
      with the crack animation and drop as an item (headless: `HarvestGameTests`). *Needs the client.*

## Slice 13 (Storage Terminal) checks

Headless (verified by `./gradlew build`, 567 tests, 0 skipped, and `./gradlew runGameTestServer`,
39 of 39 in 26 batches; details in NOTES.md, slice 13). The game tests run vanilla's own
`ServerPlayer.openMenu` and the `stillValid` check in `ServerPlayer.tick` that closes a menu.

- [x] **Binding, at runtime**, through the real `ItemStack.useOn`:
  - a plain use binds nothing;
  - sneak + use on a chest 40 blocks from the Core Site is refused, and so is a dispenser;
  - a chest, a double chest (at its left half) and a barrel within 24 blocks are bound, as dimension and
    position in the data component;
  - with the core broken, a bind is refused and the old binding kept.
- [x] **The requirement, at runtime:**
  - GOOD+ on band_900 is refused with "needs band tier 2, you're on band_900 (tier 1)";
  - band_1800 opens;
  - a use before any dispatch is refused with "no signal reading".
- [x] **A session, at runtime:**
  - a chest opens as 3 rows and a double chest as 6, with both halves in vanilla's order; a barrel opens
    as 3 rows;
  - each on vanilla's chest menu type;
  - a stack put in through the menu is in the real chest, one put in the chest shows in the menu, and
    shift-click works;
  - the chest's opener count stays 0 through a remote open and close, so the lid is never lifted and
    the next player's lid still works.
- [x] **`stillValid` checks the last verdict, at runtime:**
  - the session holds over three intervals of OK verdicts, and over two more with the terminal in the
    hotbar;
  - it closes, with "connection lost (reason)" told to the player, when:
    - the signal drops below GOOD (a real evaluation 195 blocks out: FAIR);
    - the terminal stops being dispatched (41-42 ticks after its last dispatch, at interval 20);
    - a half of the double chest is broken;
    - the Core Site is broken;
  - closed by the player, nothing is reported lost.
- [x] **Same dimension only, at runtime:** a Nether binding is refused before anything else, and no
      Nether chunk is loaded.
- [x] **Never force-load, at runtime:**
  - the storage's chunk released during a session closes it with "storage unreachable: its chunk is
    not loaded", while the radio verdict is still OK;
  - a use is then refused and leaves the chunk out of FULL, before and after it really unloads.
- [x] **3C done-when, the Storage Terminal half, at runtime** (`requireBackhaul` on): six leaves placed
      with their events on a 1000-block hop (18 dB, DEGRADED, Fresnel clear) make the sector behind it
      LIMITED.
  - The open session closes with "backhaul limited: the serving cell is capped at FAIR, needs GOOD".
  - The sample's own level stays GOOD+.
  - A Radio Link on the same cell follows its transmitter off and on.
  - With the leaves cut out, the terminal opens again.
- [x] Unit (`StorageTerminalTest`, 8):
  - the requirement;
  - the kept record (a replay keeps the dispatch's tick and the cap);
  - two intervals of freshness;
  - each reason, with BACKHAUL_LIMITED told from WEAK_SIGNAL;
  - the reasons' arguments;
  - 27 or 54 slots;
  - "near a core", equal to the graph's fiber rule.
- [x] No version bump: no payload, block or save format added, and nothing new on the wire.
      `rf` / `util` untouched (`PackagePurityTest`). The session check costs 0.44 µs per tick.

Needs a human in game (creative). Set a Sector Antenna to band_1800 in its configuration screen, aimed
at where you stand; the Field Test Meter shows the level.

- [~] **1. Bind.**
  - Place a Core Site, a chest 5 blocks from it, and a second chest 30 blocks away.
  - Sneak + use a Storage Terminal (creative tab) on the far chest: "no Core Site within 24 blocks of
    this Chest".
  - Sneak + use the near chest: "bound to the Chest at x, y, z (Core Site 5 blocks away)". The tooltip
    shows "Bound to …".
  - Sneak + use a furnace: "only a chest or a barrel can be bound".

  *Needs the client (action bar, tooltip).*
- [~] **2. Open.**
  - Stand where the meter reads GOOD or EXCELLENT on band_1800 and carry the terminal for a second.
  - Use it in the air, not on a block. The ordinary chest screen opens, titled "Storage Terminal:
    Chest", with the chest's items.
  - Move items in and out, then open the chest by hand: they are there. If the chest is in view behind
    the screen, its lid stays shut.
  - Set the sector to band_900 and use the terminal: "needs band tier 2, you're on band_900 (tier 1)".

  *Needs the client (screen).*
- [~] **3. Losing the session.**
  - With the screen open, move the terminal out of the hotbar, into the chest or the backpack. Within
    about 2 s the screen closes and the action bar reads "connection lost (no signal reading …)".
  - Walk out along the sector's beam until the meter reads FAIR (about 200 blocks in open air), then
    use the terminal: "signal too weak: FAIR, needs GOOD".
  - Optional: ride a minecart out of coverage with the screen open. It closes with "connection lost
    (signal too weak …)".

  *Needs the client (screen closing).*
- [~] **4. A backhaul-limited cell (3C done-when).**
  - Set `requireBackhaul = true` (game closed). *(Row 16b: no longer needed since the Phase 3C review:
    the cap applies with the default config, as row 16a's step 1 checks. With the flag on it works the
    same way.)*
  - Build slice 12 step 3's 1000-block hop, with the band_1800 sector behind it and a chest near the
    core. Bind the chest; the terminal opens.
  - Open the terminal and grow the tree into the line, or have it grown. Once the meter reads "BH:
    LIMITED (capped FAIR)", the screen closes with "connection lost (backhaul limited: the serving cell
    is capped at FAIR, needs GOOD)", and a use is refused with the same words.
  - A Radio Link pair beside the sector still follows its transmitter.
  - Cut the leaves out: the terminal opens again.
  - Set the flag back to `false`.

  *Needs the client.*
- [~] **5. Unreachable.**
  - Bind a chest near a core and go 300+ blocks away, past the view distance so its chunk unloads, to a
    spot with GOOD band_1800 coverage. Use the terminal: "storage unreachable: its chunk is not loaded
    (x, y, z)".
  - In the Nether: "storage unreachable: it is in minecraft:overworld …".

  *Needs the client.*

## Slice 14 (Proximity Scanner) checks

Headless (verified by `./gradlew build`, 594 tests, 0 skipped, and `./gradlew runGameTestServer`, 40 of
40 in 27 batches; details in NOTES.md, slice 14). The game test reads the `ScannerPayload` that reaches
the player's connection, sent through the ticker's own carried-device scan and dispatch
(`SignalTicker.dispatchToCarried`).

- [x] **The requirement, at runtime:** a sector made tier 3 by a real Wideband Radio Unit (through
      `ItemStack.useOn`) and set to band_3500, 12 blocks from the player: GOOD or better, status OK,
      "band_3500 (tier 3)", range 24.
- [x] **The list, at runtime** (mobs without AI, on stone):
  - a creeper 5 blocks east, a witch 8.49 south-west, a spider 10 north behind a stone wall, a slime 13
    south and a creeper exactly 24 south are listed in that order, with distances and bearings (90, 225,
    0, 180, 180) within 0.01;
  - a cow 4.24 blocks away and a creeper 30 blocks out are not;
  - with 14 silverfish added on a ring 20 blocks out, 19 hostiles are in range and 16 are listed, nearest
    first; the creeper at 24 is the one cut;
  - a replayed sample lists a moved mob where it is now.
- [x] **Which scanner sends, at runtime:** one payload per dispatch, also with a scanner in each hand;
      none from a scanner in the hotbar; none to a dead player.
- [x] **3C done-when, at runtime:** the same sector on band_1800 at GOOD or better: LOW_TIER, no list,
      band_1800 tier 2, needs GOOD tier 3. `ScannerHudText` words it "needs tier 3, you're on band_1800
      (tier 2)".
- [x] Unit (27 new):
  - `ProximityScanTest` (5): range inclusive, bad input, the cap, bearings, stable order.
  - `ScannerPayloadTest` (6): round trips, refusals carry no list, the 16 cap, malformed input, clamped
    strings, the defensive builder.
  - `ProximityScannerTest` (4): the requirement, each verdict's status, a refusal's payload, which
    scanner sends.
  - `ScannerHudTextTest` (7) and `ClientScannerStateTest` (3): the HUD text, each reason's words, the
    arrows, staleness.
  - `HudStackTest` (+2): the top-right claim.
- [x] `PROTOCOL_VERSION` 9 → 10 (new `ScannerPayload` v1). `util/ProximityScan` passes
      `PackagePurityTest`. No block, no save format. One scan costs 23.7 µs with 20 mobs around, once per
      interval per player holding a scanner with an OK verdict.

Needs a human in game (creative). Place a Sector Antenna, use a Wideband Radio Unit on it, and set it to
band_3500 in its configuration screen, aimed at where you stand. The Field Test Meter shows the level.

- [~] **1. The list.**
  - Stand 10-30 blocks out on the sector's beam, where the meter reads GOOD or EXCELLENT, and hold a
    Proximity Scanner (creative tab).
  - Top-right: "Proximity Scanner" with "0 within 24", "via band_3500 (tier 3)" and "No hostiles within
    24 blocks".
  - Spawn a few hostile mobs with spawn eggs, one behind a wall. Within about a second they are listed,
    nearest first: an arrow, the name, whole blocks and a compass point with degrees ("NE 047°"). The one
    behind the wall is listed too: the scanner is not radar.
  - Turn round: the arrows turn with you, and the list does not change.
  - A cow, a villager or a wolf is never listed.

  *Needs the client (HUD).*
- [~] **2. Beside the meter.**
  - Hold the meter in the other hand, in compact mode: the scanner's list starts under the meter's
    top-right readout and never overlaps it.
  - Switch the meter to detailed (top-left): the list moves up to the top-right corner.

  *Needs the client (HUD layout).*
- [~] **3. The refusals (3C done-when).**
  - Set the sector to band_1800, still GOOD or better: "OFF", "needs tier 3, you're on band_1800 (tier
    2)" and "change the band: the feed needs a high-capacity one". No list.
  - Back on band_3500, walk out along the beam until the meter drops below GOOD: "signal too weak: FAIR,
    needs GOOD".
  - Optional, `requireBackhaul = true` with slice 13 step 4's DEGRADED hop in front of the band_3500
    sector: "backhaul limited: the serving cell is capped at FAIR, needs GOOD".

  *Needs the client (HUD).*
- [~] **4. Put away.**
  - Move the scanner to the hotbar: its HUD goes at once.
  - Take it back: "NO DATA" ("No reading from the network yet") for up to a second, then the list.

  *Needs the client (HUD).*

## Slice 15 (power) checks

Headless (verified by `./gradlew build`, 619 tests, 0 skipped, and `./gradlew runGameTestServer`, 48 of
48 in 30 batches; of the first eight runs, three failed only the Radio Link cost gate while the machine
was slow, and every run since, on fresh worlds and the accumulated one, passed with medians of 13.7-25.5
µs against slice 14's 18.1-21.1: follow-ups. Details in NOTES.md, slice 15).

- [x] **The model** (`PowerModelTest`): the §3C.5 table (a sector 10.7 FE/t at 20 dBm, 70.7 at 30 dBm),
      the PA term exactly 10× per 10 dB, mast 2 / sector 4 / +4 wideband.
- [x] **The buffer and the latch** (`EnergyBufferTest`): a fractional draw paid exactly; out of energy →
      empty, latch off; back only strictly above 10 %; on below 10 % until a tick cannot be paid.
- [x] **The capability, at runtime** (`level.getCapability(Capabilities.EnergyStorage.BLOCK, ...)`): an
      antenna's is receive-only with 10,000 FE, takes nothing while `requirePower` is off; a column's top
      mast reads and fills the base's buffer.
- [x] **Off the air and back, at runtime:** out of energy the cell is unregistered with `OnAir` false in
      its update tag (what the lens greys); 9 % keeps it off for 40 ticks; 10.01 % brings it back at the
      next tick.
- [x] **No flapping, at runtime:** a 30 dBm sector on one generator cycles 26 ticks off, 34 on.
- [x] **The fuel bill, at runtime** (3C done-when): 320 against 2,120 burn ticks, 3 against 21 sticks.
- [x] **The generator, at runtime:** a hopper pours fuel in and keeps dirt out; a lava bucket burns
      20,000 ticks and its bucket goes to a hopper below; use with fuel, sneak + use; it drops itself to an
      iron pickaxe (`HarvestGameTests`); beside a full buffer it burns nothing.
- [x] **Columns, at runtime:** a generator by any mast fills the base; a mast placed under the base takes
      the energy; a sector on a pole is fed through the pole.
- [x] **Flag off, at runtime:** nothing changes (3C done-when, power half).
- [x] **The seam, at runtime:** a Radio Link receiver is counted for the mast that serves it.
- [x] Versions: `AntennaBlockEntity.DATA_VERSION` 3 → 4 (`Energy`, `PowerOn`, saved and loaded back, out of
      the update tag); the generator's `DataVersion` 1; `PROTOCOL_VERSION` 10 unchanged.
- [x] Cost: the draw 0.064-0.070 µs per cell on the air (200 cells: median 16.1-20.1 µs/tick), and
      0.17-0.22 µs (48.7-62.9 µs/tick) while the machine was slow; an idle generator's tick 0.11-0.69 µs.

Needs a human in game (creative is fine). The config is `config/rancraft-common.toml`; NeoForge reloads it
while the game runs. `/rancraft power status` (operator) shows each cell's draw, buffer and state.

- [~] **1. Flags off (the default).**
  - Open a world from before this slice (Phase 2 or earlier in Phase 3): every tower still transmits and
    reads as before.
  - Place a Site Generator next to a Sector Antenna and use coal on it. It stays dark; use on it says
    "idle, requirePower is off on this server". The coal stays in it.

  *Needs the client (an old world, the generator's look).*
- [~] **2. requirePower on.**
  - Set `requirePower = true`. Within a second every tower without power goes off the air; the RF Lens
    (lobes layer) draws it grey, and the meter loses it.
  - Put coal in the generator beside the sector: it lights (glowing front, light level 13). About a second
    later the sector comes on the air (its lobe lights up). `/rancraft power status`: about 10.67 FE/t at
    20 dBm, the buffer filling towards 10,000.
  - Take the coal out (sneak + use with an empty hand) and let the item already burning finish (it burns
    only as fast as its power is taken: at 20 dBm a coal lasts about 5 minutes). Then the full buffer
    keeps the 20 dBm sector on for about 47 s, and it goes grey. Put fuel back: it returns once the buffer passes 1,000 FE (about 25 s), not at the first
    FE.

  *Needs the client (the lens, the generator's light).*
- [~] **3. The fuel bill (3C done-when, felt).**
  - Set the sector to 30 dBm in its screen. The status shows about 70.67 FE/t. On one generator it now
    cycles: about 1.3 s off, 1.7 s on (the lobe blinks slowly, never every tick).
  - Add a second generator beside it: it stays on. One coal lasts about 5 minutes at 20 dBm and about 45
    seconds at 30 dBm (64,000 FE each).

  *Needs the client (watching it over minutes).*
- [~] **4. Towers and poles.**
  - Build a column of three Signal Masts and put the generator next to the top one: the tower comes on.
  - Put a Sector Antenna on top of the column: the column goes quiet, and the same generator now feeds the
    sector, which comes on.

  *Needs the client (the lens on a tower).*
- [~] **5. Hoppers and breaking.**
  - A hopper with coal on top of a generator fills it. A lava bucket in a generator leaves an empty bucket,
    which a hopper under the generator takes out.
  - Break the generator in survival with a pickaxe: it drops itself and its fuel.

  *Needs the client (hoppers in play).*

## Slice 16 (recipes, loot tables, creative tab) checks

Headless (verified by `./gradlew build`, 619 tests, 0 skipped, and `./gradlew runGameTestServer`, 63 of
63; details in NOTES.md, slice 16):

- [x] **Every item has a recipe that loads and crafts, at runtime** (`RecipeGameTests`, one generated
      test per `ModItems` entry, 14 of 14): the server loaded it, every ingredient resolves to a real item,
      its grid built from real stacks is matched by it and no other recipe (each alternative of each
      ingredient tried in turn: the receiver's repeater and comparator, every item of every tag), and
      assembling the grid gives the item.
- [x] **No collision with vanilla:** at runtime (above), and a scratch check of every grid, mirrored and
      not, against all 887 vanilla crafting recipes with tags expanded: 0.
- [x] **The chain ends in vanilla:** the one RANCraft ingredient (the Signal Mast in the Sector Antenna)
      has a recipe of non-RANCraft items.
- [x] **Recipe-book unlocks, at runtime:** an advancement rewards each of the 14 recipes.
- [x] **Every block drops itself to an iron pickaxe and is pickaxe-mineable** (`HarvestGameTests`, 7 of 7;
      the loot tables and tag predate this slice).
- [x] **The creative tab, at runtime:** built on the server, it holds all 14 items once each; every block
      has an item.
- [x] **The checks catch what they claim:** seven deliberate breakages (a recipe removed, an unlock
      removed, a vanilla-style collision, a collision on the repeater alternative only, an unknown tag, a
      recipe cycle, an item left out of the tab) each failed its test with a message naming the cause;
      the data was restored and regenerated identical.
- [x] **The log is clean:** 1,305 recipes loaded (+14), 1,414 advancements (+14), no `ERROR` line, no
      recipe or advancement parsing error, no loot-table error.
- [x] Versions: none changed (`PROTOCOL_VERSION` 10, `AntennaBlockEntity.DATA_VERSION` 4).

Needs a human in game: a **new survival world** (the full survival playthrough §7 asks for). The grids
are in NOTES.md, slice 16, and in the recipe book once unlocked.

- [~] **1. Early game.**
  - Mine and smelt some copper and iron. With a copper ingot in the inventory, a crafting table's recipe
    book shows the Field Test Meter, the RF Lens and the Signal Mast.
  - Craft 4 masts from 6 iron bars and a copper ingot, then a meter. With one amethyst shard (a geode),
    craft the lens.
  - Place a mast: the meter reads it, and the lens shows its lobe.
  - A compass shows the Network Locator. A redstone torch shows both Radio Links. The receiver crafts
    with a repeater (no Nether needed) and with a comparator.

  *Needs the client (crafting by hand, the recipe book).*
- [~] **2. Mid game.**
  - Holding a Signal Mast shows the Sector Antenna, Core Site, Backhaul Dish and Site Generator. Craft a
    sector (it uses up a mast) and put it on the mast column.
  - Craft two dishes: the Link Tool appears in the book. Craft it and pair the dishes.

  *Needs the client.*
- [~] **3. Late game.**
  - With a Sector Antenna in the inventory the Wideband Radio Unit appears. Craft it (4 gold, 4 amethyst,
    a redstone block) and fit it: band_3500 unlocks in the sector's screen.
  - An ender pearl shows the Storage Terminal.
  - A sculk sensor (Deep Dark), or holding the Wideband Radio Unit, shows the Proximity Scanner.

  *Needs the client (a Deep Dark trip for the sculk sensor).*
- [~] **4. Breaking.**
  - With an iron pickaxe in survival, each of the seven blocks drops itself: Signal Mast, Sector Antenna,
    both Radio Links, Core Site, Backhaul Dish, Site Generator.
  - With a bare hand, each one is slow to break and drops nothing.

  *Needs the client (headless half: `HarvestGameTests`).*
- [~] **5. Creative tab.** The RANCraft tab lists all 14 blocks and items. *Needs the client.*

## Phase 3C review (row 16a) checks

Headless (verified by `./gradlew build`, 625 tests, 0 skipped, and `./gradlew runGameTestServer`, 64 of
64 in three runs; details in NOTES.md, "Phase 3C review, round 1"):

- [x] **Finding 1, headless:** `serviceCap(LIMITED, false)` is FAIR, `serviceCap(NONE, false)` and
      `serviceCap(null, false)` no cap, nothing off the air with the flag off
      (`BackhaulGraphTest.requireBackhaulOffChangesNothing`); with no Core Site every cell is NONE and
      uncapped whatever the hops (`noCoreNoCapWithRequireBackhaulOff`, new); the flag-on behaviour is
      unchanged (`serviceCapWithRequireBackhaul`, the chain game test).
- [x] **Finding 1, at runtime, with `requireBackhaul` off (the default):** six leaves on a 1000-block
      hop make the sector behind it LIMITED, capped at FAIR; the real Storage Terminal's session closes
      with "backhaul limited" and its use is refused, while the Radio Link on the same cell keeps working
      (`StorageTerminalGameTests.a_limited_cell_stops_the_terminal_the_radio_link_keeps_working`; fails
      with the old gate: "capped at FAIR").
- [x] **Finding 2, headless:** `BudgetedQueueTest` (5, fake clock): order, no duplicates; no item starts
      after the budget is spent and a drain overruns by at most one; at least one item per drain whatever
      the budget; unbounded drains all; a re-queued item waits.
- [x] **Finding 2, at runtime:** 64 parallel 1000-block hops, all new (a server start) and then all
      dirtied by one block in the bin they cross, are marched by the server's own recompute over 16 to 34
      ticks; the worst tick is 0.31 to 0.76 ms on the final code, against 3.3 to 4.6 ms for the same
      marches in one tick; nothing is published mid-way (hop 0 still reads UP while its stone is being
      measured); the spread result equals an unbounded recompute exactly
      (`BackhaulGameTests.many_dirty_long_hops_are_marched_over_ticks_under_the_budget`; fails with an
      unlimited budget).
- [x] Versions: none changed (`PROTOCOL_VERSION` 10, `AntennaBlockEntity.DATA_VERSION` 4,
      `BackhaulNetwork.DATA_VERSION` 1). One COMMON config key added, `backhaulMarchBudgetMs` (0.25).

Needs a human in game:

- [~] **1. The LIMITED cap with the default config.**
  - *(Row 16c correction: at 30 blocks one stone leaves the hop UP. A clear hop's RSL is about −3 dBm
    at 30 blocks and one stone costs 36 dB, so the hop must be roughly 110 to 1,000 blocks long; and a
    chest by a core that far away is not loaded. Follow "How to test Part 3C in game", step 5, which
    uses a 1000-block hop and a second Core Site for the chest.)*
  - A world with `requireBackhaul` false (the default). Place a Core Site, and a Sector Antenna on a mast
    at least 30 blocks away. Put a Backhaul Dish beside the core and one beside the sector's mast, pair
    them with the Link Tool, and check that the link is clear (lens, lobes layer: the line is green).
  - Bind a Storage Terminal to a chest near the core, and open it near the sector at GOOD or better.
  - Put **one** stone block (or about six leaves) on the link's line, midway. Within 5 s:
    - the line turns orange (DEGRADED);
    - the meter's detailed readout shows "BH: LIMITED (capped FAIR)";
    - the terminal closes with "backhaul limited";
    - `/rancraft backhaul status` lists the sector under "LIMITED, devices capped at FAIR".
  - Remove the block: the line is green again and the terminal opens.

  *Needs the client (the meter line, the closing screen and the lens colour).*
- [~] **2. A world without backhaul is unchanged.** Open a Phase 2 world (no Core Site, no dish) with
      both logistics flags off: every cell transmits and every device works as before; the meter shows
      no "BH:" line. *Needs a real Phase 2 save in the client (the 3C done-when's remaining check).*
- [~] **3. No hitch after a restart with a large backhaul network (optional).** With twenty or more long
      links built, save and quit, then reopen: no visible stutter in the first seconds (F3 tick graph, or
      `/tick query`), and the links appear on the lens within a second or two. *Needs the client and a
      large build; the cost itself is measured headless (above).*

## 3C done-when

*Row 16b: each item re-checked against the code, the tests named and this step's runs (`./gradlew
build` 625 passed, 0 skipped; `runGameTestServer` "All 64 required tests passed", after the comment-only
part 1). Two are `[x]`: verified at runtime with real blocks, nothing left that only the client shows.
Six are `[~]`: the server half of each is verified at runtime on the final code, and what is left is
on screen (a greyed band, a grey lobe, a line's colour, a HUD list, the meter's "BH:" line) or needs
a real Phase 2 save. The route through all of them is "How to test Part 3C in game" at the end of this file (row 16c).*

- [~] A tier-2 sector cannot use band_3500 until it gets a Wideband Radio Unit. *Server half verified
      at runtime (slice 10: refused through the real `applyOn`, accepted after a unit is fitted through
      the real item use path). The greyed band and the disabled Apply on the screen need the client
      (slice 10 checks above).*
- [~] With requireBackhaul on: three microwave-chained sites are on air; breaking the middle dish takes
      the far site off air and the lens greys it. *(Slice 11: the graph half is pinned headless,
      `BackhaulGraphTest.breakingTheMiddleDish` and `fullViaUpChain`. Slice 12: server half verified at
      runtime, `BackhaulGameTests` chain: real blocks paired with the real Link Tool, all three FULL and
      on the air; the middle dish broken, the far sites unregistered with `OnAir` false in the update tag,
      which is what the lens greys. The grey lobe on screen needs the client: slice 12 checks, step 2.)*
- [~] A tree grown into a link path → DEGRADED, cells behind read BH: LIMITED, Storage Terminal stops,
      Radio Link keeps working. *(Slice 11: six leaves on a hop is DEGRADED and a DEGRADED hop makes the
      cells behind it LIMITED, both headless. Slice 12, at runtime with a stone placed with its event
      standing in for the tree (tree growth bumps its bins: slice 7, `RegionEpochGameTests`): the hop
      DEGRADED at the next recompute, the cells behind it LIMITED and capped at FAIR, a GOOD requirement
      (the Storage Terminal's) refused and a POOR one (the Radio Link's) kept on a live sample, the
      meter's line "BH: LIMITED (capped FAIR)". Slice 13: the server half end to end at runtime,
      `StorageTerminalGameTests.a_limited_cell_stops_the_terminal_the_radio_link_keeps_working`. Six oak
      leaves, placed with their events on a 1000-block hop, stand in for the canopy and make it DEGRADED
      by exactly 18 dB. The sector behind it goes LIMITED. The real Storage Terminal's open session
      closes with "backhaul limited" and its use is refused, while the real Radio Link on the same cell
      follows its transmitter off and on. With the leaves cut out, the terminal opens again. A real tree,
      the meter and the closing screen need the client: slice 12 checks, step 3, and slice 13 checks,
      step 4. Phase 3C review (row 16a): until then this held only with `requireBackhaul` on, which the
      game test set; the cap now applies with the flag off too, and the same game test runs with the
      default config. In game: row 16a checks, step 1.)*
- [~] A marginal link drops in a thunderstorm and recovers after. *(Slice 11: pinned headless,
      `MicrowaveLinkTest.marginalHopInAThunderstorm`. Slice 12: server half verified at runtime,
      `BackhaulGameTests` weather: a one-stone 1000-block hop, DEGRADED, goes DOWN in a thunderstorm and
      in rain and is DEGRADED again when the sky clears, each picked up by the server's own recompute.
      The line's colour on the lens in a real storm needs the client: slice 12 checks, step 4.)*
- [~] Proximity Scanner works on band_3500 at GOOD, refuses on band_1800 with "needs tier 3". *(Slice
      14: server half verified at runtime, `ProximityScannerGameTests`. A sector made tier 3 by a real
      Wideband Radio Unit, on band_3500 at GOOD or better, sends the list of hostile mobs within 24
      blocks with the right distances and bearings, at most 16. The same sector on band_1800 at GOOD or
      better sends LOW_TIER and no list, which the HUD words "needs tier 3, you're on band_1800 (tier
      2)". The list and the text on screen need the client: slice 14 checks, steps 1 and 3.)*
- [x] With requirePower on, a 30 dBm sector burns fuel ~7× faster than a 20 dBm one. *(Slice 15,
      verified at runtime with real blocks, `PowerGameTests.a_30_dbm_sector_burns_fuel_about_seven_times_faster`:
      a 20 dBm sector on one real Site Generator and a 30 dBm sector on two, both burning real sticks
      through NeoForge's burn-time lookup, in the steady state for 1,200 ticks: 320 against 2,120 burn
      ticks, exactly the model's 10.667 and 70.667 FE/t over 40 FE/t, ratio 6.625; 3 sticks against 21;
      both on the air throughout. Identical in every run. Feeling the bill over minutes in a real
      game is in the slice 15 checks, step 3.)*
- [x] Every block and item is craftable in survival and every block drops itself. *(Slice 12: Core
      Site and Backhaul Dish drop themselves, `HarvestGameTests`; slice 15: the Site Generator too.
      Slice 16, verified at runtime: `RecipeGameTests` finds a loaded recipe for each of the 14 items,
      whose grid built from real stacks is matched by it alone and crafts the item, from ingredients that
      end in vanilla, with an advancement that shows it in the recipe book; `HarvestGameTests`: all 7
      blocks drop themselves to an iron pickaxe. Crafting by hand in a real world is the survival
      playthrough, slice 16 checks.)*
- [~] With both logistics flags off (default), a Phase 2 world plays exactly as before. *(Slice 12:
      the backhaul half holds: with `requireBackhaul` off no cell is held off the air or capped
      (`BackhaulGraphTest.requireBackhaulOffChangesNothing`; at runtime a cell with no backhaul stays on
      the air, uncapped, and recomputes never move the site registry). Slice 15: the power half holds at
      runtime, `PowerGameTests.require_power_off_changes_nothing`: cells with empty buffers are on the air
      and registered, their capability takes no energy, a generator with coal beside them does not light
      or burn, and the site registry does not move in 100 ticks; a v3 (or older) save loads with an empty
      buffer that nothing reads. A real Phase 2 world opened in the client is the remaining check: slice
      15 checks, step 1. Phase 3C review (row 16a): with the flag off a LIMITED cell is now capped at
      FAIR, as §3C.2 states, but LIMITED needs a Core Site and a player-built DEGRADED hop; with no core
      every cell is NONE and uncapped (`BackhaulGraphTest.noCoreNoCapWithRequireBackhaulOff`), so a Phase 2
      world is still untouched. The backhaul recompute's marches are now spread under a per-tick budget.)*

---

## Follow-ups

Anything found along the way that is out of scope for the current slice goes here, with where it
came from.

- **[x] Done in the Phase 3A review round 1 (row 5a), for the ticker's link path.** (from RF Vision
  Step 2) The Step 2 adversarial review stopped after round 1 (account usage limit).
  Round 1 fixes are applied; rounds 2+ never ran. ~~Slice 4 rewrites the ticker's link-lens
  plumbing, so the Phase 3A review re-covers that code path.~~ *Slice 4 did not rewrite it: the link
  path was left in place (only `linkLensOf` / `linkCap` / `canReplay` extracted unchanged, and
  `chooseLinks` checked identical against the old code). A Phase 3A review should still cover it.*
  *(Row 5b: it did. The Phase 3A review round 1 had a dedicated ticker-regression reviewer, briefed
  to compare `SignalTicker` before Phase 3 (4153291) and after slice 0 (7b35d4f) with the current
  code and prove or disprove the link rays, the lens link cap, the "links are never starved" rule,
  the site-registry-version cache key and the evaluated ⇒ sent rule, among others. It reported no
  finding. What was never re-reviewed is the rest of Step 2's own code (client lens rendering,
  coverage painting) beyond Step 2's round 1; nothing points at a defect there, so no further
  action is planned unless a later review round runs.)*
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
- **[x] Done in slice 5 (e0fcfcd).** (from slice 0) §4's `PROTOCOL_VERSION` "+1 from whatever it is
  at start": Step 3a took it to "4", so 3A's bump is "4" -> "5". *(`ModPayloads.PROTOCOL_VERSION` is
  "5"; review round 1 changed no wire format.)*
- **[x] Done in slice 0a (0afda73).** (from slice 0) The drive-test log is kept until
  `/rancraftc drivetest clear`, per dimension. The client cannot reliably tell which world it joined,
  so joining a different world keeps the old trail under the same dimension names. Possible later
  fix: also key the log by server address or save name. *Fixed instead by clearing it on logging
  out; see the s0 gate item below.*
- **[ ] Open, for slice 17 (optional).** (from slice 0) Adding `fix_x, fix_z, fix_err` to the CSV means appending
  components to `DriveTestLog.Sample` (a public `rf` record, so append only) and columns at the end
  of `CSV_HEADER`; `DriveTestLogTest.csvIgnoresDefaultLocale` pins the current row exactly and will
  need its expected string extended.
- **[ ] Open, standing rule for slices 6 and 16.** (from slice 1, **deviation from the brief**) `PackagePurityTest` is
  stricter than "zero net.minecraft / net.neoforged / com.mojang imports": `rf` and `util` also may
  not name any other `dev.rancraft` package (the root package included), because every other
  package is game code and NeoForge is on the test classpath, so the unit tests would not catch
  Minecraft arriving one step removed. `rf` and `util` may use each other. Pure code must live in
  one of the two (e.g. ColumnScan in `util`, as §2 says). *Slice 6 complied: `util/ColumnScan` names
  no other package; the Minecraft side is `block/MastColumn`.* *(Row 9b: all of Part 3B complied:
  `rf/BinTraversal` (slice 7), `rf/BlerModel` and `rf/SplitMix64` (slice 9), and
  `ColumnScan.reportedRadiatingY` (row 9a) name no other package; their Minecraft sides are
  `world/RegionEpochs`, `device/RadioLinkNetwork` and `block/SignalMastBlockEntity`. Still a standing
  rule for Part 3C.)* *(Row 16b: all of Part 3C complied too. `rf/RadioTier` (slice 10),
  `rf/MicrowaveLink` and `rf/BackhaulGraph` (slice 11), `util/ProximityScan` (slice 14), `rf/PowerModel`,
  `util/EnergyBuffer` and `util/ServedReceivers` (slice 15) and `util/BudgetedQueue` (row 16a) import
  nothing from another `dev.rancraft` package (checked by grep and by `PackagePurityTest` in every
  build). Their Minecraft sides are `block/AntennaBlockEntity`, `world/BackhaulNetwork`,
  `device/ProximityScanner` and `world/SitePower`. Phase 3 is done; the rule stands for later phases.)*
- **[ ] Open, standing rule for every slice that adds a block (slices 6-16).** (from slice 1)
  `HarvestGameTests` generates one test per `ModBlocks.BLOCKS` entry
  and requires each block to be pickaxe-mineable and to drop itself. A new block needs a loot table
  in `data/rancraft/loot_table/blocks/` and an entry in `data/minecraft/tags/block/mineable/`
  (or an explicit exemption in the test). Run `./gradlew runGameTestServer`; it is not part of
  `build`. *(Row 9b: Part 3B added two blocks, both in slice 9, and complied: loot tables, the pickaxe
  tag, and a generated harvest test each, passing. Still a standing rule for Part 3C.)* *(Row 16b:
  Part 3C added three blocks and complied: `core_site` and `backhaul_dish` (slice 12) and
  `site_generator` (slice 15) each have a loot table and a pickaxe tag entry, and `HarvestGameTests`
  passes 7 of 7 in this step's run. Every `ModBlocks` entry is covered. The rule stands for later
  phases.)*
- **[ ] Open, minor.** (from slice 1) A game test run where nothing registered passes ("All 0 required tests passed",
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
- **[x] Resolved by the project owner (row 3a): kept**, because a centroid-only start reports a
  wrong fix with a small ± for 10.5-16.7 % of fixes outside the footprint. The code comments and
  NOTES.md label it a deliberate deviation from §3A.5 and say why; no code change.
  *(Original entry:)* **Decision needed (slice 3 deviation from §3A.5's algorithm).** Gauss-Newton from the
  centroid alone lands in a wrong local minimum for 10.5–16.7 % of FIX results once the receiver is
  outside the cells' footprint, **even with perfect ranges**, and reports it with a small ± (measured
  over 20,000 seeded scenes per case; e.g. FIX 390 blocks from the truth, "± 0", HDOP 4.3). Slice 3
  keeps the centroid as the first start and also starts from the circle crossings of the 3 strongest
  cells, taking a run that ends elsewhere with a smaller weighted residual: perfect ranges 0 %,
  band_900 1.3–5.2 % (the rest is genuine mirror ambiguity). When the centroid's run is the best fit,
  results are identical to the spec's. Cost about 19 µs per 8-cell fix plus the in-game surface
  lookups (slice 5 measured the whole solve on live ground: 22.2 µs, 37 lookups). To revert, delete the alternative-start loop in `LocatorSolver.leastSquares` and
  `notTrappedOutsideTheFootprint`. Details and the table in NOTES.md, slice 3.
- **[x] Resolved by the project owner (row 3a, f61a739): switched to the weighted covariance.**
  `errorBlocks = sqrt(trace((HᵀWH)⁻¹))`, W = diag(1/σ²), same H as HDOP; HDOP stays unweighted.
  Identical to HDOP × σ for single-band fixes (pinned; test 7 unchanged). Test 10: 35.49 → 5.12
  (6.93×) at the edge of a network; the good triangle pinned at 1.40× (below 3×, accepted). An
  owner-approved deviation from §3A.5's formula, recorded in NOTES.md with why it is the right
  estimate for a weighted fit. *(Original entry:)*
  **Spec conflict, decision needed (slice 3): test 10 vs `errorBlocks = HDOP × rms(σ)`.**
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
  live level: 77.9 ns per lookup, worst case ~~8.2~~ 8.8 µs per fix (113 lookups, not the 105 = 7 × 15
below; corrected in row 5b), a whole 8-cell solve 22.2 µs. (g) the cap
  is `LocatorFixPayload.MAX_RINGS`. (h) in the `LocatorHudText` javadoc and NOTES.md slice 5.* (from
  slice 3) (a) `RangeOnly.radius` is the measured *slant* range;
  decide how the ring is drawn (at the cell's height it is the widest circle of the range sphere).
  (b) `Ambiguous.likely` can be `NO_PREFERENCE` (-1): draw both markers alike then. (c)
  `PoorGeometry.hdop` is capped at 99.9 ("99.9 or worse", also for a singular geometry). (d) `Fix.y`
  is the assumed eye height (ground + 1.62), not a measurement. (e) Feed `Ranging.measure` the
  sample's **full** `cells()` (not the payload's 4), also on cached replays, and keep the per-player
  previous fix for `likely`. (f) The game-side `SurfaceProbe` must never load a chunk (reuse
  `CoverageSurveyor`'s), and its cost per fix must be measured: up to 7 runs × 15 lookups
(*undercount*: each run also reads the ground at its end point and the extra starts once under the
centroid, so 7 × 16 + 1 = 113). (g)
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
  ticker path with a Locator is still only checked in game. Still open for slice 8.* *(Row 5a added
  a NeoForge `FakePlayer` built directly, not on the list and with a connection that sends nothing,
  to call `NetworkLocator.onSample`: that exercises a device's server step, not the ticker's
  per-player path, so this stays open.)* *(Slice 8: fixed receivers need no player, so
  `FixedReceiverGameTests` runs the real `FixedReceiverTicker` on the real server tick with no
  connection at all. The per-player path of `SignalTicker` is still only checked in game; open.)*
  *(Row 9b: still open. The 3B done-when on a player's cache is verified through `canReplay` on a real
  evaluation, the region epochs on a live level and the same replay rule on the real fixed-receiver
  ticker; the per-player path is step 3 of "How to test Part 3B in game".)*
  *(Slice 13: still open. The Storage Terminal's game tests use a `SilentServerPlayer`, a real
  `ServerPlayer` whose connection drops every packet and which is not on the list. They reach the
  ticker's carried-device scan and capped dispatch through `SignalTicker.dispatchToCarried`, but not
  the per-player loop. The follow-up from slice 13 below gives an option for the loop.)* *(Row 16b:
  still open as Phase 3 closes. Slice 14 used the same route (`dispatchToCarried`, a
  `SilentServerPlayer`), so the scanner was checked through the carried scan and dispatch, not the loop.
  Slice 15's served-receivers seam is recorded for players inside the loop (`SignalTicker` calls
  `SitePower.noteServed` on an evaluation and on a replay), so its player half has no runtime test; its
  game test counts a Radio Link receiver, a fixed device. The loop itself is exercised by every in-game
  step that holds a device ("How to test Part 3C in game", row 16c), and `/rancraft power
  status` shows the served count there.)*
- **[x] Done in slice 8 (02d1790) (from slice 4).** *`FixedReceiverTicker.staleCandidateGapTicks(interval,
  lag)` is the receiver's own gap, `interval + lag` (lag = server ticks it was served past its due
  tick); `ReceiverStateStore.resume` is reused with it. A budget overrun keeps an armed candidate, a
  skipped turn (chunk not FULL) drops it; `FixedReceiverTickerTest` pins both and checks on the real
  scheduler that every gap is exactly `interval + lag`. NOTES.md slice 8, decision 1.*
  `staleCandidateGapTicks` is derived from the player
  ticker's cadence (exactly one interval, guaranteed by the stagger). `FixedReceiverTicker` is
  round-robin under a time budget, "at most once per `evaluationIntervalTicks`", so its gaps can be
  longer while the budget is exhausted. Reuse `ReceiverStateStore.resume` with a threshold derived
  from that ticker's own worst case, or a budget overrun will be read as a pause and delay handovers
  (by at most one TTT each time).
- **[x] Done in slice 7 (from slice 4).** `SignalTicker.canReplay` is now the single place the cache
  decision is made, and `SignalTickerCacheTest` pins every current condition. The region-epoch rework
  (§3B.2) replaces the `epoch` condition there; keep the rest, including the armed-candidate skip and
  "never starve links". *(Slice 7: the epoch condition is now "every dependency bin's epoch
  unchanged" (`Cached.isCurrent` via `RegionEpochs.unchanged`); every other condition is untouched and
  still pinned, 10 → 14 tests.)*
- **[x] Accepted, minor (from slice 4); no action.** A live change of `evaluationIntervalTicks` re-phases the stagger:
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
  whenever a lens shows the trail. *(Review round 1: the client now drops the reading every tick no
  Locator is held (`ClientLocatorState.putAway`), so read from the client state the columns are
  blank then, which matches the first option.)*
- **[ ] Spec conflict, deviation for the owner (from the Phase 3A review round 1, row 5a).** §3A.5
  says "3 or more usable **cells**" (and "2 cells", "1 cell"). In this tree a sector is its own cell
  on a neighbouring block (NOTES.md, Phase 2 "Sector antenna": no site container), so a three-sector
  site is three cells one block apart. Counted as cells they gave one site AMBIGUOUS on a ring, two
  sites a confident FIX on the wrong mirror image (921 of 2000 scenes) and an over-confident ± (6.45
  against an rms error of 10.21). The solver now counts **sites**: antennas within
  `locatorSiteMergeBlocks` (3.0) horizontally are one site with one range, a labelled abstraction
  for the site identity a real network knows. `LocatorFix.cellsUsed`, the rings and the HUD's
  "Cells N" count sites. Decide: keep (and perhaps relabel the HUD "Sites N"), or give the mod a
  real site identity later (slice 6's mast columns are a related grouping). `locatorSiteMergeBlocks`
  0 restores per-cell counting except for antennas stacked in one column. *(Row 5b: the RANGE ONLY
  line's "(one cell heard)" also means one site now; a relabel would change it with "Cells N".)*
  *(Slice 6: a column of stacked masts is now one cell, so the Locator's grouping no longer has to
  collapse stacks; a sector on a mounting pole is its own cell, as before, and sectors round one
  mast are still grouped only by `locatorSiteMergeBlocks`. Nothing changed in the solver.)*
- **[x] Done in row 5a (62fef10).** (from the Phase 3A review round 1) Drive-test de-duplication
  compared each sample with the previous, already replaced, sample, so any movement under 0.5 blocks
  per evaluation collapsed a whole walk into one moving entry in the trail and the CSV (a 100-block
  walk at 0.43 blocks per sample: 1 entry). Now measured from the still period's first sample (an
  anchor moved only on append): 116 entries. The server's sample cache used to hide it, but only
  with caching enabled and the dimension's block epoch not changing.
- **[ ] Open, minor (from the Phase 3A review round 1).** The meter's `ClientSignalState` (NO SERVICE)
  still treats a sample as stale after a fixed 5 s, so above 100-tick evaluation intervals (or under
  `/tick rate` below 4) the meter HUD flickers to NO SERVICE between samples. The Locator now uses
  the learned cadence (`ClientLensState.staleAfterMillis`); the same change fits the meter. Its
  payload is also a sample the drive-test log reads, so check that path when changing it.
- **[ ] Open, minor (from the Phase 3A review round 1).** The solver's `previous` is dropped once
  older than two intervals, but a same-dimension teleport (`/tp`, an ender pearl) while the Locator
  stays in the hotbar keeps a fresh, far-away previous for one evaluation, which can mark an
  AMBIGUOUS candidate "likely" once. Optional guard: drop the previous when its (x, z) is farther
  from both new candidates than the larger measured range.
- **[ ] Open, minor (from the Phase 3A review round 1).** A waypoint's saved ± is converted with the
  server's current `metersPerBlock` (from the latest payload), not the scale it was saved under. A
  server that changes the scale after saving shows a different ± than the save message did. More
  robust: store the scale (or the ± in metres) on the waypoint, an appended optional field of
  `LocatorWaypoints.Waypoint` plus a stream-codec change and a `PROTOCOL_VERSION` bump.

- **[x] Decided in slice 6, spec vs tree (recorded in NOTES.md, slice 6, decision 1).**
  §3B.1 writes `ColumnScan.bounds(...) → (baseY, topY)`. The record also carries `highestY`, the top
  of the whole run: with the height cap, "a sector antenna directly above the top mast" must look
  above the highest mast, not above the capped top, and the power gate covers the whole column. The
  cap only limits where the cell radiates from.
- **[x] Decided in slice 6, spec vs tree (NOTES.md, slice 6, decision 3).** §3B.1: "call
  refreshRegistration() on the base's block entity only". The re-scan also refreshes the mast that
  received the update, because a base that just became structure (a mast was placed under it) must
  unregister, and only it gets the update. That costs a two-read check on one structure mast; no
  other mast is touched, which is the rule's intent.
- **[x] Decided in slice 6, spec vs tree (NOTES.md, slice 6, decision 6).** §4 lists `+ OnAir` for the
  update tag with no `PROTOCOL_VERSION` change. Bumped 5 → 6 anyway: the update tag is wire format
  and the client now reads it (and collapses columns itself), so a slice 5 client on a slice 6
  server would draw a lobe per stacked mast and the reverse one lobe where nine cells transmit. No
  payload changed shape; `DATA_VERSION` stays 2.
- **[x] Done in the Phase 3B review (row 9a, 8b42665), the second fix below.** *The update tag carries
  `RadiatingY` (the server's radiating height) and the lens uses it whenever it fits the column the
  client sees; `PROTOCOL_VERSION` 6 → 7 (NOTES.md, "Phase 3B review, round 1", finding 4).* (from slice 6)
  `maxMastHeight` is COMMON, which NeoForge does not sync. The lens
  applies the client's own copy, so on a dedicated server whose cap differs from the client's, a
  column taller than the smaller cap is drawn with its lobe at the client's cap (the server radiates
  from its own). Single player is unaffected. Fixes: move the value to a SERVER config (synced), or
  append the base's radiating y to the update tag and let the lens prefer it.
- **[ ] Open, minor (from slice 6).** The census line ("N stacked masts now form M columns...") is
  logged on every server start, not only the first after the upgrade: slice 6 persists nothing that
  would mark a world converted (§3B.1: no persisted field changes in 3B). If once-per-world is
  wanted, the next save-format change (slice 10's `DATA_VERSION` 3) could carry a marker. *(Slice 10:
  still open. `DATA_VERSION` 3 alone is not a reliable marker: a loaded entity is re-saved only when its
  chunk is saved for another reason, so an unchanged column's masts stay v2 on disk and would be
  counted again. A marker needs a per-world flag (a `SavedData`), which is not slice 10's scope.)*
- **[x] Done in slices 12 and 15 (from slice 6).** `AntennaBlockEntity.onAir` is set only by
  `refreshRegistration`, from `isTransmitting()`. Backhaul (§3C.2 "Unregister and set OnAir false")
  and power (§3C.5) should feed `isTransmitting()` (or the mast's column rule) and call
  `refreshRegistration()`, not write the flag directly, so the registry and the lens cannot disagree.
  For a column, only the base's entity matters. *(Slice 12: done for backhaul. `isTransmitting()` is
  `eligibleToTransmit() && backhaulAllows()`, the old rule moved to `eligibleToTransmit()` (overridden
  by the mast column); the network calls `refreshRegistration()` on the cells whose state changed, which
  stays the one writer of `OnAir`. Power (slice 15) can add its verdict the same way.)* *(Slice 15:
  done for power. `isTransmitting()` is `eligibleToTransmit() && backhaulAllows() && powerAllows()`;
  `world/SitePower` calls `refreshRegistration()` on a cell whose latch changed or on every tracked cell
  when `requirePower` flips. The column's buffer is its base's.)*

- **[x] Decided in slice 7 (NOTES.md, slice 7, decision 1).** The dependency set is the union of
  `BinTraversal.binsAlong` over the marched rays' endpoints, as §3B.2 words it, not the bins of the
  voxels the probe actually read. It can include bins past an early exit (a needless re-evaluation,
  never a stale sample); the random-ray test shows the two agree exactly otherwise. Both side bins are
  taken where a ray passes through a bin corner.
- **[x] Decided in slice 7, beyond the spec (NOTES.md, slice 7, decisions 4 and 6).** Pistons bump
  twice, at `PistonEvent.Pre` and 4 game ticks later, because the moved blocks spend two ticks as
  0 dB `moving_piston` and land with no event. Tree growth (`BlockGrowFeatureEvent`) also bumps
  every bin within 16 blocks: §3B.2 does not list it, but slice 12's "tree grown into a link path"
  needs it.
- **[ ] Open (from slice 7).** Block changes with no event still do not invalidate a cached sample:
  fluid flow (water is 15 dB, the biggest one), fire spread and burn-out, leaf decay, falling sand
  and gravel, ice and snow, an enderman moving a block, `/setblock` / `/fill` / `/clone`, other mods.
  The same gaps existed with the per-dimension epoch. `BlockEvent.NeighborNotifyEvent` would catch
  most of them but fires on every redstone update (a clock would keep its bin invalidated); a
  narrower option per source (`FluidPlaceBlockEvent` for fluid-made blocks, a chunk-section check)
  if one of them matters in play.
- **[x] Done in slice 8 (02d1790) (from slice 7).** *`FixedReceiverTicker.Cached` keeps the snapshot
  and `canReplay` checks it with the site registry version, no move check. The empty-dimension piston
  corner stays, recorded in NOTES.md slice 8 (known behaviours).* Fixed receivers get the region epochs for free: keep a
  `RegionEpochs.Snapshot` of `evaluation.dependencyBins(RegionEpochs.BIN_SIZE)` per receiver and
  replay while `RegionEpochs.of(level).unchanged(snapshot)` and the site registry version hold (a
  block never moves, so no move check). One corner to know: in a dimension with no players and no
  forced chunk for over 300 ticks, block entities stop ticking, so a piston's moved blocks settle
  only when a player returns, after the deferred bump; a fixed receiver there could replay a sample
  taken mid-move until the next event in its bins.
- **[x] Done in slice 12 (from slice 7).** *`BackhaulNetwork` keeps a `RegionEpochs.Snapshot` of
  `MicrowaveLink.dependencyBins` per hop (the line's bins and the eight Fresnel segments', a superset of
  the line's alone) and re-marches a hop when it no longer holds; `RegionEpochs.bumps()` (new) lets a
  quiet tick skip the per-hop checks. Pinned at runtime: a stone placed with its event re-marches that
  hop only.* §3C.2's "a region epoch moves on any bin a link
  crosses" is `BinTraversal.binsAlong(dishA.x, dishA.z, dishB.x, dishB.z, RegionEpochs.BIN_SIZE)` plus
  a `RegionEpochs.snapshot` / `unchanged`, exactly as the player cache does. Tree growth already
  bumps (above).

- **[x] Decided in slice 8, spec vs tree, minor (NOTES.md, slice 8, decision 7).** §3B.3 says "NOTES.md
  records that sector antennas once missed the chunk paths". The record is in
  `SignalTicker.onChunkLoad`'s javadoc, not NOTES.md. No action beyond following the lesson: the
  fixed receivers' unload path drops by position, with no type check at all.
- **[x] Done in slice 9, 10c1de7 (from slice 8).** *(a) `RadioLinkGameTests.unloading_stops_it_reloading_resumes_it`
  unloads and reloads real Radio Links from disk; (b) 200 real radio links (400 blocks) measured at
  20-53 µs/tick, medians 27-44 (NOTES.md, slice 9); (c) closed by `FixedDeviceBlockEntity.clearRemoved`
  instead of `onPlace`, which `LevelChunk.setBlockState` calls before it creates the block entity
  (NOTES.md, slice 9, decision 9).* Two 3B done-when items finish with the Radio Link:
  (a) "reloading resumes them": add a game test that places a real Radio Link in a forced chunk,
  releases the chunk, waits for the unload (registry empty for that chunk), loads it again and checks
  that the load event registered it and the ticker serves it (slice 8's test entity borrows the chest
  type and comes back as a chest); (b) re-measure "200 radio links < 0.1 ms/tick" with 200 real radio
  links, whose `onSample` work comes on top of the 29-42 µs/tick measured for the ticker. Also: a
  device placed by a command in a dimension whose block entities are not ticking (no player, no forced
  chunk, over 300 ticks) registers only when they tick again (NeoForge defers `onLoad`; no chunk event
  for a loaded chunk); the Radio Link blocks can close it by also registering from their server-side
  `onPlace`.
- **[ ] Open, decision for the owner (from slice 8).** A fixed receiver never moves, so slice 7's
  event-less block changes (fluid flow above all: water is 15 dB; fire, leaf decay, falling blocks,
  `/fill`, other mods) can leave its replayed sample stale indefinitely, where a player's ends when
  they move half a block. Option: a maximum replay age (e.g. `fixedReceiverMaxReplayTicks`, a new
  `RanCraftConfig` value not in §5), re-evaluating each receiver at least that often: at 600 ticks
  about 200 x 25 µs / 600 ≈ 8 µs/tick for 200 receivers. Most relevant once the Radio Link exists
  (slice 9). *Slice 9: still open. The Radio Link now exists, so water flowing into a link's path can
  leave one end replaying a sample that is too good (or too bad) until the next event in its bins.*
  *(Row 9b: still open. The Phase 3B review's finding 1 was the one change of this kind that was not
  a block change (a chunk on the ray reaching or leaving FULL); it is fixed (row 9a). The block-change
  gaps above remain, and a maximum replay age would bound them all.)*
- **[x] Decided in slice 9, spec wording (NOTES.md, slice 9, decision 1).** §3B.4's receiver rule, read
  literally ("outputs 15 if any transmitter ... is powered and the last update from it was delivered;
  otherwise it holds its previous output"), would never turn a receiver off. Implemented per
  transmitter: the receiver remembers the state in the last delivered message from each transmitter
  and outputs 15 while any says powered; a lost message changes nothing.
- **[x] Decided in slice 9 (NOTES.md, slice 9, decisions 3 and 12).** The draw seed is the
  transmitter's `pos.asLong() ^ gameTime`, one stream per message, one draw per receiver in ascending
  key order. "200 radio links" is measured as 200 transmitter-receiver pairs (400 blocks), and the game
  test asserts the median of three windows because the same code varies by up to a factor of two
  between runs on the development machine. Any later cost assertion needs a similar margin.
- **[ ] Open, decision for the owner (from slice 9).** An unloaded transmitter is never timed out: its
  receivers hold its last delivered state for as long as its chunk stays unloaded (a real network
  would time out a silent terminal). Option: a maximum age for a remembered transmitter, a new
  `RanCraftConfig` value not in §5. Not needed for the done-when.
- **[ ] Open, minor (from slice 9).** A transmitter whose input changes turns every receiver on its
  address on or off inside its own dispatch, each a `setBlock` with neighbour updates. The budget is
  checked only between batches, so a toggle with dozens of receivers on one address is one burst. Not
  measured; measure it if a large build shows it.
- **[x] Done in slice 16 (from slice 9).** *Both have recipes (redstone torch and copper; the receiver
  adds a comparator or a repeater).* Both Radio Link blocks are creative-only until their
  recipes exist.
- **[ ] Open, minor (from slice 8).** The round robin scans every registered receiver's due tick every
  tick: 8-12 µs/tick at 200 (40-60 ns each, loop and lock included), so the scan alone nears 0.1
  ms/tick around 2,000 receivers. A timing wheel (one bucket per tick of the interval) would make the
  cost proportional to the due receivers. Not needed at the done-when's scale. *(Row 9b: with 400
  Radio Link blocks registered the scan measured 9-20 µs/tick in slice 9 and 15.7-22.2 µs/tick in row
  9b's two runs, a third of the whole cost there. It is the first thing to fix if a build packs
  thousands of Radio Links or a later phase adds more fixed devices; Part 3C's Storage Terminal and
  Proximity Scanner are carried items, so they add none.)*
- **[ ] Open, note for slices 12 and 15 (from slice 8).** The replay key includes the dimension-wide
  site registry version (§3B.2), so every antenna change, and every cell backhaul or power takes on
  or off the air, re-evaluates every fixed receiver in the dimension once (one interval at 200-390
  µs/tick for 200 receivers, within the budget). If a backhaul state flaps (rain fade), keep the
  flips hysteretic so this stays occasional. *(Slice 12: no hysteresis needed for backhaul (NOTES.md,
  slice 12, decision 10): a state changes only on an event (weather, a block on a path, a pairing, a
  cell coming or going), at most once per `backhaulRecomputeTicks`, and nothing oscillates; with
  `requireBackhaul` off the registry never moves (pinned at runtime). Still a note for power, slice 15.)*
  *(Slice 15: power has its hysteresis, the 10 % restart band, so a cell never flips every tick. But a
  cell on an undersized supply still cycles: a 30 dBm sector (70.7 FE/t) on one generator (40) goes off
  for 26 ticks and on for 34, measured, and each switch moves the site registry version. So every fixed
  receiver in the dimension re-evaluates about twice every 3 s while such a cell runs, held to
  `fixedReceiverTickBudgetMs` by the ticker. With `requirePower` off nothing moves (pinned). Owner
  decision, still open: accept it (it is the honest brownout, and the budget caps the cost), or widen
  the band (`powerRestartFraction` is already a setting; 0.5 makes the same cell cycle about 5× more
  slowly), or add a minimum off time.)*
- **[ ] Open, minor (from row 9b).** `RadioLinkGameTests.two_hundred_radio_links_in_steady_state`
  asserts the median of three windows under 100 µs. Row 9b's two runs measured medians of 58.9 and
  48.9 µs/tick (single windows up to 66.4), above the 26-48 of every earlier run, on code that
  differs from row 9a's only in comments. The done-when holds in every run, but a busier machine narrows the margin. If the test ever
  fails with no code change, look at the scan's share first (the timing-wheel follow-up above) before
  touching the bound. *(Slice 15: it failed in three of the slice's first eight runs (126.6, 120.6 and
  113.7 µs; the others 98.2, 96.5, 76.9, 37.0 and 36.1). Measured again with a control, alternately and
  minutes apart, each on a fresh game-test world: slice 14's code (`a1e9611`) 18.1 and 21.1 µs, slice
  15's (`797d641`) 13.7 and 21.5, and 25.5 and 20.8 on the accumulated world. So slice 15 adds nothing to it. The
  failures came while the machine was slow: the test's warm-up at interval 1 (as many replays as the
  0.5 ms budget allows) logged 7,836-16,368 replays in 100 ticks then, against 26,912-35,332 later, and
  the scan and setup, which slice 15 does not touch, were 2-3× slower too. The gate tracks the machine's
  load. Not changed here: it is slice 9's test, and its bound is the 3B done-when's. When it fails, read
  that warm-up count in the log first; if it is far below about 25,000, rerun on a quiet machine.)*
- **[ ] Open, minor (from the Phase 3B review, fix 1).** Chunks reaching or leaving FULL now bump their
  bin, so as a player walks, cached evaluations whose rays cross the edge of the loaded area are redone
  once (correct: the edge changed what those rays read), and vanilla's two-step ticket raise can bump a
  chunk twice in one call (needless, never missed). No game test has a walking player, so the cost was
  not measured. If a server with many players and fixed receivers shows it, measure with
  `/tick query` while players walk.
- **[x] Accepted, minor (from the Phase 3B review, fix 2); no action.** A Radio Link receiver whose
  chunk sits outside FULL changes only its memory and saves it on its next turn. If the chunk unloads
  first, the reloaded receiver starts from its last save: it looks its remembered transmitters up on
  its first turn, and the next delivered message (about a second later) corrects a missed state.
  Nothing stays stuck (NOTES.md, "Phase 3B review, round 1", fix 2).
- **[x] Decided in slice 10, spec vs tree (NOTES.md, slice 10, decision 1).** §4 lists only
  `OpenAntennaConfigPayload + radioTier`. The screen cannot grey a band without that band's tier, and
  a client on a dedicated server has no band table, so the payload also appends `bandCapacityTiers`
  (parallel to the band ids) and the screen applies the server's rule (`rf/RadioTier`) to the server's
  numbers. One protocol bump (7 → 8) covers both.
- **[x] Fixed in slice 10 (NOTES.md, slice 10, decision 2).** `AntennaBlockEntity.migrate` applied
  Phase 1's "PCI 0 means never assigned" to every save older than `DATA_VERSION`; with the bump to 3 it
  would have re-planned every deliberate PCI 0 in a Phase 2 world. It now applies to v1 saves only
  (`RadioTierGameTests` fails with the old condition).
- **[x] Decided in slice 10 (NOTES.md, slice 10, decisions 3 and 4).** A sector at tier 3 or above
  counts as holding one Wideband Radio Unit, fitted or grandfathered, and drops it on any removal of
  the block (a chest's contents: any tool, creative, explosions, regardless of `doTileDrops`); a command
  replacing it clears it first (`Clearable`) and drops nothing. A grandfathered Phase 2 band_3500 sector
  therefore yields one unit when broken, so moving it keeps band_3500.
- **[x] Noted in slice 10 (NOTES.md, slice 10, decision 5).** The Signal Mast's "band_900 only" is its
  missing screen: its tier 1 also covers band_700, which a crafted configuration packet could set, as
  before. band_1800 and band_3500 are refused there now.
- **[ ] Open, decision for the owner (from slice 10).** The tier is enforced when a band is chosen, as
  §3C.1 says. If a datapack raises a band's `capacityTier` above an antenna's tier, the antenna keeps
  the band on the air (nothing reads the tier at run time), but its screen shows the current band
  locked and Apply stays off until another band is picked. Option: let `applyOn` accept the band the
  antenna already has. No shipped data does this.
- **[ ] Open, decision for the owner (from slice 10, found while verifying).** The Sector Antenna's
  screen opens on use with any item in the main hand (vanilla calls `useWithoutItem` for the main hand
  whatever it holds; sneak + use places a block against it), not only with an empty hand as its old
  comment said. Comment fixed, behaviour kept (a Phase 2 world plays as before). The Wideband Radio Unit
  is the one item that skips the screen.
- **[x] Done in slice 16 (from slice 10).** *Recipe `rancraft:wideband_radio_unit`.* The Wideband
  Radio Unit is creative-only until its recipe
  exists (§3C.6: gold, amethyst, redstone block).
- **[x] Decided in slice 11, spec silent (NOTES.md, slice 11, decision 1).** §3C.2 says "horizontally"
  for the fiber radius and nothing for the site radius. Both are horizontal, so a dish on top of a tall
  mast column (up to `maxMastHeight` above its base) serves the column's own cell.
- **[x] Decided in slice 11, spec interpretation (NOTES.md, slice 11, decisions 2 and 3).** A cell's
  site relays between its dishes (the dish-to-cell edge is undirected), which is what lets "three sites
  chained by microwave" work. Thunder uses `max(rain_db_per_km, thunder_db_per_km)`, so a storm is never
  lighter than rain whatever a datapack sets.
- **[ ] Open, decision for the owner (from slice 11).** A site with no cell relays nothing: two dishes
  side by side with no antenna between them are not joined, so a pure repeater (a hilltop relay) carries
  no traffic. §3C.2 defines no such edge. Option: also join dishes within `siteRadiusBlocks` of each
  other (one more edge kind in `BackhaulGraph.solve`, pass 0). *(Slice 12: still open. The network
  passes every dish to `solve`, so the option stays a change in `BackhaulGraph` alone.)*
- **[x] Accepted in slice 11, labelled (NOTES.md, slice 11, decision 4).** The Fresnel check samples
  the zone along four offset paths, so one block on the line of sight in mid-path costs its
  penetration loss but not the 6 dB penalty. §3C.2's own sanity check needs this (one stone is
  DEGRADED at −69.55 dBm; with the penalty it would be DOWN). Side effect: mid-path, a leaf beside the
  beam costs 6 dB and a leaf on it 3 dB.
- **[x] Done in slice 12 (from slice 11): cost.** *Measured on the live level (NOTES.md, slice 12):
  0.14 µs per block of hop over loaded ground (five to six times the synthetic probe), a recompute
  marching three hops 192 µs, marching none 36 µs, the quiet per-tick check about 1 µs. Only new hops
  and hops whose bins moved are marched, a weather change uses `withWeather`, and the graph is solved
  only when an input changed. One new follow-up below (all re-marches of a recompute land in one
  tick).* A hop reads about five voxels per block of its
  length (the line and the four offset paths); 26 µs per 1000-block hop with a synthetic probe, more
  with the server's block lookups, so measure it in a game test. Re-march only hops whose
  `MicrowaveLink.dependencyBins` moved (region epochs), re-budget a weather change with `withWeather`
  (no march), and run `BackhaulGraph.solve` only when an input changed (0.35 ms at 200 cells, 400 dishes
  and 200 links; about 2 ms at 1000 cells, allocation-bound; move to primitive arrays if it ever shows).
  If the backhaul state flaps in rain, keep the flips hysteretic (the slice 8 note above).
- **[x] Done in slice 12 (from slice 11): evaluation details.** *Each hop is marched from the lower
  packed position; the march gets `MicrowaveLink.stepsToReach` (new: a cap it cannot run out of,
  pinned on 400 random hops and on the 1414-voxel diagonal), and the length is bounded by
  `maxEvaluationRangeBlocks` instead (the Link Tool refuses a longer pairing; a hop a lowered range
  leaves too long is DOWN, out of range, unmarched); `Biome.getPrecipitationAt` at the midpoint maps
  NONE / RAIN / SNOW to CLEAR / RAIN / SNOW, with `Level.isRaining()` / `isThundering()` (NOTES.md,
  slice 12, decisions 4 and 5).* Evaluate each pair of dishes in one
  fixed order (say the lower packed position as A): the voxel march breaks ties at voxel edges by
  direction, so the two ends could otherwise disagree. Choose the hop's march cap deliberately:
  `maxRaySteps` (default 1200 voxels, about `|dx| + |dy| + |dz|`) would put a 1000-block diagonal hop
  (about 1414 voxels) out of range. Map `Biome.getPrecipitationAt(midpoint)` NONE / RAIN / SNOW to
  `Weather.CLEAR` / `RAIN` / `SNOW` and pass `Level.isRaining()` / `isThundering()` to `Weather.at`.
- **[x] Done in slice 12 (from slice 11).** *Both wired: `requireBackhaul` gates the off-air effect
  and the FAIR cap, `backhaulRecomputeTicks` the recompute interval. (Phase 3C review: the flag no
  longer gates the FAIR cap, only NONE's effects.)* `requireBackhaul` and
  `backhaulRecomputeTicks` are in the config (COMMON) but nothing reads them yet; slice 12 wires them.
- **[x] Decided in slice 12, spec silent (NOTES.md, slice 12, decision 1).** With `requireBackhaul` off,
  backhaul has no effect at all: no cell is held off the air and a LIMITED cell caps nothing (§3C.2
  states the cap without the flag; the task and §2 say a default world must play as before, and a
  world with no Core Site would otherwise cap every device at FAIR). The states are still worked out,
  once a core or dish exists, for the lens, the dish and the status command, which say so.
  *Reversed in the Phase 3C review (row 16a, finding 1): the reason was wrong. With no Core Site
  every cell is NONE, not LIMITED, so a flag-independent LIMITED cap cannot touch such a world. The
  LIMITED cap now applies whatever the flag says, as §3C.2 states; NONE caps (and goes off the air)
  only with the flag on.*
- **[x] Decided in slice 12 (NOTES.md, slice 12, decisions 2 and 3).** The backhaul topology (cores,
  dishes, pairings, eligible cells) is saved per dimension, so a hop's far end, a relay or the core may
  be unloaded and still count; the graph's cells are those passing their own rules, not the
  registered ones (a cell off the air for want of backhaul must be found again). A cell the graph has
  not judged yet is on the air until the next recompute.
- **[x] Decided in slice 12, spec vs tree (NOTES.md, slice 12).** §3A.2's `DeviceContext` gains a
  seventh component, `serviceCap` (appended; the six-argument constructor kept as no cap), and
  `DeviceRequirement.check` a three-argument form; a cap adds no verdict (a capped device is
  `LOW_QUALITY`, `DeviceContext.backhaulLimited()` says why). `SignalSamplePayload` v4 carries the cap,
  not the text.
- **[x] Done in the Phase 3C review (row 16a, finding 2) (from slice 12).** *A recompute marches its
  queued hops under `backhaulMarchBudgetMs` (new, 0.25 ms per tick, shared by every dimension) through
  `util/BudgetedQueue`, and solves once all are measured, in a tick of its own. Measured with 64
  1000-block hops: worst tick 0.31-0.76 ms, against 3.3-4.6 ms in one tick.* A recompute runs all its re-marches in one tick. One block
  moving in a bin that many long hops cross invalidates them together: 50 hops of 1000 blocks over
  loaded ground would make that one tick about 7 ms (0.14 µs per block, measured), against an average
  of under 0.1 ms/tick. Not reachable at the done-when's scale. Option: a per-tick march budget (as
  `fixedReceiverTickBudgetMs` does for fixed receivers), carrying the rest to the next ticks and
  solving once they are all measured.
- **[ ] Open, minor (from slice 12).** `BackhaulNetwork.validate` scans every core, dish and known cell
  on each chunk load, to drop entries whose block was removed while unloaded. Cheap at hundreds;
  index the entries by chunk if a server has thousands of antennas.
- **[ ] Open, decision for the owner (from slice 12).** With `requireBackhaul` on, a newly placed cell
  with no backhaul transmits until the next recompute (at most `backhaulRecomputeTicks`, 5 s) before
  it goes off the air: a short-lived lit lobe. Option: when a cell is first noted, solve the graph for
  it at once without marching (marches stay bound by the interval). Not needed for the done-when.
  *(Phase 3C review: the delay can now also include the recompute's spread marches, a tick or two for
  a few hops and 16 to 34 ticks for 64 long ones. The option above would still remove it, solving
  with the last published hop budgets, not the half re-marched ones.)*
- **[x] Done in slice 13 (from slice 12).** *The cap needed no code of its own in the terminal, and
  "near a core" uses `BackhaulNetwork.cores()` with the graph's own fiber rule. Instead of
  `backhaulLimited()`, the terminal tells "backhaul limited" from "weak signal" by whether the radio
  alone meets GOOD (`TerminalLink.problemOf`). `backhaulLimited()` is true under any capped cell, even
  when the radio itself is short (NOTES.md, slice 13, decision 2).* The Storage Terminal's GOOD requirement is already refused
  under a LIMITED cell through the capped verdict (both tickers pass the cap); its HUD can say
  "backhaul limited" rather than "weak signal" with `DeviceContext.backhaulLimited()`. "Within
  `fiberRadiusBlocks` of a core site" can use `BackhaulNetwork.of(level).cores()` (every core of the
  dimension, loaded or not) with `RanCraftConfig.fiberRadiusBlocks()` (horizontal, as the graph's
  fiber rule).
- **[x] Decided in slice 15 (from slice 12).** Power can feed `eligibleToTransmit()` as the column
  rules do. Then an unpowered cell leaves the backhaul graph too (`noteCell(false)`), so its site stops
  relaying, which is what a dark site does; decide whether that is wanted before wiring it there rather
  than in `isTransmitting()` beside the backhaul verdict. *(Slice 15: beside the backhaul verdict, in
  `isTransmitting()` (NOTES.md, slice 15, decision 1). A cell out of energy stays in the backhaul graph,
  so its site keeps relaying between its dishes, and a power flap changes no backhaul topology. §3C.5
  puts only antennas on the power budget, so the dishes (the site's transport) are not dark when the
  radio is.)*
- **[x] Done in slice 16 (from slice 12).** *All three have recipes from those ingredients.* Core
  Site, Backhaul Dish and Link Tool are creative-only
  until their recipes exist (§3C.6: iron block, redstone block, chest; iron, copper, lightning rod;
  stick, copper).
- **[x] Done in slice 16 (from slice 13).** *Recipe `rancraft:storage_terminal`; `RecipeGameTests`
  covers items.* The Storage Terminal is creative-only until its recipe
  exists (§3C.6: ender pearl, copper, gold; late-mid). It is an item, so `HarvestGameTests` does not
  cover it. It needs only the recipe.
- **[x] Done in slice 14 (from slice 13).** *The scanner reuses the rule, extracted as
  `TerminalLink.reasonOf` (behaviour unchanged), but keeps no per-player record: it judges each
  dispatch as it comes, and its session is the HUD, so there is no `stillValid` to read a stored verdict
  (NOTES.md, slice 14, decision 3). Its game test uses `SilentServerPlayer`, which now also keeps the
  custom payloads sent to it, and `dispatchToCarried`.* The Proximity Scanner can follow the terminal's
  pattern:
  - keep the verdict per player in a `DeviceMemory`, as `TerminalLink` does;
  - tell "backhaul limited" from "weak signal" with `TerminalLink.problemOf`'s rule (the radio level
    against the requirement), or reuse it.

  `SilentServerPlayer` and `SignalTicker.dispatchToCarried` let a game test check it through the
  ticker's own scan and dispatch.
- **[x] Decided in slice 14, spec vs tree (NOTES.md, slice 14, decision 1).** §3C.4's "within 24
  blocks" is a new COMMON config value, `scannerRangeBlocks` (default 24, 1 to 64), though §5's list
  does not name it: the ground rules put everything tunable in `RanCraftConfig`. It stays out of
  `RfConfig` (gameplay and server cost, not RF).
- **[x] Decided in slice 14, interpretation (NOTES.md, slice 14, decision 2).** §3C.4 says the server
  sends `ScannerPayload` while the scanner is held with an OK verdict. A held scanner is also sent a
  payload on any other verdict, carrying only the reason and the values to word it (no list), because
  the HUD must tell "needs tier 3" from "signal too weak" from "backhaul limited" and the client may not
  work out a verdict. A scanner in the hotbar is sent nothing.
- **[x] Done in slice 16 (from slice 14), art still open.** *Recipe `rancraft:proximity_scanner`; the
  art is still the placeholder.* The Proximity Scanner is creative-only until its recipe
  exists (§3C.6: spyglass, amethyst, gold, sculk sensor; late). It is an item, so `HarvestGameTests`
  does not cover it. Its art is a placeholder (the echo shard texture).
- **[ ] Open, minor (from slice 14).** The scan sorts every hostile mob in the query's box each
  interval, before the cap of 16. One scan measured 23.7 µs with 20 mobs around. A mob farm with
  hundreds of hostiles within 24 blocks of a player holding a scanner would cost more; not measured.
  If it shows, keep the 16 nearest with a bounded heap instead of sorting all of them.
- **[ ] Open, option for the slice 4 follow-up (from slice 13).** `SilentServerPlayer` drops every
  packet in `send(Packet)`, before NeoForge's channel check runs. So it could stand on the real player
  list, and a game test could run the ticker's per-player loop itself, the part still only checked in
  game.
  - It would have to be added to `PlayerList.getPlayers()` directly and removed after.
    `placeNewPlayer` would send join packets and add it to the level.
  - While it is on the list, an autosave would write its player data.
  - Not done: the slice 13 tests call `dispatchToCarried`, which runs the ticker's scan, cap and
    dispatch but not the loop, the stagger or the cache.
- **[ ] Note (from slice 13).** The terminal keeps one record per player, not per terminal. Every
  terminal has the same requirement, so two carried terminals share it with no difference. If a later
  slice adds a terminal with another requirement (an upgraded one), key the record by requirement.
- **[x] Done in slice 16 (from slice 15), art still open.** *Recipe `rancraft:site_generator`; the art
  is still the placeholder.* The Site Generator is creative-only until its recipe
  exists (§3C.6: furnace, copper, redstone; mid). It already drops itself and is pickaxe-mineable
  (`HarvestGameTests`). Its art is a placeholder (the blast furnace's textures).
- **[ ] Owner decision (from slice 15).** Turning `requirePower` on in a running or existing world takes
  every cell off the air at the next tick, because every buffer starts empty with its latch off (a v3
  save, a new antenna, and a v4 one that never ran with the flag on). Each comes back once it is fed past
  10 %. That is §3C.5's "out of energy → off air", and turning the flag on is the server's choice, so it
  was kept (NOTES.md, slice 15, decision 10). Option: grant every loaded cell a full buffer the first
  time the flag turns on in a world (a per-world marker in a `SavedData`, as the slice 10 note above
  describes for another case).
- **[ ] Note for Phase 4 (from slice 15): the cell-sleep seam.** `SitePower.of(level).servedCount(cellId,
  now)` is the number of distinct receivers the cell served in the last `servedWindowMinutes` (5; 1 to
  120); `util/ServedReceivers` is the pure part. Three limits for a sleep rule built on it:
  - it is not saved, so after a restart a zero means nothing until a whole window has passed;
  - it counts what the server evaluates: a player carrying a device or wearing a lens, and fixed
    devices. A player carrying nothing is not evaluated, so not counted;
  - a fixed receiver reports when its serving cell changes or every 20 s
    (`SitePower.SERVED_NOTE_INTERVAL_TICKS`), so it can drop out up to 20 s early. The window's minimum
    of 1 minute keeps that harmless.
- **[x] Decided in slice 16, spec conflict (NOTES.md, slice 16, decision 1).** §3C.6 lists a comparator
  for the Radio Link Receiver and calls the pair early. A comparator needs Nether quartz, so a
  comparator-only receiver makes the pair post-Nether (a transmitter alone does nothing). The receiver's
  ingredient is "a comparator or a repeater" (a JSON array), which keeps the spec's comparator and the
  early tier. Option for the owner: comparator only (one line in `recipe/radio_link_receiver.json`).
- **[x] Decided in slice 16, beyond the spec (NOTES.md, slice 16, decision 2).** Every recipe has a
  recipe-book unlock advancement (`data/rancraft/advancement/recipes/`). §3C.6 does not ask for them, but
  without one the recipe book never shows a recipe, RANCraft has no guide, and with the
  `doLimitedCrafting` game rule on the recipe could not be crafted at all.
- **[x] Decided in slice 16 (NOTES.md, slice 16, decisions 3 to 5).** Raw materials use NeoForge's `c:`
  tags, so another mod's copper or iron works. The Signal Mast yields 4 per craft, because masts stack
  for height (about 6 iron for a ten-high tower). The shapes are mine; §3C.6 gives ingredients only.
- **[ ] Note (from slice 16).** Recipes are data, so retuning a cost needs no code: edit the file, and
  `RecipeGameTests` re-checks collisions and reachability. Of the costs, the Core Site (15 iron, 9
  redstone) is the steepest mid-tier item. It is one per network, and nothing needs it while
  `requireBackhaul` is off. Watch it in the playthrough.
- **[ ] Open, decision for the owner (from the Phase 3C review, row 16a, finding 1).** With
  `requireBackhaul` off, a cell with no path to a core (NONE) is uncapped, but a cell behind a DEGRADED
  hop is capped at FAIR. So with the flag off, a marginal link that drops from DEGRADED to DOWN (a
  thunderstorm, a second tree) *lifts* the cap from the cells behind it, and breaking the dish of a
  DEGRADED hop gives them full service. This follows §3C.2's wording and the review's fix, and it is
  labelled at `BackhaulGraph.serviceCap`: with the flag off a cell nobody connected has implicit
  transport. Options:
  - (a) keep it;
  - (b) with the flag off, cap a NONE cell at FAIR too when it has a dish on its site (the player built
    backhaul for it and it is down);
  - (c) cap NONE at FAIR whenever the dimension has a Core Site.
  Each of (b) and (c) still leaves a world with no core untouched.
- **[x] Decided in the Phase 3C review (row 16a), spec vs tree.** `backhaulMarchBudgetMs` (0.25 ms, COMMON,
  not in `RfConfig`) is a new config key that §5 does not list. It makes the march budget tunable like
  everything else, as `scannerRangeBlocks` did in slice 14.
- **[ ] Open, minor (from the Phase 3C review, row 16a).** The backhaul solve (the weather, the graph
  and the effects) is not budgeted. It runs once per recompute, in a tick of its own, and took 0.2 to
  0.47 ms in the game test, with 64 hops, 128 dishes and about 230 cells. Slice 11 measured the graph
  alone at about 2 ms for 1000 cells (allocation-bound). Options: primitive arrays in
  `BackhaulGraph.solve`, or skip the solve when no hop changed state and the topology did not move.
- **[ ] Note (from the Phase 3C review, row 16a).** Spreading the marches costs more CPU in total than
  marching in one tick: about 1.1 to 1.2× for all-new hops, and about 2.5× when about two hops are
  marched per tick, because each tick's first march starts with cold caches. That is the price of
  keeping every tick short. If it matters, a larger `backhaulMarchBudgetMs` trades tick length back for
  total cost.
- **[ ] Note (from row 16b).** The microwave link's rain figures (`rain_db_per_km` 2.5,
  `thunder_db_per_km` 6.0) are 18 GHz ones and do not follow `frequency_mhz`. §6's "rain fade rising with
  frequency" shows only as the contrast with the cellular bands, which have no weather term. A datapack
  that moves the link to another frequency must change both figures with it. Labelled at `MicrowaveLink`
  in row 16b. Option, if a datapack ever needs it: derive the specific attenuation from ITU-R P.838's
  k and α for the link's frequency and a rain rate per weather (a few more numbers in `microwave.json`).
- **[ ] Deferred docs (from row 16b, owner chose to skip what can wait).** Independent of all code and of
  each other; any can be written later in any order: (a) NOTES.md "Phase 3C summary" (slices 10-16,
  the review's 2 fixes, measurements: fuel ratio 6.625, worst backhaul march tick 0.68 ms, 200 radio
  links median 32.5 us/tick, 625 tests, 64/64 game tests); (b) ~~README's Part 3C section and how to turn on
  requireBackhaul / requirePower~~ **done 2026-10-05** (README "Turn on backhaul and power"); (c) ~~a consolidated "How to test Part 3C in game" checklist built from
  the slice check steps above~~ **done in row 16c** (end of this file).
- **[x] Done in row 16b.** Two in-game steps written before the Phase 3C review no longer matched the
  code: slice 12 step 5 (with the flag off, a cell behind step 4's DEGRADED hop now reads "BH: LIMITED
  (capped FAIR)") and slice 13 step 4 (the flag is no longer needed). Both are annotated where they
  stand; "How to test Part 3C in game" (row 16c) is written for the final code.

### Phase 3C review round 1: the two findings

The Part C review reported two findings, both minor. Each was confirmed against the code and fixed in
row 16a (3e5bdd1); none was rejected. They are recorded here in the reviewer's terms, with where each
was closed, so that a later round does not re-raise them without new evidence. Each fix has a test that
fails with the fix disabled. Details are in NOTES.md, "Phase 3C review, round 1".

- **[x] Finding 1 (minor), fixed.** "The LIMITED to FAIR cap is gated on requireBackhaul, which §3C.2
  does not ask for. The reason recorded for this (NOTES.md slice 12, decision 1) is wrong: in a world
  with no Core Site every cell is NONE, not LIMITED, so a flag-independent LIMITED cap would not change
  a Phase 2 world."
  - *`BackhaulGraph.serviceCap`: LIMITED → FAIR whatever the flag; NONE → NONE with the flag on, no cap
    with it off; FULL and unknown → no cap.*
  - *The status command and the docs follow. Slice 12's decision 1 is marked reversed.*
  - *The Storage Terminal's 3C done-when game test runs with the default config.*
  - *`BackhaulGraphTest` gains `noCoreNoCapWithRequireBackhaulOff`.*
  - *The non-monotonic side effect with the flag off is an owner decision above.*
- **[x] Finding 2 (minor), fixed.** "A backhaul recompute re-marches every hop whose bins moved in one
  server tick, with no per-tick budget. Chunk load and unload bumps along long hops, and the first
  recompute after a server start, can therefore produce multi-millisecond ticks, above the 1 ms/tick RF
  budget."
  - *A per-tick march budget, `backhaulMarchBudgetMs`, through the pure `util/BudgetedQueue`.*
  - *One solve once every hop is measured, in a tick of its own, with every state published at once.*
  - *A game test times 64 dirty 1000-block hops: worst tick 0.31 to 0.76 ms, against 3.3 to 4.6 ms in
    one tick.*

### Phase 3B review round 1: the four findings

The Part B review reported four findings, two major and two minor. Each was confirmed against the
code and the 1.21.1 / NeoForge 21.1.251 sources and fixed in row 9a (8b42665, 48e4da8); none was
rejected. They are recorded here in the reviewer's terms, with where each was closed, so that a later
round does not re-raise them without new evidence. Each fix has a game test that fails with the fix
disabled; details in NOTES.md, "Phase 3B review, round 1".

- **[x] Finding 1 (major), fixed.** "Region epochs never change when a chunk loads, unloads, or moves
  in or out of the in-memory ring outside FULL. The probe reads any chunk that is not FULL as air, so a
  fixed receiver can replay a sample indefinitely that a fresh evaluation would no longer produce. This
  gap is not recorded anywhere." *`RegionEpochs` bumps the chunk's bin on `ChunkEvent.Load` and on a
  `ChunkTicketLevelUpdatedEvent` that crosses FULL (33); no hook on unload, because a chunk leaves FULL
  (and is bumped) before it can unload. Chunk bumps stay out of `total()`, so coverage painting is
  unchanged. The gap is now recorded in the `RegionEpochs` javadoc and NOTES.md, slice 7.*
- **[x] Finding 2 (major), fixed.** "A Radio Link receiver applies delivered messages and transmitter
  departures with setBlock(UPDATE_ALL) even when its chunk is not FULL. The ticker skips such a
  receiver, but the network still delivers to it, and setBlock then forces the chunk back to FULL
  synchronously on the server thread and fires redstone updates in a chunk the ticker treats as
  unloaded." *Outside FULL the receiver changes only its memory and writes no block; its next turn,
  once the chunk is FULL again, saves and writes the output.*
- **[x] Finding 3 (minor), fixed.** "Slice 6 moved the PCI plan for a migrated Phase 1 mast from the
  deferred onLoad to ChunkEvent.Load. It now plans before antennas in chunks loaded later in the same
  batch have registered, which regresses the planning quality of the Phase 1 to Phase 2 migration."
  *Only a promotion (structure mast become base) plans in `refreshRegistration`; every other pending
  plan waits for `onLoad`, as before slice 6.*
- **[x] Finding 4 (minor), fixed.** "The RF Lens places a column's lobe using the client's own COMMON
  maxMastHeight, which NeoForge does not sync. On a dedicated server with a different cap, the client
  draws the lobe somewhere other than where the server radiates. This is already recorded as a
  follow-up but is still unresolved as 3B closes." *The update tag carries `RadiatingY`, the server's
  height, and the lens uses it; `PROTOCOL_VERSION` 6 → 7; the slice 6 follow-up is closed. The
  dedicated-server check stays an in-game item (row 9a checks, first item).*

### Gate review findings (slices 2-5), checked against the code in row 5b

The per-slice gate reviews left these unfixed at the time. Each was re-read against the tree.

- **[ ] Open, minor, decision for the owner (from the slices 2-3 gate; measured in row 5b).**
  **Nearly collinear towers give a confident FIX on the wrong side of the line.** Only an exact line
  is POOR GEOMETRY (singular from the centroid, which lies on the line). The HDOP gate is read at the
  estimate, and seen from off the line the lines of sight fan out, so HDOP looks good while the
  mirror image fits the coarse ranges about as well; quantisation decides which side wins.
  Measured (scratch program on the current `rf`, flat ground, band_900, three single masts at
  x = -150, 0, +150 with the middle one pushed off the line, receiver 20-120 blocks from the line,
  4,000 seeded scenes per row; re-run in row 5b part 2 on the tree as committed, identical):

  | Middle mast off the line | FIX | on the wrong side | mean error of those | their mean ± reported | their mean HDOP |
  |---|---|---|---|---|---|
  | 0 (exact line) | 0 (all POOR GEOMETRY) | – | – | – | – |
  | 2 blocks | 3949 | **46 %** | 142 blocks | 11.4 | 1.31 |
  | 5 blocks | 3881 | 40 % | 141 blocks | 11.7 | 1.36 |
  | 10 blocks | 3909 | **32 %** | 139 blocks | 11.6 | 1.34 |
  | 20 blocks | 3854 | 18 % | 120 blocks | 11.8 | 1.36 |
  | 40 blocks | 4000 | 2 % | 84 blocks | 12.3 | 1.42 |

  With exact ranges it never happens (0 % at every offset), so it is measurement ambiguity, not a
  convergence bug. Labelled in the `LocatorSolver` javadoc (honest limits) and NOTES.md. Candidate
  fix (a solver change and a spec question, so not done in a docs step): after the fit, also fit from
  the estimate mirrored across the sites' best-fit line, and if that basin's weighted residual is
  within a threshold of the best one (for example `Δcost < 1`, one σ² of evidence), report the two as
  AMBIGUOUS (§3A.5 defines AMBIGUOUS only for two cells) or as POOR GEOMETRY. Test: the table above
  with the wrong-side rate at 0 and the FIX rate reported. How far off the wrong side lands depends
  on where you stand: roughly the mirror image across the line, so twice your distance from it (a
  single scratch scene 50 blocks from the line, middle mast 10 blocks toward the receiver: FIX 70
  blocks off, ± 10.5, HDOP 1.21).
- **[x] Resolved in slice 5 (from the slice 4 gate).** The slice 4 follow-up suggested guarding the
  emergency record stamp with a `ReplayGuard`; followed as written, a player standing still would
  have had no stamp newer than their last fresh evaluation, and the record would age out although the
  Locator showed FIX all along. Slice 5 did not use a `ReplayGuard`: a replay reuses the stored fix and
  moves its `confirmedTick`, and `NetworkLocator.stampFix` stamps with that tick (NOTES.md slice 5,
  decisions 1 and 6). Checked in the code in row 5b.
- **[x] Done in row 5b (0482a74) (from the slice 4 gate).** Two abstractions NOTES.md called
  "labelled also at the code sites" were not: dropping a stale candidate has no 3GPP counterpart
  (now in `CellSelector.expireStaleCandidate`), and a device measures only while carried (now in
  `SignalTicker.carried`). Row 5b found two more of the same kind and labelled them in the docs
  commit: the slant σ used for horizontal ranges (`LocatorSolver.weightedErrorOf`) and the
  near-collinear wrong-side FIX (`LocatorSolver` class javadoc).
- **[x] Done in row 5a (a755f7f) (from the slice 5 gate).** "A dead player's Locator does nothing" was
  `[x]` with no test: now `LocatorGameTests.dead_player_locator_is_inert`, which fails with the
  `isAlive()` guard removed.
- **[x] Done in row 5a (2f1306f) (from the slice 5 gate).** A Locator re-selected after sitting in the
  hotbar drew a fix up to 5 s old as current: the client now drops the reading every tick no Locator
  is held (`ClientLocatorState.putAway`).
- **[x] Done in row 5a (2f1306f) (from the slice 5 gate).** A stale or missing payload converted the
  waypoint's saved ± at a hardcoded 1.0 m per block: now the last payload's `metersPerBlock`, or no ±.
- **[x] Done in row 5b (0482a74) (from the slice 5 gate).** The recorded worst case of 105 ground
  lookups per fix (7 × 15) undercounted: each Gauss-Newton run also reads the ground at its end point,
  and the extra starts read it once under the centroid, so 7 × 16 + 1 = **113**, the bound
  `LocatorTrackerTest.costPerFix` already asserted. `LocatorGameTests.LOOKUPS_PER_WORST_FIX` is now
  `7 * (MAX_ITERATIONS + 1) + 1`; the docs above and NOTES.md are corrected (8.8 µs at 77.9 ns).

### Phase 3A review round 1: the two rejected findings

The review reported 11 findings; its verifiers confirmed 9 (all fixed in row 5a) and rejected 2 as
not defects in the current tree. Neither is a follow-up; both are recorded so a later round does not
re-raise them without new evidence.

- **[x] Rejected (net-protocol lens).** `SignalSamplePayload`'s v3 decoder does not reject a wrong
  version, a bad cell count, a negative `cellsHeard` or a non-finite receiver position, although
  `LocatorFixPayload` in the same package rejects all of these. The verifier found the facts right
  but nothing in the tree that can make it fail (the list's initial capacity is capped at
  `MAX_CELLS`, and only the server writes this server-to-client payload): a hardening and consistency suggestion, not a defect. A later slice that
  changes this payload's format anyway can add the same checks at no extra cost.
- **[x] Rejected (rf-math lens).** The drive-test CSV writes the band id with RFC 4180 quoting only
  (`DriveTestLog.csvRow`), with no step that neutralises a leading formula character for
  spreadsheets. The verifier found no way to exploit it: on an unmodified server the field is always
  a loaded band id, and a hostile server gains nothing it could not already do. Speculative
  hardening, not a defect.

### From the session's pending list (written while a workflow agent held this file)

All three were copied into the list above when they were found and are fixed; re-checked against
the code in row 5b:

- **[x]** Stale armed handover candidate across an evaluation gap: slice 4 part 1 (a51e13a),
  `ReceiverStateStore.resume` in `SignalTicker.evaluate`.
- **[x]** Drive-test log bleeding across worlds: slice 0a (0afda73), `ClientEvents.onLoggingOut` calls
  `ClientDriveTest.clear()`.
- **[x]** Export link opening the CSV in Excel: slice 0a (0afda73), the `OPEN_FILE` target is the
  `drivetests` folder.

---

## How to test Part 3A in game

For the project owner, in one sitting of about an hour (steps 1-9 are the core; 10 and 11 need a
config edit or a portal). Everything that can run headless has passed (382 unit tests, 5 game
tests); this is what only a person at the client can see. The sections above carry the detail for
each check; this list is the route through all of them. The numbers quoted for steps 2-5 were
checked against the current solver with a scratch program on flat ground (NOTES.md, "Phase 3A
summary"); in a world they vary by a block or two. Directions: in Minecraft +x is east and +z is
**south**; F3 shows your true block position (compare it with the Locator's "Est").

**Setup.** From `C:\Users\k_ale\Downloads\rancraft` run `.\gradlew.bat runClient` (no other Gradle
running). Create a **Creative, Superflat** world, so nothing stands between you and the masts unless
you build it. From the **RANCraft** creative tab take: Field Test Meter, RF Lens, Network Locator,
Signal Masts, Sector Antennas. Run `/gamerule keepInventory true` (for step 8). Leave the config at
its defaults (`metersPerBlock` 1.0) until step 10.

**1. Meter, lens and handover counter, unchanged by the ticker refactor (6 min).**
- [ ] Place two Signal Masts 200 blocks apart. Hold the meter near the first: bars and RSRP;
      right-click switches compact (top-right) and detailed (top-left, with "HO:"). Move it to the
      offhand: the same HUD.
- [ ] Put the meter in a hotbar slot you have not selected: no HUD. Select it: a current reading at
      once.
- [ ] Walk from the first mast toward the second: past the midpoint "HO:" goes up by one, about 2 s
      after the second mast becomes clearly stronger. Walk back and stop about 15 blocks past the
      midpoint, before "HO:" changes, and stand still: it still goes up about 2 s later (the sample
      cache must not freeze an armed handover).
- [ ] Wear the RF Lens (helmet slot; right-click equips it). A lobe over each mast. Press V to cycle
      All layers → Antennas → Links → Coverage → Drive-test trail; on Links, a ray from each mast
      you hear, the serving one highlighted. With the lens on and the meter out of your hands: no
      meter HUD.
- [ ] Place a Sector Antenna 30 blocks away, stand still, right-click it with an empty hand, change
      its Downtilt and Apply: its ray and lobe change without you moving.
- [ ] Break the sector and walk far from both masts (or break them): the meter says NO SERVICE.

**2. The Locator, one mast at a time (8 min).** Break all masts. Stand at a spot you will use for
steps 2-5 and note F3's x and z.
- [ ] Hold the Network Locator in the main hand: "Network Locator ... NO SIGNAL", "No cell at or
      above -100 dBm to range to". Its tooltip has three lines and says "not GPS". In a hotbar slot
      you have not selected: no Locator HUD.
- [ ] Place **one** mast about 100 blocks east (+x): RANGE ONLY, "On a ring .. m from the cell at
      x .. z .. (one cell heard)", and one ring on the ground round the mast passing within about
      15 blocks of your feet (half of band_900's 30-block ranging step; about 10 in the scratch run).
- [ ] Place a **second** mast about 100 blocks south (+z): AMBIGUOUS, two yellow vertical markers,
      one near you (about 15 blocks off) and one mirrored across the line between the two masts,
      and "No last estimate to choose between them" (both markers alike).
- [ ] Place a **third** mast about 100 blocks north-west (-x, -z), so the three surround you: FIX, a
      green marker with an error circle near your feet, "±" about 10 m (10.5), HDOP about 1.2,
      "Cells 3   best res 30 m (band_900)"; the three rings cross at the green marker. "Est" vs F3:
      within the ± about half the time and within twice it almost always (scratch sweep over 441
      standing spots: 44 % and 100 %), and the same error while you stand still (the rounding of
      the ranges is deterministic, not noise).
- [ ] Break the third mast: AMBIGUOUS again, and now the marker nearer your last FIX is brighter
      ("Likely A: nearer the last estimate" or "Likely B ...").
- [ ] Still with two masts: move the Locator out of the hotbar into the main inventory for 5 s, then
      back into the hand: "No last estimate to choose between them", both markers alike (a last
      estimate older than two updates is not used). Put the third mast back.
- [ ] Switch the meter to detailed (right-click it in the main hand), then move it to the offhand
      and hold the Locator: the Locator HUD sits under the meter's readout, no overlap; with the
      meter compact the Locator starts at the top-left corner. The meter still updates once a
      second, and `/tick query` shows the same ms per tick with and without the Locator in hand.

**3. Towers in a line (3 min).** Break the three masts.
- [ ] Place three masts on **exactly** one east-west line (the same z for all three), at your x - 150,
      your x and your x + 150, and stand 50 blocks south of the middle one: POOR GEOMETRY (HDOP
      99.9+), "No position: the cells are too close to a line (limit HDOP 6.0)".
- [ ] Known limit, logged as an open follow-up (not a fault in your test): move the middle mast 10
      blocks off the line, once toward you and once away, and try a few standing spots. You get a
      FIX with a normal-looking ± (about 10-12 m), and at some spots (about a third in a measured
      sweep) the marker is on the **wrong side** of the line, up to about twice your distance from
      the line away from you (one scratch scene: 70 blocks off, ± 10.5).

**4. band_3500 shrinks the ± (5 min).** Break the line; rebuild the step 2 triangle (FIX, about ± 10 m).
- [ ] Place a Sector Antenna about 50 blocks north (-z) of your test spot. Right-click it with an
      empty hand: Band band_3500, Azimuth 180 (pointing south, at you; 0 = north, 90 = east), Apply.
      Back at the spot: "Cells 4   best res 3.0 m (band_3500)" and the ± down from about 10.5 m to
      about 7 m (about 1.5x inside a good triangle, accepted by the owner; the same sector on
      band_900 gives about 9).
- [ ] The big effect, at the edge of a network: break the triangle **and the sector**, and place
      three masts 150 blocks west in a narrow wedge, at (your x - 148, your z - 26), (your x - 150,
      your z), (your x - 148, your z + 26): FIX with a large ± (about 35 m, HDOP about 4.1). Place a
      Sector Antenna 60 blocks south (+z) of you, set to band_3500 and Azimuth 0 (pointing north, at
      you): the ± drops to about 5 m (about 7x; the same sector on band_900 gives about 10 m).

**5. Behind a hill (3 min).** Break the wedge and the sector; rebuild the step 2 triangle (FIX).
- [ ] Note "Est". Build a stone wall **one block thick**, 3 high and 10 wide, right in front of you,
      between you and the east mast. That mast's range now reads 3 blocks long (0.25 blocks per dB x
      12 dB of stone), and "Est" moves about 2 blocks away from it (the other two rings take part of
      the error); two blocks thick: about 4. The ± does **not** grow: it is quantisation only, by
      design. At the default bias the effect is small; the "Est" line is where to read it. A wall
      thick enough to push that mast below -100 dBm turns the fix AMBIGUOUS.

**6. Sites, not cells (review round 1; 8 min).** Break the masts and the wall.
- [ ] Build a three-sector site: one Signal Mast with a Sector Antenna on its east, south and west
      side, each placed while you stand on its outer side so it faces away from the mast. Stand 100
      blocks east, where you hear two or three of its cells and nothing else: RANGE ONLY, one ring,
      "Cells 1" (before review round 1: AMBIGUOUS). The line still says "one cell heard": it counts
      sites (see the spec-conflict follow-up).
- [ ] Build a second such site about 200 blocks from the first and stand between them, off to one
      side: AMBIGUOUS, the two candidates mirrored across the line through the sites, one on your
      side (before review round 1: a green FIX on the far side with a small ±).
- [ ] Build a third such site so the three surround you: FIX with "Cells 3" and a ± like three
      single masts (about 10 m), not smaller.

**7. Waypoints (4 min).** With a FIX (the step 6 sites, or the step 2 triangle).
- [ ] Sneak + right-click: chat "Waypoint 1/1 saved: x .. z .., ±.. m (the estimate, not where you
      stand)"; HUD "WP 1/1 (saved ±.. m)   0 m   bearing ...". Walk 50 blocks: distance and bearing
      follow the estimate. Save a second one; right-click cycles between them.
- [ ] Where you get no FIX (walk next to a single mast, or break masts until AMBIGUOUS): sneak +
      right-click is refused and names the fix type.
- [ ] Select another hotbar item, walk 20 blocks, select the Locator again: "No reading from the
      network yet" and no rings for up to a second, never the old FIX at the old place. The same
      with the HUD hidden (F1): no old rings drawn after switching back.
- [ ] Quit to the title screen and reopen the world: the waypoints are still on the Locator.

**8. Emergency record (3 min).** keepInventory is on (setup), so the Locator stays with you.
- [ ] With a FIX on the HUD, note "Est" and F3. `/kill`. After respawn, hold the Locator: "Last fix
      before death: x .. z .. y ~.. ±.. m, N s before (minecraft:overworld)" at the **estimate**, not
      F3's death spot. Quit and reopen the world: still there.
- [ ] Break masts until there is no FIX, wait more than a minute, `/kill` again: the line is gone.

**9. Drive-test trail (Vision 3a; 8 min).** Two masts 200 blocks apart, as in step 1.
- [ ] Lens on Drive-test trail (V), walk from one mast to the other: coloured markers at eye height
      where you walked, in the meter's colours, and a white pillar where "HO:" went up. Standing
      still for a minute adds none. Walk out of range of both masts (or break one and walk away from
      the other) and back into service: a yellow pillar where service came back.
- [ ] Meter out of the hotbar (main inventory), lens on Links: walk across the boundary and back
      (two handovers), then cycle to the trail: a white pillar at each handover point and **none**
      where you switched layers.
- [ ] Lens on Antennas, meter only in the hotbar (not selected): walk across the boundary, then
      cycle the lens to the trail: the HANDOVER pillar stands where the handover fired, not where you
      switched.
- [ ] `/rancraftc drivetest export`, then click the file name in chat: Explorer opens
      `run/client/rancraft/drivetests/` and Excel does **not** start. In Excel, Data > From
      Text/CSV (comma delimiter, "." decimal): the numbers read correctly on es-PE, and `HANDOVER`
      appears only on the handover rows.
- [ ] `/rancraftc drivetest clear` reports the count and empties the trail.
- [ ] Walk a little, quit to the title screen, open another world: no markers from the first, and an
      export there holds only that world's rows (or reports nothing recorded yet).

**10. Config-dependent checks (8 min).** Edit `run/client/config/rancraft-common.toml` with the game
closed, one change at a time, and restore the value afterwards.
- [ ] `evaluationIntervalTicks = 200`: hold the Locator with a FIX; once two updates have arrived
      the HUD and rings stay on between updates (no NO SIGNAL blink every 5 s).
- [ ] `metersPerBlock = 2.0`: save a waypoint under a FIX; the chat's "±N m" and the HUD's "saved
      ±N m" agree, also in the first second after taking the Locator back into the hand (then the
      ± is either the same number or left out, never half of it).
- [ ] `evaluationIntervalTicks = 2` and `enableSampleCaching = false`: lens on the trail, sneak in a
      straight line: a marker every 0.5-1 block, and one CSV row per step (before review round 1:
      one marker sliding along with you).
- [ ] `timeToTriggerTicks = 200`: the stale-candidate check in the slice 4 section above (the
      handover fires one full time-to-trigger after the meter comes back, not on the first reading).

**11. Nether and survival (optional, 8 min).**
- [ ] Build a Nether portal and go through with the lens on the trail and the Locator in hand: the
      Overworld trail and rings are not drawn in the Nether. Place three masts round you there: FIX
      works; its "y ~" is a guess (under the Nether roof no ground height is trusted, NOTES.md slice
      5). Come back: the Overworld trail is still there, and an export writes one file per
      dimension.
- [ ] Take an iron pickaxe, switch to Survival (`/gamemode survival`): a Signal Mast and a Sector
      Antenna each break in under a second, with the crack animation, and drop as an item.

If a step fails, note its number and what the HUD said; the matching detailed check in the sections
above says what the code is meant to do there.

---

## How to test Part 3B in game

For the project owner, in one sitting of about 45 minutes (steps 1-7; step 8 is optional and needs a
dedicated server). Everything that can run headless has passed (485 unit tests, 30 game tests in
each of two runs in row 9b); this is what only a person at the client can see. The sections above
(slices 6, 7 and 9, and the row 9a review) carry the detail for each check; this list is the route
through them. Directions: +x is east, +z is **south**; F3 shows your block position.

**Setup.** From `C:\Users\k_ale\Downloads\rancraft` run `.\gradlew.bat runClient` (no other Gradle
running). Create a **Creative, Superflat** world. From the **RANCraft** creative tab take: Field Test
Meter, RF Lens, Signal Masts, a Sector Antenna, a Radio Link Transmitter and a Radio Link Receiver;
from vanilla: a lever, a redstone lamp, redstone dust, a Block of Redstone, stone, TNT and flint and
steel, a piston. Hold the meter and right-click it once for the **detailed** readout. Leave the config
at its defaults until step 2's last check.

**1. One column, one cell (6 min).** Open ground, no other Signal Mast within 500 blocks.
- [ ] Stack nine Signal Masts. Lens on (helmet slot), layer Antennas or All (V cycles): **one** lobe,
      just above the top mast (before Phase 3B: nine overlapping lobes). Stand 10 blocks away: the meter
      reads "Serving: PCI n @ x, y, z" with y one above the top mast, and "(0 co-channel)" (before:
      about 8).
- [ ] Add a tenth mast on top: the lobe moves up one block at once, the meter's y goes up by one and its
      PCI stays the same; `run/client/logs/latest.log` has no new "PCI plan" line.
- [ ] Put a Sector Antenna on the top mast: the column's lobe goes and the sector's appears; the meter
      serves from the sector. Break the sector: the column's lobe is back at the top.
- [ ] Break the lowest mast: the lobe stays where it was; the log shows a new "PCI plan" for the mast
      above it (a fresh plan, known behaviour).

**2. Tall columns, saved worlds, redstone (8 min).**
- [ ] `/fill ~2 ~ ~ ~2 ~63 ~ rancraft:signal_mast` (a 64-mast pillar): one lobe above the 64th mast, no
      frame-rate drop. From the same spot, `/fill ~2 ~64 ~ ~2 ~67 ~ rancraft:signal_mast` (four more on
      top): the lobe stays above the 64th (the cap). `/setblock ~2 ~68 ~ rancraft:sector_antenna`: the
      column's lobe goes (a sector on the very top still silences it).
- [ ] Save and quit, reopen: about 5 s after the world appears the log has "RANCraft mast columns (Phase
      3, loaded this run): N stacked masts now form M columns; N-M masts stopped transmitting.", with a
      second sentence for the mounting pole you just made.
- [ ] Quit, set `requireRedstone = true` in `run/client/config/rancraft-common.toml`, reopen: the
      step 1 column's lobe is **grey** and the meter says NO SERVICE near it. Place a Block of Redstone
      touching any one mast (a middle one will do): the lobe turns band-coloured and the meter reads the
      column; break it: grey again. Quit and restore `requireRedstone = false`.

**3. What invalidates a reading (6 min).** Stand still about 40 blocks from a Signal Mast, meter held,
lens on Links (the line shows the link).
- [ ] Place a row of stone across the line: within a second the meter's RSRP drops and the line reddens
      at the stone. Break it: back within a second.
- [ ] Light TNT on the line, away from the mast: the reading changes within a second of the explosion
      (before Phase 3B it could stay until you moved).
- [ ] A lever-powered piston pushing a stone into the line: within about a second the reading shows the
      stone and keeps showing it; retract: clear again.

**4. Radio Link basics (8 min).** `/tp ~2000 ~ ~`, so the masts of steps 1-3 are out of range (cells
are heard up to 1,400 blocks away), and place one Signal Mast.
- [ ] Place a Radio Link Transmitter and, a few blocks away (not touching it), a Radio Link Receiver:
      both lamp tops light within about a second.
- [ ] Right-click each: the action bar reads "Radio Link Transmitter: address 1" (then 2, ...). Sneak +
      right-click with an empty hand counts down; 0 wraps to 15. Put both on the same address.
- [ ] Lever on the transmitter, redstone lamp beside the receiver: flip the lever and the lamp follows
      within about a second, both ways. Set the receiver to another address: it no longer reacts.
      Set it back.
- [ ] Redstone dust run up to the transmitter bends into it and powers it.
- [ ] Either item's tooltip says updates arrive about once a second, by design, and both ends need POOR
      service or better.
- [ ] With the lever on, break the transmitter (the lever drops with it): the redstone lamp goes off
      at once. Place a new transmitter, set the same address, put the lever back and switch it on: the
      redstone lamp comes back on.
- [ ] Break the mast: both lamp tops go dark within about a second, and the redstone lamp stays on
      (the receiver holds its last output). Place the mast again: the lamp tops light.

**5. POOR drops, FAIR is solid (done-when; 6 min).** In the same area as step 4, place two Signal
Masts 200 blocks apart (step 4's mast is broken, so nothing else is in range).
- [ ] Walk from the midpoint toward one mast until the meter reads SINR between 0.5 and 2 dB (POOR).
      Put the transmitter there and the receiver at a similar spot a few blocks away (both POOR).
      Flip the lever every few seconds: the lamp lags some flips by a second or more, now and then by
      several (at about 1 dB each end, about half the messages arrive).
- [ ] Move both next to one mast, where the meter reads FAIR or GOOD (SINR 5 dB or more): every flip
      arrives within about a second.

**6. Unload and reload (done-when; 4 min).**
- [ ] Lever on, receiver lamp on. Fly at least 600 blocks away and wait 20 s, then come back: the
      receiver's lamp is still on, and it still follows the lever.

**7. Survival (2 min).**
- [ ] `/gamemode survival`, iron pickaxe: a Radio Link Transmitter and a Radio Link Receiver each break
      quickly, with the crack animation, and drop as an item. `/gamemode creative` afterwards.

**8. Optional: the review fixes that only show in special setups.** The row 9a section above has the
exact steps.
- [ ] Finding 4, dedicated server: `runServer` with `maxMastHeight = 4` in the server's
      `rancraft-common.toml`, a `runClient` keeping 64, a column of eight masts: the lobe sits above
      the **fourth** mast, where the meter says the cell is.
- [ ] Finding 1, best effort: the walled link and the save-and-reopen check (row 9a, third item).
- [ ] Finding 2: the forceloaded transmitter on a clock (row 9a, fourth item): no tick hitch, and
      the lamp correct within a second when you come back.

If a step fails, note its number and what the HUD said; the matching detailed check in the sections
above says what the code is meant to do there.

## How to test Part 3C in game

For the project owner. Steps 1-8 are one creative sitting of about 75 minutes; step 9 is a survival
playthrough (an evening); step 10 is optional and needs a world saved before Phase 3C. Everything that
can run headless has passed (625 unit tests, 64 game tests); this is what only a person at the client
can see. The sections above (slices 10-16 and the row 16a review) carry the detail for each check;
this list is the route through them, written for the final code. Directions: +x is east, +z is
**south**; F3 shows your block position.

**Setup.** From `C:\Users\k_ale\Downloads\rancraft` run `.\gradlew.bat runClient` (no other Gradle
running). Create a **Creative, Superflat** world (plains: it rains there, which step 5 needs). From the
**RANCraft** creative tab take: Field Test Meter, RF Lens, Signal Masts, Sector Antennas, Wideband Radio
Units, Core Sites, Backhaul Dishes, a Link Tool, a Storage Terminal, a Proximity Scanner, Site
Generators, a Radio Link Transmitter and Receiver; from vanilla: chests, a furnace, stone, dirt, an oak
sapling, bone meal, oak leaves, a lever, a redstone lamp, coal, a hopper, a lava bucket, spawn eggs (a
zombie, a skeleton, a creeper, a cow). Right-click the meter once for the **detailed** readout. Both
logistics flags stay **off** (the default) until step 6. To change one: close the game, edit
`run\client\config\rancraft-common.toml`, start again (README, "Turn on backhaul and power"; NeoForge
also reloads the file while the game runs, but closing first always works). Start the game once on this
build before editing, so the file has the new lines.

**1. Radio tiers (5 min; slice 10).**
- [ ] Place a Sector Antenna and right-click it with an empty hand: "Radio tier: 2" under Apply. Cycle
      the band: band_3500 reads "band_3500 (locked)" in grey, hovering it says "needs Wideband Radio
      Unit", Apply is greyed with the same reason under it. band_700, band_900 and band_1800 are white
      and Apply works.
- [ ] Right-click the sector with a Wideband Radio Unit: the action bar says it was fitted, a smithing
      sound plays, and the screen does not open. Open the screen: "Radio tier: 3", band_3500 white.
      Apply it, aimed at where you will stand: the meter near the sector shows band_3500.
- [ ] Right-click it again with a unit: "already has a Wideband Radio Unit", the unit stays in hand. A
      unit used on a Signal Mast does nothing.

**2. Proximity Scanner (6 min; slice 14).** Step 1's band_3500 sector, aimed at you.
- [ ] Stand 10-30 blocks out on its beam, where the meter reads GOOD or EXCELLENT, and hold a Proximity
      Scanner. Top right: "Proximity Scanner", "0 within 24", "via band_3500 (tier 3)" and "No hostiles
      within 24 blocks".
- [ ] Spawn a few hostile mobs, one behind a wall. Within about a second they are listed nearest first:
      an arrow, the name, whole blocks and a compass point with degrees ("NE 047°"). The one behind the
      wall is listed too (the scanner is not radar). Turn round: the arrows turn, the list does not
      change. A cow is never listed.
- [ ] Meter in the other hand, compact mode: the list starts under the meter's top-right readout and
      never overlaps it. Meter detailed (top-left): the list moves up to the corner.
- [ ] Set the sector to band_1800 (still GOOD or better): "OFF", "needs tier 3, you're on band_1800
      (tier 2)" and "change the band: the feed needs a high-capacity one", no list. Back on band_3500,
      walk out along the beam until the meter drops below GOOD: "signal too weak: FAIR, needs GOOD".
- [ ] Move the scanner to the hotbar: its HUD goes at once. Take it back: "NO DATA" for up to a second,
      then the list. Afterwards `/kill @e[type=!player]`.

**3. Backhaul blocks and the Link Tool (4 min; slice 12 step 1).**
- [ ] Place a Core Site and right-click it: the action bar gives the fiber radius (24).
- [ ] Two Backhaul Dishes 40 blocks apart, each on a 10-block pillar. Right-click one with an empty hand:
      "not paired". Use the Link Tool on one (it glints, the tooltip names the dish), then on the other:
      "paired ... (40 m)". Within 5 s, right-click a dish: "UP, RSL ... dBm, margin +... dB, Fresnel
      clear, rain 0.0 dB". Sneak + use the tool on a dish: "unpaired".

**4. Storage Terminal (8 min; slice 13 steps 1-3 and 5).** A Sector Antenna set to band_1800, aimed at
where you stand, and step 3's Core Site.
- [ ] A chest 5 blocks from the core and a second 30 blocks away. Sneak + use the Storage Terminal on the
      far chest: "no Core Site within 24 blocks of this Chest". On the near chest: "bound to the Chest
      at x, y, z (Core Site 5 blocks away)", and the tooltip shows "Bound to …". On a furnace: "only a
      chest or a barrel can be bound".
- [ ] Where the meter reads GOOD or EXCELLENT on band_1800, carry the terminal for a second and use it in
      the air: the ordinary chest screen opens, titled "Storage Terminal: Chest", with the chest's items.
      Move items in and out, then open the chest by hand: they are there. If the chest is in view, its
      lid stays shut.
- [ ] Set the sector to band_900 and use the terminal: "needs band tier 2, you're on band_900 (tier 1)".
      Set it back to band_1800.
- [ ] With the screen open, move the terminal out of the hotbar: within about 2 s the screen closes and
      the action bar reads "connection lost (no signal reading …)".
- [ ] Walk out along the beam until the meter reads FAIR (about 200 blocks in open air) and use it:
      "signal too weak: FAIR, needs GOOD".
- [ ] `/tp ~400 ~ ~` (the chest's chunk unloads), place a Sector Antenna on band_1800 aimed at you and
      use the terminal at GOOD: "storage unreachable: its chunk is not loaded (x, y, z)". In the Nether:
      "storage unreachable: it is in minecraft:overworld …".

**5. A degraded hop, flags off (15 min; row 16a step 1, slice 12 steps 3-4, slice 13 step 4).** A
DEGRADED hop caps the cells behind it at FAIR even with `requireBackhaul` off. One stone (36 dB) makes a
hop DEGRADED only when the hop is long: at 1000 blocks it reads −69.55 dBm, just above DOWN (−70), so do
this step in **clear weather** (`/weather clear`).
- [ ] Build it: back in the Overworld, `/tp ~1000 ~ ~` from step 4's area so nothing else is near. Core Site A with a dish beside
      it on a 10-block pillar. `/tp ~1000 ~ ~` again: a Signal Mast with a Sector Antenna on top, on
      band_1800, and a dish within 8 blocks of the mast on a 10-block pillar. Pair the two dishes with the
      Link Tool (the far one's chunk may unload; it still counts). Core Site B about 40 blocks from the
      sector, on the far side from its dish (more than 24 from both, so neither is on fiber) with a chest beside it; bind the
      terminal to that chest. A Radio Link pair beside the sector on one address, a lever on the
      transmitter, a redstone lamp by the receiver.
- [ ] RF Lens on (lobes layer): the hop is a green line, "UP · ... dBm (+... dB)". Near the sector, at GOOD
      or better, the terminal opens.
- [ ] `/tp` to the hop's midpoint (500 blocks from either end) and put **one** stone block on the line.
      Within 5 s: the line is orange (DEGRADED, about −69.5 dBm); the dish reads DEGRADED; back at the
      sector, the meter's detailed readout shows "BH: LIMITED (capped FAIR)" while its bars stay the
      radio's own; `/rancraft backhaul status` lists the sector under "LIMITED, devices capped at FAIR".
- [ ] With the terminal open when the stone goes in (or used afterwards): the screen closes with
      "connection lost (backhaul limited: the serving cell is capped at FAIR, needs GOOD)", and a use is
      refused with the same words. The Radio Link still follows its lever.
- [ ] Storm: with the stone still there, `/weather thunder`. Within 5 s the line is red (DOWN) and the dish
      reads "rain 6.0 dB". `/weather clear`: orange again. (A DOWN hop leaves the sector with no backhaul;
      with the flag off that means on the air and uncapped, so the meter's "BH:" line goes while it is
      DOWN. That is correct.)
- [ ] Take the stone out: green, and the terminal opens again.
- [ ] A tree: put a dirt pillar under the midpoint whose top is about 6 blocks below the line, plant an
      oak sapling on it and bone-meal it. Within 5 s of the tree growing into the line, the line is orange
      and the meter reads "BH: LIMITED (capped FAIR)". It needs at least six leaves on the line (3 dB
      each): if the line stays green, the canopy crossed fewer, so add oak leaves on the line by hand.
      Cut the leaves out: green again.

**6. requireBackhaul on (12 min; slice 12 steps 2 and 5).** Close the game, set `requireBackhaul =
true`, start again. Every tower with no path to a Core Site is now off the air (steps 1-4's sectors
too).
- [ ] Next to step 5's Core Site A (`/tp` there): a dish D0 within 24 blocks of the core. Site 1, a Signal
      Mast about 50 blocks out, with two dishes within 8 blocks of its base; site 2 the same about 50
      blocks further; site 3, one mast and one dish 50 blocks further. Dishes on 10-block pillars. Pair D0
      with site 1's first dish, site 1's second with site 2's first, site 2's second with site 3's dish.
      Lens on: three green lines, all three lobes lit.
- [ ] Break site 2's first dish: within 5 s the lobes of sites 2 and 3 turn grey, the middle line goes,
      and the meter beside site 3 loses it. `/rancraft backhaul status` lists sites 2 and 3 under "Off the
      air". Put the dish back and pair it again: lit within 5 s.
- [ ] Close the game, set `requireBackhaul = false`, start again: every site is lit whatever its backhaul,
      and `/rancraft backhaul status` says "still on the air because requireBackhaul is off". (Leave step
      5's stone off the line, or the meter behind it reads "BH: LIMITED (capped FAIR)", which is
      correct.)

**7. Power (15 min; slice 15 steps 1-5).** `/rancraft power status` (operator) shows each cell's draw,
buffer and state.
- [ ] Flag off (the default): place a Site Generator next to a Sector Antenna and use coal on it. It stays
      dark; using it says "idle, requirePower is off on this server", and the coal stays in it.
- [ ] Close the game, set `requirePower = true`, start again. Every tower without power is off the air
      (grey lobe; the meter loses it). Put coal in the generator beside the sector: it lights (glowing
      front, light level 13), and about a second later the sector's lobe lights. Power status: about
      10.67 FE/t at 20 dBm, the buffer filling towards 10,000.
- [ ] Take the coal out (sneak + use with an empty hand) and let the item already burning finish (at 20
      dBm a coal lasts about 5 minutes). Then the full buffer keeps the sector on for about 47 s and it
      goes grey. Put fuel back: it returns once the buffer passes 1,000 FE (about 25 s), not at the
      first FE.
- [ ] Set the sector to 30 dBm: power status about 70.67 FE/t. On one generator it cycles, about 1.3 s
      off and 1.7 s on (the lobe blinks slowly, never every tick). Add a second generator beside it: it
      stays on. (3C done-when: about 6.6x the fuel of 20 dBm.)
- [ ] A column of three Signal Masts with the generator next to the top one: the tower comes on. A Sector
      Antenna on top: the column goes quiet and the same generator feeds the sector, which comes on.
- [ ] A hopper with coal on top of a generator fills it. A lava bucket in a generator leaves an empty
      bucket, which a hopper under it takes out.

**8. Back to the defaults (1 min).** Close the game and set `requirePower = false` (and check
`requireBackhaul = false`).

**9. Survival playthrough (an evening; slice 16, slice 10 step 4, slice 12 step 6).** A **new survival
world**. The grids are in NOTES.md, slice 16, and in the recipe book once unlocked.
- [ ] Early: with a copper ingot in the inventory the recipe book shows the Field Test Meter, the RF Lens
      and the Signal Mast. Craft 4 masts (6 iron bars and a copper ingot), a meter, and, with an amethyst
      shard, the lens. A placed mast: the meter reads it and the lens shows its lobe. A compass shows the
      Network Locator; a redstone torch shows both Radio Links; the receiver crafts with a repeater and
      with a comparator.
- [ ] Mid: holding a Signal Mast shows the Sector Antenna, Core Site, Backhaul Dish and Site Generator.
      Craft a sector (it uses up a mast); two dishes show the Link Tool; pair them.
- [ ] Late: with a Sector Antenna in the inventory the Wideband Radio Unit appears (4 gold, 4 amethyst, a
      redstone block). Fitting one consumes it; band_3500 unlocks. An ender pearl shows the Storage
      Terminal; a sculk sensor, or holding the Wideband Radio Unit, shows the Proximity Scanner.
- [ ] Breaking, iron pickaxe: each of the seven blocks drops itself with the crack animation (Signal Mast,
      Sector Antenna, both Radio Links, Core Site, Backhaul Dish, Site Generator); the sector with a unit
      drops the unit too, and placed again it is tier 2. A generator drops its fuel. Bare-handed, each is
      slow to break and drops nothing.
- [ ] The creative tab (`/gamemode creative`) lists all 14 blocks and items.

**10. Optional.**
- [ ] A world saved before Phase 3C (on `main`), both flags off: every tower transmits and every device
      works as before; the meter shows no "BH:" line. A sector there on band_3500 shows "Radio tier: 3"
      with band_3500 unlocked, and breaking it drops a unit; a sector with PCI 0 keeps PCI 0.
- [ ] With twenty or more long links built, save and quit, then reopen: no visible stutter in the first
      seconds (F3 tick graph, or `/tick query`), and the links appear on the lens within a second or two.

If a step fails, note its number and what the HUD or the action bar said; the matching detailed check in
the sections above says what the code is meant to do there.
