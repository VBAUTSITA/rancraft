# RANCraft Phase 1 — implementation notes

Running log of deviations, tuning decisions and measured behaviour.
Written as I go, per the working instructions.

---

## Platform decision (RESOLVED: NeoForge 21.1.x on MC 1.21.1)

The spec pins **Minecraft 1.21.1 / NeoForge 21.1.x / Java 21 / NeoGradle / Mojang+Parchment**.

The project actually set up in this workspace
(`C:\Users\k_ale\Downloads\testmod-template-26.3`) is
**Minecraft 26.3 / Fabric Loom 1.17 / Java 25 / Fabric Loader 0.19.5**.

These are not reconcilable by configuration. Every API named in spec sections 3 and 4 is
NeoForge-specific with no drop-in Fabric equivalent:

| Spec calls for (NeoForge) | Fabric equivalent | Same shape? |
|---|---|---|
| `RegisterPayloadHandlersEvent` / `registrar.playToClient` | `PayloadTypeRegistry.playS2C` + `ClientPlayNetworking` | no |
| `ServerTickEvent.Post` | `ServerTickEvents.END_SERVER_TICK` | roughly |
| `RegisterGuiLayersEvent` | `HudLayerRegistrationCallback` / `HudRenderCallback` | no |
| `ModConfigSpec` (COMMON) | no built-in; needs Cloth/Fabric config or hand-rolled | no |
| `DeferredRegister` + `@Mod` | `Registry.register` + `ModInitializer` | no |
| Data components (`ModDataComponents`) | exists in 1.20.5+, different registration | roughly |
| `PacketDistributor.sendToPlayer` | `ServerPlayNetworking.send` | roughly |

Also: NeoForge 21.1.x for MC 1.21.1 requires **JDK 21**. Only **JDK 25** is installed
(Temurin 25.0.1). A 1.21.1 build needs either a JDK 21 install or Gradle toolchain
auto-provisioning.
**Decision: follow the spec.** RANCraft is a new NeoGradle project at
`C:\Users\k_ale\Downloads\rancraft`. The `testmod-template-26.3` folder is left untouched as the
toolchain smoke test it was. Java 21 comes from Gradle toolchain auto-provisioning (foojay resolver
in `settings.gradle`), so the installed JDK 25 is not disturbed.

**Slice 1 (the `rf` package) was unaffected either way** -- it is pure Java by design.

### Resolved versions

Queried from upstream maven rather than guessed:

| Component | Version | Source |
|---|---|---|
| NeoGradle userdev | 7.1.39 | `maven.neoforged.net` latest |
| NeoForge | 21.1.251 | latest 21.1.x for MC 1.21.1 |
| Parchment | 1.21.1 / 2024.11.17 | `maven.parchmentmc.org` |
| Gradle | 9.2.1 | matches the official MDK-1.21.1-NeoGradle wrapper |

Gradle 9.2.1 was chosen over the 9.5.1 already on the machine specifically because it is the
version the official 1.21.1 MDK ships and therefore the tested NeoGradle pairing.

---

## Build environment: this machine is memory-constrained

The first `neoFormDecompile` failed outright:

```
OpenJDK 64-Bit Server VM warning: INFO: os::commit_memory(0x..., 673185792, 0) failed;
error='The paging file is too small for this operation to complete' (DOS error/errno=1455)
> Task :neoFormDecompile FAILED
```

Measured at the time: **7,382 MB total physical RAM, 18 MB free**, with five JVMs resident (stale
Gradle daemons plus the VS Code Java language server). This is not a build misconfiguration -- the
box genuinely ran out of commit charge while the forked decompiler JVM reserved its heap.

Mitigations applied in `gradle.properties`:

| Setting | Value | Why |
|---|---|---|
| `org.gradle.jvmargs` | `-Xmx1536m` | daemon heap, down from 3G |
| `org.gradle.parallel` | `false` | parallel workers multiply peak memory |
| `neogradle.subsystems.decompiler.maxMemory` | `2g` | the decompiler forks its own JVM, so this ADDS to the daemon heap |
| `neogradle.subsystems.decompiler.maxThreads` | `2` | fewer concurrent decompile threads, lower peak |

Stale daemons were also stopped (`gradlew --stop` in both projects, 4 daemons freed).

**Worth knowing going forward:** the decompile is a one-time cost that is cached afterwards, so
later builds are far cheaper. But `runClient` will want ~2 GB of its own on top of the game. If
you hit this again, closing VS Code during the first build of a new Minecraft version is the
cheapest fix.

target unchanged. Slices 2–5 cannot start until this is resolved. Awaiting a decision.

---

## Slice 1 — `dev.rancraft.rf` (done)

Pure Java, zero `net.minecraft` imports, 18 tests green.

### Types added that the spec's file layout did not list

The signature `RfEngine.evaluate(..., BandTable bands, RfConfig config, ...)` references two types
that were not in the package layout. Both added to `rf`, both Minecraft-free:

- **`BandTable`** — immutable `id -> Band` map, rebuilt per datapack reload. `getOrFallback`
  degrades an unknown band id to a fallback rather than nulling the sample.
- **`RfConfig`** — plain record mirroring the config spec so the engine never touches a config API.
  Carries `evaluationIntervalTicks`, `requireRedstone`, `enableSampleCaching` even though the
  engine ignores them, so exactly one config object crosses the boundary. Added
  `maxRaySteps` (default 1200) to make the step cap configurable rather than a constant.

`RayMarcher.march` returns a **`MarchResult`** record rather than a bare double, because the caller
has to distinguish three outcomes: clean traverse, early exit on a dead link, and step-cap
exhaustion. The engine treats `cappedOut` as out-of-range and drops the cell.

### Tuning: the 400–600 block target conflicts with the pinned constants

The spec asks for clear-LOS max range in 400–600 blocks, but every knob is pinned elsewhere:

- `Tx = 20 dBm`, `Gt = 6 dBi`, `n = 3.5`, `f = 900 MHz` are pinned by the known-value test.
- The sensitivity floor is pinned at `-105 dBm` by the bars table.

That gives a 131 dB budget: `131 = 31.52 + 35*log10(d)` → **d = 695 blocks**, outside the target.

The spec's own sanity check got ~500 blocks because it used a **-100 dBm** sensitivity, but the
bars table says **-105**. The spec is internally inconsistent on this point.

**Decision: constants left exactly as specified.** The `maxEvaluationRangeBlocks = 600` filter
clips the link before the sensitivity floor ever does, so effective max range is **600 blocks** —
inside the requested band. Retuning Tx, Gt, n or the floor would have broken a pinned test, so the
range cap does the tuning instead. This is recorded rather than silently worked around.

### Measured bar curve (clear LOS, defaults, metersPerBlock = 1.0)

| Distance (blocks) | RSRP (dBm) | Bars |
|---|---|---|
| 10 | -40.5 | 4 |
| 96 | -75.0 | 4 → 3 boundary |
| 100 | -75.5 | 3 |
| 186 | -85.0 | 3 → 2 boundary |
| 200 | -86.1 | 2 |
| 360 | -95.0 | 2 → 1 boundary |
| 500 | -100.0 | 1 |
| 600 | -102.8 | 1 (evaluation edge) |
| > 600 | — | NO SERVICE (range filter drops the cell) |

**Consequence worth knowing:** because the 600-block cap bites before the -105 dBm floor,
a player walking away on flat clear ground goes **1 bar → NO SERVICE as a cliff at 600 blocks**,
never showing 0 bars from distance alone. 0 bars is only reachable via obstruction. If you want a
visible 0-bar band before dropout, raise `maxEvaluationRangeBlocks` above ~695.

This also means acceptance criterion "bars drop below 1 past ~550 blocks" lands at **600**, not 550.

### Geometry decisions

- **Radiating point.** `CellParams.x/y/z` is the radiating point already, not the block the player
  placed. For a Signal Mast that is `pos.above()`. Kept decoupled so Phase 3 can stack masts.
- **Ray endpoints.** The march runs from the centre of the radiating voxel (`x + 0.5`) to the
  receiver's exact eye position. Both the source voxel and the destination voxel are skipped.
- **Distance.** 3D Euclidean from the receiver to the radiating voxel *centre*, so a receiver
  standing in the antenna voxel reports distance 0, clamped to 1.0 m inside the path-loss formula.

### Test results

All 7 required tests implemented and passing, plus 6 extras (endpoint skipping, step cap,
same-voxel march, bar-threshold boundaries, evaluation cap, out-of-range emptiness, and the
range-tuning check above).

One test needed correcting during development: the engine-level obstruction-additivity check
originally placed the receiver at 100 blocks, where a clear link is -75.5 dBm. Adding 36 dB of wall
put it at -111.5 dBm, below the floor, so the engine correctly dropped the cell and there was
nothing left to compare. Moved the receiver to 30 blocks. The engine was right; the test was wrong.

### Temporary build file

`build.gradle` in this folder is a throwaway plain-Java build whose only job is to prove the `rf`
package compiles and tests with no Minecraft on the classpath. It targets `release = 21` so the
sources drop into a 1.21.1 project unchanged while still compiling on the installed JDK 25.
It gets replaced by the real mod build once the platform question is settled.

---

## Slices 2-5 -- Minecraft integration

### API deviations from the spec

| Spec text | What was actually used | Why |
|---|---|---|
| `Font#drawInBatch` via GuiGraphics | `GuiGraphics#drawString` | `drawString` is the public wrapper and calls `drawInBatch` internally with a managed buffer source. Calling `drawInBatch` directly means hand-managing `MultiBufferSource` and flushing, for identical output. |
| `Reference2DoubleMap<Block>` cache "built once on datapack reload" | built lazily on first use after reload | Block tags are not bound while reload listeners run, so resolving tags inside `apply()` would silently produce wrong values. `TagsUpdatedEvent` invalidates; the next probe rebuilds. Same guarantee, correct ordering. |
| `player.getEyeZ()` (implied by "eye position") | `player.getEyePosition()` -> `Vec3` | `Entity` has `getEyeY()` but no `getEyeZ()`. |
| Skip if "no block changed within the evaluation radius" | any block change in the **dimension** invalidates | Tracking per-radius changes needs a spatial change index. The epoch counter is conservative: it recomputes more often than required, never less. Behind `enableSampleCaching`. |
| `CellSample` carries band for display | band travels in `SignalSamplePayload` | `CellSample` is part of the Minecraft-free engine contract; display fields do not belong in it. |

Two types not in the spec's file layout had to exist because `RfEngine.evaluate` references them:
`BandTable` and `RfConfig`. Both are pure Java and live in `rf`. `RanCraftConfig` (the
`ModConfigSpec`) lives at `dev.rancraft.RanCraftConfig` and snapshots into `RfConfig`.

### Deliberate Phase 1 simplifications

- **Waterlogging is not additive.** A waterlogged stair attenuates as the stair, not stair + water.
  Fixing this means keying the material table on `BlockState` instead of `Block`, which costs the
  O(1) reference lookup the spec explicitly asked for.
- **Unloaded chunks read as air.** `LevelWorldProbe` never force-loads. A mast in an unloaded chunk
  is absent from `SiteRegistry` and does not transmit, as the spec specifies.
- **Block-change epoch is per dimension**, see the table above.
- **Placeholder art.** The mast model and meter item reference vanilla textures
  (`minecraft:block/iron_block`, `minecraft:item/iron_ingot`) rather than shipping PNGs, so no art
  is blocking. Swap the `textures` blocks in
  `assets/rancraft/models/{block/signal_mast,item/field_test_meter}.json` when you have real art.

### Running the dev client

NeoGradle does not generate VS Code launch configs the way Fabric Loom's `vscode` task does. Use
the Gradle run tasks:

```
gradlew.bat runClient
gradlew.bat runServer
```

### Manual test plan (maps to the acceptance criteria)

1. **HUD appears** -- creative, grab a Signal Mast and a Field Test Meter from the RANCraft tab.
   Place the mast, hold the meter. A readout appears top-right within 1 s. Right-click toggles the
   detailed panel (top-left).
2. **Monotonic falloff** -- flat superflat world. Walk directly away. RSRP falls smoothly.
   Expected: 4 bars to ~96 blocks, 3 to ~186, 2 to ~360, 1 to 600, then NO SERVICE at the
   evaluation-range cliff. (Note: the cliff is at 600, not the ~550 the spec predicted -- see the
   tuning section above.)
3. **Terrain shadowing** -- stand behind a hill at a fixed distance, compare against clear LOS at
   the same distance. Obstruction dB should be non-zero and RSRP measurably lower.
4. **Faraday cage** -- at a distance showing 3 bars, wall yourself into a 3x3x3 of iron blocks.
   Iron is 30 dB/block; two blocks of wall on the path is 60 dB, which drops a -80 dBm link to
   -140 dBm, well under the floor. Expect NO SERVICE.
5. **Cell reselection** -- two masts, unequal distance. The HUD reports the stronger as serving and
   switches as you cross the midpoint.
6. **Break removes the site** -- break the serving mast. Within one sample (1 s) the HUD drops it.
7. **Save round-trip** -- place a mast, save and quit, reload. The block entity should still carry
   all Phase 2 seams (`AzimuthDeg`, `TiltDeg`, `HBeamwidthDeg`, `VBeamwidthDeg`, `Pci`) plus
   `DataVersion=1`. Verify with an NBT viewer or by checking the mast still transmits identically.

---

# RANCraft Phase 2 — implementation notes

Slices 1–5 (the whole Minecraft-free half) are complete and verified headless.
Slices 6–8 are not started. See "Status" at the end.

---

## Bearing convention (verify this before trusting any azimuth)

Minecraft puts **north at −Z** and **east at +X**. `AntennaGeometry` uses the compass convention,
because that is what antenna datasheets and the GUI polar plot use:

| Bearing | Direction | Vector |
|---|---|---|
| 0° | north | −Z |
| 90° | east | +X |
| 180° | south | +Z |
| 270° | west | −X |

`bearingDeg = normalize360(toDegrees(atan2(dx, -dz)))` produces exactly that. All four cardinals are
pinned by `AntennaPatternTest.bearingCardinals()`, plus a diagonal, because a 90° azimuth error is
silent and survives casual play-testing.

Note this is **not** vanilla yaw, which runs clockwise from south. A block's `HorizontalDirectionalBlock`
facing must be mapped N=0 / E=90 / S=180 / W=270 when slice 6 sets `azimuthDeg` on placement.

Elevation is positive when the **receiver is above the antenna**, so a player on the ground below a
mast has negative elevation — which is the case downtilt exists for. `atan2` is guarded with a
1e-6 horizontal floor, so a receiver directly above an antenna reads 89.999994°, not 0°. That guard
is why the vertical-geometry test uses a 1e-4 tolerance rather than 1e-9.

---

## Omni cells short-circuit the pattern entirely (deliberate)

Spec §1.2 says a 360° antenna has `A_H = 0`. It is silent on `A_V`. Two readings are possible and
they disagree by up to 3 dB close to a tower, so the choice is recorded here:

- `ParabolicPattern` implements the literal spec: `A_H = 0` for 360°, but the **vertical pattern
  still applies**.
- `OmniPattern` is completely flat in both planes.
- `AntennaPattern.forCell()` routes any 360° cell to `OmniPattern`.

**Why flat wins.** Phase 2 acceptance requires an omni mast to read identically to Phase 1 within
0.01 dB, and Phase 1 applied a constant gain with no angular term at all. Phase 1 masts carry
`vBeamwidthDeg = 90`; running them through the vertical parabola would cost 3 dB at 45° elevation —
a visible regression for anyone standing near their own tower.

**Fidelity cost, stated plainly:** a real omni panel is omnidirectional in azimuth only. It has a
vertical pattern, and a receiver at the foot of the mast really is in its null. RANCraft does not
model that. The Signal Mast is the cheap early-game option and behaves like an ideal point source.

Both engine and GUI call `AntennaPattern.forCell()`, so the preview can never disagree with the
measurement.

---

## Measured band reach — the numbers do not match the spec's estimates

Spec §2 predicts "roughly 700 m at band_700, 500 at band_900, 300 at band_1800, 120 at band_3500"
with Tx 20 dBm / 15 dBi sector / −105 dBm floor. Measured with exactly those inputs:

| Band | PL@1m | n | **Reach, 15 dBi sector** | Reach, 6 dBi omni | Spec estimate |
|---|---|---|---|---|---|
| band_700 | 29.34 dB | 3.2 | **2871** | 1502 | ~700 |
| band_900 | 31.52 dB | 3.5 | **1257** | 695 | ~500 |
| band_1800 | 37.55 dB | 3.6 | **701** | 394 | ~300 |
| band_3500 | 43.32 dB | 4.0 | **261** | 156 | ~120 |

Every band is 2.1–4.1× the spec's estimate, uniformly. The spec's sanity check —
"if band_3500 reaches 2 km the exponent or the floor is wrong" — passes: band_3500 reaches 261
blocks, not 2 km. The exponents and the floor are **not** wrong; the spec's arithmetic appears to
have used a smaller link budget than the one its own pinned constants produce. This is the same
inconsistency Phase 1 already recorded for the 400–600 block target.

### The consequence, which is a real design problem

`maxEvaluationRangeBlocks` defaults to **600**. With a 15 dBi sector that cap bites before the
sensitivity floor on three of the four bands:

| Band | Sector reach | Effective in-game reach |
|---|---|---|
| band_700 | 2871 | 600 (capped) |
| band_900 | 1257 | 600 (capped) |
| band_1800 | 701 | 600 (capped) |
| band_3500 | 261 | **261 (physics)** |

So **band_700, band_900 and band_1800 are indistinguishable in range** on sector antennas. Only
band_3500 behaves differently. That partly undermines the acceptance criterion "switching a site
from band_700 to band_3500 visibly shortens range" — the low/mid comparison is invisible, though
the low/high one is stark.

### Resolution: `maxEvaluationRangeBlocks` raised 600 → 1400

Raised deliberately, not quietly — it contradicts the Phase 1 tuning note, which chose 600 to land
inside the Phase 1 400–600 target. Phase 2 needs band choice to change range, and at 600 it did not.

After the change:

| Band | Sector reach | Bounded by |
|---|---|---|
| band_700 | 2871 | cap (1400) |
| band_900 | 1257 | **floor** |
| band_1800 | 701 | **floor** |
| band_3500 | 261 | **floor** |

Three of four bands are now physics-limited and visibly differ. Only band_700 still hits the cap.
Raise to ~2900 to differentiate all four; the benchmark has the headroom (re-measured at the wider
range: **0.0086 ms/evaluation**, 116× inside budget), but registry queries and ray marches both grow
with the cube of the radius, so this was left at the more conservative value.

**Side effect on Phase 1 behaviour, worth knowing:** a 6 dBi omni mast on band_900 now fades out at
**~695 blocks on the sensitivity floor** rather than being cut off at a hard 600-block cliff. This
is the behaviour the Phase 1 notes said they wanted ("raise `maxEvaluationRangeBlocks` above ~695")
— the falloff is now smooth to the end instead of dropping off an edge. `RfEngineTest`'s tuning
check was rewritten to assert the floor, not the cap, is what bounds the link.

Rejected alternatives: `metersPerBlock = 2.0` would halve every reach without touching a pinned
constant, but it rescales Phase 1 readings so old worlds would measure differently. Leaving 600
would have made band choice a pure penetration decision.

Wall penetration is unaffected by any of this and works as designed — one 12 dB stone block costs:

| band_700 | band_900 | band_1800 | band_3500 |
|---|---|---|---|
| 8.4 dB | 12.0 dB | 13.2 dB | 21.6 dB |

---

## Additive record changes

Per the ground rule, nothing was removed or reordered. Appended only:

| Record | Appended |
|---|---|
| `Band` | `noiseFloorDbm`, `capacityTier` |
| `CellSample` | `bandId`, `pci`, `effectiveGainDbi`, `azimuthOffsetDeg`, `elevationOffsetDeg` |
| `SignalSample` | `servingCellId`, `sinrDb`, `interferenceDbm`, `noiseDbm`, `serviceLevel`, `handoverCount` |
| `RfConfig` | the nine §10 tunables |

`SignalSamplePayload` version bumped **1 → 2**, written as the first field.

### One behavioural change worth knowing

`SignalSample.serving()` no longer means `cells.get(0)`. From Phase 2, hysteresis can hold a
receiver on a **slightly weaker** cell to stop it ping-ponging, so the serving cell and the
strongest cell can differ. `strongest()` was added for callers that really do want index 0.
Anything that assumed "index 0 is serving" is now wrong.

`isNoService()` also widened: it is now true when the service level is `NONE`, not only when the
cell list is empty. A link drowned in interference is genuinely no service. The HUD deliberately
does **not** use it for that purpose — see below.

---

## Deviations from the spec text

| Spec text | What was done | Why |
|---|---|---|
| `world/ReceiverStateStore.java` | `rf/ReceiverStateStore.java`, generic in the key | The spec also says to keep the logic testable without a world. It has no Minecraft dependency, so putting it in `rf` avoids a second copy for Phase 3's block-keyed devices. `world` just instantiates it with `UUID`. |
| `world/PciPlanner.java` | `rf/PciPlanner.java` | Same: the spec says "keep the detection logic in `rf` or a pure helper class". It is pure, so it lives in `rf` and is fully unit-tested. |
| `SinrCalculator` takes cells | takes `RxSignal(cellId, bandId, pci, rsrpDbm)` | SINR arithmetic has no business depending on the shape of a geometry record. `CellSample.asRxSignal()` bridges. |
| `⚠` in the HUD conflict line | `[!]` | U+26A0 is not reliably present in Minecraft's default font. A missing-glyph box next to a PCI collision would be worse than no marker. The degree sign is width-probed and falls back to `deg`. |
| mod-3 warning on identical PCIs | suppressed | Two cells with the *same* PCI trivially share `pci % 3`. That is already reported as a collision (an error); adding a warning on the same pair is noise. |
| "Clamp SINR to [−20,+40]" | raw kept, clamped on display | `SignalSample.sinrDb` is raw and drives classification; `displaySinrDb()` clamps. Clamping before classification would misreport a −40 dB link as −20 dB POOR. |

### Sample caching now yields to an armed handover

`SignalTicker` skips its cache whenever a handover candidate is armed. Replaying a cached payload
would freeze the time-to-trigger clock, so a player standing still at a cell boundary would never
hand over at all. Caching still applies in the common case.

---

## Fidelity — what is real and what is a game abstraction

**Real, modelled faithfully:**
- The 3GPP parabolic pattern approximation, and the 3 dB beamwidth definition. The factor 12 is not
  a fudge: `12 · 0.5² = 3` is *why* half a beamwidth off boresight costs exactly 3 dB. Pinned by test.
- Front-to-back ratio (A_m) and vertical sidelobe floor (SLA_v) as saturating floors.
- The frequency term in free-space path loss. High bands are penalised by `20·log10(f)` alone —
  there is deliberately **no** second manual penalty stacked on top.
- Downtilt's near/far tradeoff, and uptilt raising the beam.
- Gain–beamwidth reciprocity: `G ≈ 10·log10(32400 / (θ_h · θ_v)) − 2 dB`. 65° × 10° → 15.0 dBi,
  which is what a real sector panel specifies. Gain is *derived*, never set independently, so
  "360° and 20 dBi" is impossible.
- SINR as serving-over-interference-plus-noise, summed in linear milliwatts.
- Adjacent-channel rejection making other-band neighbours matter slightly but not much.
- A3-style handover: neighbour-better-by-an-offset, plus time-to-trigger.
- PCI collision and confusion, with their real definitions.

**Abstracted, and labelled as such in the code:**
- **The mod-3 penalty is a flat linear power multiplier (2.0 = +3 dB).** A real mod-3 conflict
  collides cell-specific reference signals and wrecks channel estimation and RS decoding. It does
  **not** raise the interferer's power. The multiplier is a stand-in that costs one multiply and
  makes mod-3 planning a decision the player can feel. The note is repeated verbatim in
  `SinrCalculator.compute` so nobody reads the code and learns the wrong thing.
- **One flat noise floor per band**, instead of bandwidth × noise figure × kT.
- **No fading, no shadowing, no MIMO, no scheduler, no load.** Output is fully deterministic —
  100 identical runs are asserted byte-identical.
- **Per-block attenuation is a flat dB constant**, scaled by a per-band `penetrationFactor`, rather
  than a frequency-dependent material property.
- **One RSRP value stands in for RSRP / RSRQ / RSSI at once.**
- **The omni vertical pattern is absent**, see above.

**Deliberately absent until later phases:** traffic and capacity (Phase 3 gates devices on
`ServiceLevel` + `Band.capacityTier` instead), backhaul, energy, and any prediction that is not a
live point measurement.

---

## Tests

**75 passing**, headless, zero `net.minecraft` imports in `rf` (asserted by grep, 21 source files).

| Group | Tests | Covers |
|---|---|---|
| Phase 1 carry-over | 18 | unchanged and still green against the new engine |
| `AntennaPatternTest` | 15 | spec tests 1–6, plus bearing cardinals and gain–beamwidth |
| `SinrCalculatorTest` | 9 | spec tests 7–10, plus ServiceLevel boundaries |
| `CellSelectorTest` | 7 | spec tests 11–14, plus store lifecycle |
| `PciPlannerTest` | 12 | spec tests 15–16, plus clean-plan silence |
| `RfEnginePhase2Test` | 14 | spec tests 17–19, plus the headline SINR and pattern behaviours |

### Four fixtures were wrong on first run; the engine was right each time

Recorded because the failures were instructive:

1. **Downtilt test** put the receiver 54° below a tall mast. With a 10° vertical beamwidth both the
   tilted and untilted cases saturate at SLA_v, so tilt changed nothing. Geometry moved to within
   10° of the horizon.
2. **Ray-prioritisation test** had the "deaf" near cell pointing *at* the receiver — its optimistic
   budget genuinely beat the far cell's. Fixed the azimuth and moved it out to 60 blocks.
3. **Band test** put band_3500 behind 24 dB of stone at 100 blocks, which is below the floor, so
   the engine correctly dropped the cell and left nothing to compare.
4. **Determinism test** asserted `!isNoService()`, but the fixture's two strongest cells are
   co-channel and 1.6 dB apart, giving SINR −1.4 dB and a legitimate `NONE`. Plenty of signal, no
   usable service — the Phase 2 lesson arriving uninvited in an unrelated fixture.

---

## Performance

`RfEngine` pruning measured over 20,000 evaluations, 16 candidate cells across four bands, random
azimuths, a scattering world, JIT warmed:

```
avg 0.0077 ms/evaluation        (budget: 1 ms)
in-range cells 16.0 → ray-marched 7.0, budget-pruned 9.0
56% of in-range cells never touched the world
```

130× inside budget. `SignalTicker` logs the same ratio whenever an evaluation exceeds 2 ms.

The pruning order is load-bearing, not an optimisation: pattern gain costs two `atan2` calls, and
computing it *before* the ray march is what lets a backlobe cell at 400 blocks be discarded without
a single `WorldProbe` call. Test 17 asserts exactly zero probe calls in that case.

Candidates are sorted by **optimistic RSRP**, not by distance as in Phase 1, so the 12 cells that
get rays are the 12 most likely to matter rather than merely the nearest.

---

## Status and the build blocker

### Done and verified (slices 1–5)

`AntennaGeometry`, `AntennaPattern`, `ParabolicPattern`, `OmniPattern`, `ServiceLevel`,
`SinrCalculator`, `ReceiverState`, `CellSelector`, `ReceiverStateStore`, `PciConflict`,
`PciPlanner`, and the rewritten `RfEngine`. All 19 required Phase 2 tests present and green.

### Minecraft-side wiring of slice 5 (now compiled and server-booted)

`RanCraftConfig` (nine new options plus the range change above), `RfDataLoader` (band fields),
the four band JSONs,
`SignalSamplePayload` (v2), `SignalTicker` (receiver state, lifecycle clearing, PCI note, pruning
telemetry) and `SignalHudOverlay` (the §9 layout). All compile and load on a dedicated server.

### Slices 6–8 (done, compiling, see below)

`SectorAntennaBlock` + block entity, `dataVersion` 1→2 migration, `/rancraft pci check`, the
`OpenAntennaConfigPayload` / `UpdateCellParamsPayload` pair, and `AntennaConfigScreen` with the
polar pattern preview.

### The build blocker (RESOLVED)

`./gradlew build` now **succeeds**: 75 tests, 0 failures, `build/libs/rancraft-0.1.0.jar` produced.
This is the first successful build in this workspace — before it, `build/classes` held only the
`rf` package and no Minecraft-side code from either phase had ever been compiled.

**Cause.** Vineflower OOMed inside the `Blocks.java` static initializer, emitted a
`$VF: Couldn't be decompiled` stub, and exited 0. NeoGradle cached that as a successful decompile,
and `:neoFormPatchUserDev` then failed applying the NeoForge patch to a stub — one rejected hunk,
`net/minecraft/world/level/block/Blocks.java.patch.rej`.

**Fix.** `neogradle.subsystems.decompiler.maxMemory=4g` (already in `gradle.properties`) plus
deleting `build/neoForm/` so the poisoned artifact was re-derived rather than restored. The
decompile now produces a real 327 KB `Blocks.java` and the patch applies cleanly.

Note the decompile output jar is ~9.3 MB and that is **correct** — it is compressed sources. Jar
size is not a usable health check; the stub marker inside `Blocks.java` is.

**One genuine compile error** surfaced in the Phase 2 code once a real classpath existed:
`SignalHudOverlay` called `displaySinrDb()` on `SignalSamplePayload`, but the method existed only on
`SignalSample`. Added to the payload. Everything else compiled first time.

Remaining output is five deprecation warnings inherited from Phase 1 (`EventBusSubscriber.bus()`,
`DeferredRegister.createDataComponents`), none introduced by Phase 2.


---

## Slices 6–8 — Minecraft integration

### Shared base class rather than duplicated block entities

`SignalMastBlockEntity` and `SectorAntennaBlockEntity` now both extend a new
`AntennaBlockEntity`, which owns every persisted field, the registry lifecycle, the PCI assignment
and the migration. The two subclasses are ~20 lines each and differ only in their defaults and
whether they have a facing.

This refactors a Phase 1 file. It was done rather than copy-pasting ~120 lines of NBT handling that
would then have to be kept in step across two classes for Phases 3 and 4.

`isTransmitting()` reads `BlockStateProperties.POWERED`, which is a singleton instance shared by
both blocks, so the base class never has to know which subclass it is on.

### Migration, dataVersion 1 → 2

On load, a tag with `DataVersion < 2` (or no `DataVersion` at all) keeps every value it has. The
only field filled in is PCI:

> Phase 1 wrote `Pci = 0` for every site because it had no planner. Phase 2 treats a loaded 0 as
> "never assigned" and re-plans it against its neighbours. Without that, a migrated world is one
> giant PCI collision.

The consequence, stated plainly: **a Phase 1 site that genuinely wanted PCI 0 will be reassigned.**
Phase 1 had no way to express that intent, so nothing is actually lost, but it is a real behavioural
difference rather than a pure no-op.

Assignment is deferred from `loadAdditional` to `onLoad`, because planning needs the neighbouring
sites and `SiteRegistry` is not populated while NBT is being read.

Migration logging is debounced to one INFO line per 10 seconds carrying a running total, rather than
one line per tower. The spec asked for "once with the count"; a strict once-ever would either fire
before the count was known or never fire on a slow chunk-by-chunk load.

### Sector antenna

Independent cell per block — no "site" container. A three-sector site is three blocks at azimuths
0/120/240, which is what `SiteRegistry` and Phase 4's planning table already expect.

Facing maps to azimuth on placement as N=0 / E=90 / S=180 / W=270, the compass convention, **not**
vanilla yaw. The block faces away from the player on placement, like a furnace front. Defaults are a
real panel: 65° × 10°, 3° downtilt, 15 dBi derived, band_900, PCI auto-assigned.

Right-click with an **empty hand** opens the configuration screen; `useItemOn` passes through, so
the antenna never swallows block placement or tool use.

**The Signal Mast has no GUI.** Spec §5.2 says it is unchanged, and exposing the beamwidth control
on it would let a player turn the cheap omni into a sector panel, collapsing the progression. If
band selection on masts is wanted later, it needs a mast-specific screen with only that control.

### Server-side validation of `UpdateCellParamsPayload`

Nothing from the client is trusted. Before anything is written the server re-checks:

- the player is within 8 blocks (`distanceToSqr < 64`),
- the target block entity still exists and is an `AntennaBlockEntity`,
- the band id is one the datapack actually loaded,
- Tx is 0–30 dBm, tilt −10…+20°, azimuth 0–359°, PCI 0–503,
- the beamwidths are **exactly** one of the offered options, not merely in range.

**Gain is not in the packet at all.** It is derived server-side from the beamwidths, so a client
cannot ask for 20 dBi on a 360° antenna. That is the structural version of the rule, rather than a
validation rule that could be forgotten.

Rejections are logged with the player name and dropped. There is no legitimate client that sends an
invalid one.

### GUI

A plain `Screen`, no `MenuType` — there are no item slots.

The pattern preview samples the **same `AntennaPattern` object the server evaluates against**, via
`AntennaPattern.forCell()`, at 2° steps. This is the payoff for keeping `rf` Minecraft-free: the
plot cannot drift from the measurement, because there is only one implementation of the maths. The
polar plot uses the same compass convention as the engine (0° up = north), and the vertical cut
draws the horizon as a centre line so downtilt visibly drops the lobe below it.

Deviations worth noting:

- **`DoubleSlider`**, a small `AbstractSliderButton` subclass, because vanilla's slider is 0–1 only.
- **Bresenham line drawing**, because `GuiGraphics` only draws axis-aligned lines.
- Azimuth and PCI use sliders rather than the number boxes the spec suggested. An `EditBox` needs
  parse/validate/revert handling for a value the slider expresses exactly; the slider cannot produce
  an invalid value at all.
- The plot maps gain onto 40 dB of dynamic range below peak. Below that everything would collapse
  onto the origin and the backlobe would be invisible.

### Payload versioning

`ModPayloads` protocol version bumped **1 → 2**, so a Phase 1 client cannot connect to a Phase 2
server and silently misread the sample. `SignalSamplePayload` additionally carries its own
`VERSION` field as the first value on the wire.

`OpenAntennaConfigPayload` is registered `playToClient` and its handler lambda is the only reference
to `ClientAntennaConfig`. A dedicated server never receives an S2C packet, so the client-only class
is never resolved there.

---

## Verification performed

| Check | Result |
|---|---|
| `./gradlew build` | **succeeds** |
| Unit tests | **75 passed, 0 failed** |
| `rf` package Minecraft imports | **0** |
| Engine performance, 16 candidates | **0.0086 ms/eval** (budget 1 ms) |
| Pruning effectiveness | **56%** of in-range cells never ray-marched |
| `./gradlew runServer` | boots to `Done (0.950s)!`, no mod errors |
| Band datapack load | `RANCraft loaded 4 band(s), 23 block override(s), 11 tag override(s)` |

### Not yet verified — needs a human at a client

Everything above is headless. These require actually playing and are the remaining acceptance
criteria:

1. Three sectors at 0/120/240 giving continuous coverage with a ~3 dB dip at each 60° crossover.
2. Pointing a sector 180° away dropping RSRP by the front-to-back ratio. *(Asserted headlessly at
   exactly 30.00 dB in `RfEnginePhase2Test.frontToBackIsObservable`, but not seen in world.)*
3. Downtilt 0°→10° raising near RSRP and lowering far RSRP. *(Also asserted headlessly.)*
4. A 4th co-channel site lowering SINR, recovered by re-banding or retilting — **the headline demo.**
   *(Asserted headlessly in `fourthCoChannelSiteDegradesSinr`.)*
5. Two sites sharing a PCI raising a collision in the GUI and in `/rancraft pci check`.
6. Walking a cell boundary without ping-pong, handover counter incrementing once.
7. GUI pattern preview matching measured falloff at three spot-checked azimuths.
8. A Phase 1 world loading with every mast intact and migrated to `dataVersion = 2`.

**(8) has no Phase 1 save to test against** — the Minecraft side never built during Phase 1, so no
world was ever created with `dataVersion = 1` masts in it. The migration path is written and
compiles, but it has not been exercised against real Phase 1 NBT. A world would have to be created
by checking out the Phase 1 code, and that is worth doing before claiming the criterion.

Client-side rendering in particular is completely unexercised: the HUD layout, the polar plot, the
`[!]` marker and the degree-sign width probe have all been compiled but never drawn.
