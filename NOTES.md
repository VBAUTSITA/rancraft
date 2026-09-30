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

---

# RANCraft Phase 3 — implementation notes

Spec: `PHASE_3_PROMPT.md`. Tracker and follow-ups: `PHASE_3.md`. One section per slice.

---

## Slice 0 — RF Vision Step 3a: drive-test trail and CSV export

The precondition `PHASE_3_PROMPT.md` recommends: Step 3a edits `SignalTicker` and
`SignalSamplePayload`, and so does §3A.3, so 3a lands first rather than interleaving the two.
Design: `VISION_STEP3.md` Part 3a.

### What was built

| Piece | Where | Notes |
|---|---|---|
| `DriveTestLog` | `rf/` (pure) | Ring buffer, event classification, stationary de-dup, RFC 4180 CSV. Copied from the session scratchpad after review; two changes, below. |
| Evaluation point on the wire | `net/SignalSamplePayload` | Appended `rxX, rxY, rxZ` (the eye position the server evaluated at) and `cellsHeard`. `VERSION` 2 → 3. |
| Protocol | `net/ModPayloads` | `PROTOCOL_VERSION` "3" → "4". |
| Server send rule | `world/SignalTicker` | The sample now goes to a player holding a meter **or** wearing a lens whose settings show the trail. Still one evaluation per player per interval. **Superseded by the gate fix below:** every evaluation is sent, a LINKS-only lens included. |
| Client log | `client/ClientDriveTest` | Every received sample, one `DriveTestLog` per dimension. |
| Trail | `client/TrailRenderer` | Camera-facing squares in the HUD's level colours; white pillar = handover, yellow = reselection; a faint neutral line joins consecutive samples. |
| Export / clear | `client/DriveTestCommands` | `/rancraftc drivetest export` and `/rancraftc drivetest clear` via `RegisterClientCommandsEvent`. |
| Layer | `item/LensLayers`, `item/LensSettings` | `TRAIL` preset; `ALL` includes it. `showTrail` appended as the 5th `LensSettings` field. |
| Client config | `RanCraftConfig.CLIENT_SPEC` | New `config/rancraft-client.toml`: `driveTestCapacity` (3600), `trailRenderDistance` (128). |
| Shared colours | `client/LensStyle` | The HUD's five level colours moved here so the trail and the meter can never drift. Values unchanged, pinned by a test. |

### Review changes to the scratchpad `DriveTestLog`

1. **An exact replay of the previous sample is ignored.** The server replays its cached payload
   (same tick, same everything) while the player stands still. Normally the stationary rule
   collapses that, but right after a handover the previous entry is an event, which the stationary
   rule must never replace. So standing still on a handover spot logged the handover sample twice:
   once as the event, once as a plain row with the same tick. Now an identical sample is a no-op.
   Record equality compares doubles with `Double.compare`, so NaN RSRP on no-service rows still
   matches.
2. **`forEach(Consumer)`** was added: the renderer walks up to 3,600 entries every frame, and
   `entries()` copies.

Tests: 19 → 22 (`exactReplayIsIgnored`, `exactReplayWithNaNIsIgnored`, `forEachMatchesEntries`).
The 19 originals are unchanged.

### Deviations from VISION_STEP3.md and the slice brief

| Design text | What was done | Why |
|---|---|---|
| Payload gains `rxX, rxY, rxZ` | also `cellsHeard` (append, same v3 bump) | The payload carries at most 4 cells. The CSV `cells` column would silently cap at 4, which hides exactly the pilot-pollution/stacked-mast case a drive test exists to find. Same precedent as `coChannelCount`, which is also counted over the full list. |
| (not mentioned) | **The 4-cell cut now always keeps the serving cell** (`SignalSamplePayload.topCells`) | **Bug found and fixed.** A plain top-4 cut dropped a serving cell that hysteresis held at rank 5 or lower, which is easy next to a column of stacked co-channel masts, all within 3 dB of each other. The HUD then drew NO SERVICE over a real (interference-drowned) serving link, hiding the diagnosis; the trail would have logged a false outage. The lens's `chooseLinks` already used this rule. **This changes the meter HUD in that one edge case**, so slice 4's "byte-identical meter" gate compares against this slice, not Phase 2. |
| `drivetest-<timestamp>.csv` | one file per dimension: `drivetest-<yyyyMMdd-HHmmss>-<dimension>.csv`, e.g. `...-minecraft_overworld.csv` | Overworld and Nether coordinates are different places. One table mixing them would place rows next to each other that describe unrelated ground. The column header is exactly as designed. |
| one log | one `DriveTestLog` per dimension, keyed by the client's level at receipt | Same reason, for drawing: an Overworld trail must not be drawn in the Nether. The dimension at receipt is the dimension measured in: samples and the respawn packet travel one ordered connection, and both are queued FIFO on the client thread (checked in NeoForge's `MainThreadPayloadHandler` / `ClientPayloadContext.enqueueWork` and vanilla `PacketUtils.ensureRunningOnSameThread`). |
| default 3,600 samples | `driveTestCapacity` in a new **CLIENT** config spec, plus `trailRenderDistance` | "Everything tunable goes in RanCraftConfig." These are client-only knobs, so they sit in a CLIENT spec (FML loads it only on a physical client) rather than COMMON, where a server admin would see settings they cannot affect. A capacity change applies to a dimension's log when it next starts (after `clear`, or since the follow-ups below, after a relog). |
| trail samples | every received sample is logged, from the meter or the lens | A Field Test Meter is what a real drive-test scanner is, so a walk with the meter out is a drive test too. The trail layer only decides whether the log is drawn. |
| (not mentioned) | the lens band filter does not apply to the trail | The trail records what the receiver got; the serving band is part of that result, not a view choice. |
| (not mentioned) | pre-3a lens saves: a missing `show_trail` is derived as `lobes && links && coverage` | A lens saved on ALL gains the trail (ALL now includes it). A lens narrowed to one layer (say LINKS) stays on it, rather than waking up with the trail on and reading as an unnamed combination. `show_trail` is always written from now on, so the rule only touches pre-3a data. Pinned by `LensSettingsTest`. |

### Clearing: kept until `/rancraftc drivetest clear`

> **Superseded by "Vision Step 3a follow-ups" below:** the log is now also cleared on logging out,
> like every other client readout. It still survives respawn and dimension change. The text below
> is what slice 0 shipped.

The brief said to clear the log on disconnect and dimension change only if `VISION_STEP3.md` says
so. It does not (it only says `/rancraftc drivetest clear` resets it). So **the log survives
disconnect, respawn and dimension change**, unlike the meter and lens readouts that `ClientEvents`
clears. Per-dimension logs keep a portal trip from smearing trails together.

Consequence, stated plainly: the client cannot reliably tell which world it joined, so **joining a
different world keeps the old trail under the same dimension names** (drawn in the new world at the
old coordinates, and exported together). Run `/rancraftc drivetest clear` when switching worlds.
Logged as a follow-up in `PHASE_3.md`. ~~Accepted limitation.~~ Fixed by the follow-ups below.

### Server cost

- **No new evaluation for anyone who was already evaluated.** The default lens (ALL) already had
  link rays on, so it was already evaluated each interval; it now also receives the sample packet
  it already had built and cached.
- **One new case costs an evaluation:** a wearer on the TRAIL-only preset, who is otherwise idle.
  It is the same single evaluation the meter and link rays share, with the same cache and the same
  stagger. ANTENNAS and COVERAGE presets still cost the ticker nothing.
- **Wire:** +25 bytes per sample (three 8-byte doubles and a one-byte varint while fewer than 128
  cells are heard), 1 Hz per player who wants it.
- The ticker's subtleties are untouched: the cache skip while a handover candidate is armed, the
  lens link cap and band-filter check on replays, the stagger by `player.getId()`, `forget()` on
  logout, dimension change and respawn. A cached replay carries the point of the evaluation it
  replays, which `isCurrent()` keeps within 0.5 blocks of the player.

### Honest-abstraction notes (also at the code sites)

- **Nothing on the trail is computed client-side.** `SignalSamplePayload.toDriveTestSample()` is a
  field-for-field copy (tested). The only derived thing is the event, read off consecutive server
  values; a handover is the server's own counter going up, never guessed from a cell change.
- **`y` is eye height.** The server evaluates at the eye, so markers hang at eye height and the CSV's
  `y` is feet + 1.62 when standing (lower when sneaking or swimming).
- **The trail is a record, not a map.** The joining line is a faint neutral white on purpose: nothing
  was measured along it, and colouring it would claim otherwise. Gaps over 24 blocks (teleport,
  respawn, relog) are not joined at all.
- **RESELECTION covers two things:** recovery from outage, and the first cell after the server reset
  the handover counter (respawn, dimension change, relog). Both are "serving cell changed without an
  A3 handover"; the CSV cannot tell them apart.
- **OUTAGE gets no pillar.** It shows as the marker turning grey; a pillar per outage sample would
  bury the handovers.
- **1 Hz sampling** (the configured `evaluationIntervalTicks`). A sprinting player skips ground
  between markers, as a real scanner does at its own rate.
- **`tick`** is the server game time of the evaluation. Exact replays are dropped (above), so a
  cached replay never adds a second row for the same evaluation.

### StreamCodec ceiling

`LensSettings.STREAM_CODEC` is a `StreamCodec.composite`, which in 1.21.1 takes at most **6**
fields (verified in the decompiled `StreamCodec`: overloads stop at `Function6`). `LensSettings` is
now at **5**. Step 3b's metric would make 6; a seventh must switch to a hand-written
`StreamCodec.of(...)`. Noted in the `LensSettings` javadoc. Phase 3 §3C.2 already says not to add a
field for backhaul links.

### Spec vs tree, for later slices

`PHASE_3_PROMPT.md` §3A.3 says "a player is evaluated if they carry any device or wear a link
lens", and §3A.2 says the meter "sends SignalSamplePayload only when held". It was written before
Step 3a landed. The tree now also sends the sample to a lens wearer showing the trail, whether or
not they carry any device. The trail is a lens path like the link rays (which §3A.2 says "is not a
device and does not move"), so it stays in `SignalTicker`. ~~Slice 4 must keep
`sendSample = meterHeld || lensShowsTrail`.~~ **Superseded by the slice 0 gate fix below:** that
rule left a LINKS-only lens evaluated but not sent, which put false handover pillars on the trail.
Slice 4 must keep **"every evaluation sends the sample"** (`SignalTicker.sendsSample`). Recorded in
`PHASE_3.md` follow-ups.

### Versions after this slice

| Thing | Before | After |
|---|---|---|
| `ModPayloads.PROTOCOL_VERSION` | "3" | **"4"** (so Phase 3A's bump goes to "5") |
| `SignalSamplePayload.VERSION` | 2 | **3** |
| `LensSettings` fields | 4 | **5** (of 6) |
| `AntennaBlockEntity.DATA_VERSION` | 2 | 2 (untouched) |

### APIs verified against sources (new to this codebase)

`RegisterClientCommandsEvent` (game bus, client only) and `ClientCommandSourceStack`
(`sendSuccess` and `sendFailure` reach the local chat); `StreamCodec.composite` 5-field overload;
DFU `optionalFieldOf(name, default)` omits default values on encode (why `show_trail` uses the
`Optional` form); `ModConfig.Type.CLIENT` loaded only on a physical client; `ModConfigSpec.isLoaded`
and `getDefault`; `Camera.getLeftVector`/`getUpVector`; `ClickEvent.Action.OPEN_FILE` as vanilla
`Screenshot` uses it; `Minecraft.gameDirectory`; `RenderType.debugQuads` (translucent, no cull,
sorted on upload).

### Verification

| Check | Result |
|---|---|
| `./gradlew build` | succeeds |
| Unit tests | **203 passed, 0 failed** (161 before; +22 `DriveTestLogTest`, +7 `SignalSamplePayloadTest`, +7 `LensSettingsTest`, +3 `DriveTestCommandsTest`, +3 `LensStyleTest`; `LensLayersTest` updated in place) |
| `rf` Minecraft/NeoForge/Mojang imports | **0** (grep) |
| Headless coverage | CSV shape and comma-decimal locale safety, stationary de-dup, exact-replay drop, event classification, payload v3 round trip (reader and writer agree to the byte), serving cell kept in the cut, drive-test sample is a straight copy, pre-3a lens migration, lens network codec round trip, export file naming |

Not run: `runServer` / `runClient` (the brief forbids `runClient`, and nothing here needs a
headless server to prove). **Needs a human in game:** the trail drawing itself, pillars at a real
handover, the export command writing and opening the file, the portal behaviour, and the
regression check that meter HUD, link rays and the handover counter behave as before. The list is
in `PHASE_3.md`.

---

## Slice 0 gate fixes (s0-vision3a)

A gate review of slice 0 flagged one defect. I confirmed it against the code and fixed it.

### [major] A LINKS-only lens was evaluated but not sent the sample: false handover pillars

**Confirmed.** `SignalTicker.onServerTick` evaluated anyone holding a meter or wearing a lens with
the trail *or link rays* on. It sent the `SignalSamplePayload` only for the meter or the trail
(`sendSample = meter || trail`). `evaluate()` always stores the new handover state
(`RECEIVERS.put`), so on the LINKS preset the A3 state machine and the handover counter kept
running while the client's drive-test log saw nothing. `DriveTestLog.classify` compares each
sample only with the last one it logged, and a counter that went up means HANDOVER. So:

- Walk on ALL (the log's last entry has count N), press V to LINKS (the natural layer for watching
  the serving ray swap), and cross two cell boundaries (count N+2). Then, 300 blocks on, cycle to
  TRAIL or ALL, or take the meter out. The first logged sample is classified HANDOVER. That puts one
  white pillar and one `event=HANDOVER` CSV row at the switch point, and nothing at either real
  handover.
- An A→B→A pair on LINKS gives the same false HANDOVER, because the counter wins over the unchanged
  cell.
- Same class, not in the report: an outage and recovery while on LINKS showed up as a RESELECTION
  at the switch point.

This broke VISION_STEP3 3a's "Handovers and reselections are visibly marked where they happened".

**Fix. One rule: every evaluation is sent.**

- `SignalTicker.sendsSample(boolean meterHeld, LensSettings lens)` (package-private, pure) is the
  single gate: meter held, or the lens shows the trail, or the lens shows link rays. The ticker
  evaluates exactly those players.
- `evaluate()` no longer has a `sendSample` parameter. Both of its paths (a fresh evaluation and a
  cached replay) always send the sample, plus the link rays when they are wanted. There is no
  longer any way to evaluate someone without sending.
- **The evaluated set is unchanged:** meter, trail or links, as before. The only change is what an
  already-evaluated LINKS-only wearer receives.

**Deviation from the review's wording, and why.** The review offered `sendSample = meter ||
lens != null` "or simply always sending the payload built by an evaluation". Dropped into the old
loop literally, the first form widens the gate: ANTENNAS and COVERAGE wearers would start costing
an evaluation every interval. §3A.3 does not ask for that, and the invariant does not need it. A
player who is not evaluated has a frozen handover state: only `evaluate()` writes `RECEIVERS`, and
`CoverageSurvey` uses a throwaway `ReceiverState.NONE`. So the log has nothing to miss for them. I
built the second form. As the review also says, there is no client-side tick-gap rule: a cached
replay carries the tick of the evaluation it replays, so a genuine sample after a long stationary
replay would look like a gap.

### Cost (measured)

| | |
|---|---|
| Evaluations | **unchanged** (same players, same cache, same stagger) |
| New traffic | one `SignalSamplePayload` per interval (1 Hz by default) for a wearer showing link rays with neither the trail nor a meter |
| Payload body, encoded with the real codec | **425 bytes** with 4 cells carried, **176** with 1, **83** with no service. Add about 24 bytes of custom-payload framing (packet id plus `rancraft:signal_sample`), before the connection's compression. |
| For comparison | the same wearer already gets a `LensLinksPayload` every interval, about 2 KB at the default 8 links (per its javadoc) |

The review's "about 200 bytes" holds for one heard cell; four cells is about 0.45 kB. I measured
this with a throwaway JUnit probe on a realistic sample (negative coordinates, `band_1800` ids, game
time 1.2 M ticks) and deleted the probe before committing.

### Side effects, stated plainly

- **The log keeps recording while the lens shows only link rays**, and cycling to TRAIL draws those
  markers too. That matches slice 0's rule: every received sample is logged, and the trail layer
  only decides whether the log is drawn.
- **Meter HUD, one edge case.** A LINKS wearer who takes the meter out now sees a current reading
  at once, because the latest sample is at most one interval old. Before, the HUD showed whatever
  was last received (NO SERVICE if that was over 5 s old) until the next evaluation arrived. The
  values drawn are unchanged. Slice 4's "byte-identical meter" reference is therefore slice 0
  **plus** this fix.

### Honest-abstraction note (also at the code sites)

- **The log is only as complete as the evaluations.** A real UE measures all the time. In RANCraft,
  a receiver nobody is looking at (no meter, and the lens off or on ANTENNAS/COVERAGE) is not
  evaluated at all, like a phone that is switched off. When evaluation resumes, the first change of
  serving cell may be a reselection, or a handover on the first evaluation, at the resumption point.
  The log marks it there because that is where the server's state machine acted. The contract is now
  written on `DriveTestLog.classify` and `SignalTicker.sendsSample`.

### Spec vs tree, for slice 4 (recorded in `PHASE_3.md` follow-ups)

§3A.2 says the meter "sends SignalSamplePayload only when held". §3A.3 lets a device in the hotbar
trigger an evaluation ("so the Locator's emergency record keeps working in a pocket"). Together
they would bring this bug back. Take a player with a Locator in the pocket, no meter in hand and no
lens: they are evaluated and their counter moves, but no sample is sent. The false pillars then
appear once the meter or the trail comes back. Slice 4 must keep "evaluated implies sent": the
ticker sends the sample once per evaluation, whatever device or lens caused it. The "only when held"
part is already enforced where it matters, on the client: `SignalHudOverlay` draws only while a
meter is in hand. The cost is one sample per interval per evaluated player (numbers above).

### Found along the way, not fixed (pre-existing since Phase 2)

A handover candidate armed before evaluation pauses survives the pause. Evaluation pauses when the
meter is put away, or the lens is taken off or switched to ANTENNAS/COVERAGE. `CellSelector`
measures time-to-trigger as a tick difference. So if the same neighbour still qualifies when
evaluation resumes, the handover fires on the first evaluation, although the A3 condition was never
observed for the whole TTT. The log marks it correctly, where the server's counter moved, but the
handover itself may be early. I logged it in the `PHASE_3.md` follow-ups for the slice 4 ticker
refactor and left it alone here, because it is state-machine behaviour outside this fix.

### Tests

| | |
|---|---|
| `SignalTickerTest` (new, 6) | The rule for the meter (with no lens and with every preset), ALL, TRAIL, **LINKS**, ANTENNAS, COVERAGE and no lens. The band filter does not gate the sample. Hand-edited flag combinations follow the flags, not the preset name. Loads the ticker class headless; no game starts. |
| `DriveTestLogTest` (+1) | `unseenHandoversLandOnTheNextSample` shows the failure mode next to the fixed behaviour. Failure: a counter jump across unsent evaluations becomes one HANDOVER at the next sample. Fixed: each handover is marked where it fired, and nothing at the switch point. |
| `DriveTestLogTest.counterWinsEvenWithoutCellChange` | Comment made precise: a same-cell HANDOVER cannot happen while the log sees every evaluation. |

### Verification

| Check | Result |
|---|---|
| `./gradlew build` | succeeds |
| Unit tests | **210 passed, 0 failed** (203 + 6 `SignalTickerTest` + 1 `DriveTestLogTest`) |
| `rf` Minecraft/NeoForge/Mojang imports | **0** (grep) |

**Needs a human in game:** on the LINKS preset, walk across two cell boundaries (or A→B→A), then
cycle to TRAIL. There should be a white pillar at each handover point and none at the switch point,
and the exported CSV should agree. The check is listed in `PHASE_3.md`.

---

## Slice 1 — datapack folders (§0 known problem 3) and the purity assertion (§2)

### Known problem 3: confirmed at runtime, and fixed

The scout had already read vanilla's own 1.21.1 jar: it ships `data/minecraft/loot_table/`,
`recipe/`, `tags/block/` and `tags/item/`. RANCraft shipped the pre-1.21 plural names, which 1.21
neither reads nor warns about. The fix is three `git mv`s; the file contents are unchanged:

| Before (not read by 1.21) | After |
|---|---|
| `data/rancraft/loot_tables/blocks/signal_mast.json` | `data/rancraft/loot_table/blocks/signal_mast.json` |
| `data/rancraft/loot_tables/blocks/sector_antenna.json` | `data/rancraft/loot_table/blocks/sector_antenna.json` |
| `data/minecraft/tags/blocks/mineable/pickaxe.json` | `data/minecraft/tags/block/mineable/pickaxe.json` |

Nothing else in `src/main/resources/data` used an old name (`rancraft/rf/` is our own reload
listener's folder, which 1.21 did not rename). No world migration is needed: the fix is data only,
and placed blocks are unaffected. A mast or sector already broken in survival before this fix
dropped nothing, and that is not recoverable.

### Runtime check: `HarvestGameTests` (NeoForge GameTest)

`src/main/java/dev/rancraft/gametest/HarvestGameTests.java`, run by `./gradlew runGameTestServer`
(added to the command tables in `CLAUDE.md` and `README.md`). One generated test per entry in
`ModBlocks.BLOCKS`, so the blocks §3C.6 adds are covered with no edit. For each block, placed in an
empty 3x3x3 template, it checks what a survival break decides, and names every failed cause at once:

1. the block is in `#minecraft:mineable/pickaxe` (`BlockTags.MINEABLE_WITH_PICKAXE`);
2. an iron pickaxe mines it faster than a bare hand (`ItemStack.getDestroySpeed > 1`);
3. `BlockState.canHarvestBlock` is true for a survival player holding an iron pickaxe (both blocks
   are `requiresCorrectToolForDrops()`, so false means the loot table is never rolled);
4. `Block.getDrops(state, level, pos, blockEntity, miner, ironPickaxe)` is non-empty and contains the
   block's own item.

Before and after (both blocks, every run):

| Layout | Run | Game tests | Gradle |
|---|---|---|---|
| old (`loot_tables/`, `tags/blocks/`) | earlier interrupted attempt at this slice, 2026-09-28 02:26 | **2 of 2 failed** | not recorded |
| new | earlier attempt, 02:27 | **2 of 2 passed** | not recorded |
| new | this attempt | **2 of 2 passed** (920 ms) | `BUILD SUCCESSFUL`, exit 0, 27 s |
| old, files moved back by hand for the run, then restored (`git status` clean after) | this attempt | **2 of 2 failed** (805 ms) | `runGameTestServer FAILED`, "non-zero exit value 2", 25 s |
| new, after the restore, final tree | this attempt | **2 of 2 passed** (1.01 s) | `BUILD SUCCESSFUL`, exit 0, 25 s |

The earlier attempt's two runs are in `run/gameTestServer/logs/` (gitignored; the failing one is
`2026-09-28-1.log.gz`); the two runs of this attempt reproduced them. The failure on the old layout,
identical for `sector_antenna`:

> `harvestgametests.signal_mast failed ... rancraft:signal_mast: not in #minecraft:mineable/pickaxe
> (data/minecraft/tags/block/mineable/pickaxe.json); an iron pickaxe mines it at bare-hand speed
> (1.0); a survival player holding an iron pickaxe cannot harvest it, so breaking it drops nothing;
> loot table rancraft:blocks/signal_mast yields nothing (missing, or not under
> data/<ns>/loot_table/)`

So all four symptoms were real: no tag, no tool speed, no harvest, no loot table. What that meant in
survival, **computed** from `Block.getDestroyProgress` (progress per tick = dig speed / hardness /
(harvestable ? 30 : 100)) with hardness 3.0 and iron's tier speed 6.0, not timed in game:

| | Before | After |
|---|---|---|
| Iron pickaxe | 1.0 / 3.0 / 100 → 300 ticks = **15 s**, drops nothing | 6.0 / 3.0 / 30 → 15 ticks = **0.75 s**, drops itself |

`GameTestServer` exits with the number of failed required tests (`System.exit(failedRequiredCount)`,
checked in the decompiled source), which is why the Gradle task goes red.

**Kept as a permanent regression check** for §3C.6 (recipes, loot tables for every new block). It
is **not** part of `./gradlew build`: run `runGameTestServer` after touching `data/` or `ModBlocks`.

#### Harness notes and honest limits (also in the class javadoc)

- **Mock player, not a connected player.** The miner is vanilla's `GameTestHelper.makeMockPlayer
  (GameType.SURVIVAL)`. The test runs the two decisions `ServerPlayerGameMode.destroyBlock` makes
  (`canHarvestBlock`, then `playerDestroy` → `dropResources` → `getDrops`, checked in the patched
  source) instead of `destroyBlock` itself, which would need a fake network connection. Neither
  block overrides any drop or removal hook, so the loot table is the whole drop path today. A future
  block that overrides `playerWillDestroy` or `onDestroyedByPlayer` would need its own check.
- **A run with no tests passes.** `GameTestServer` reports "All 0 required tests passed" and exits
  0 if nothing registered. That happens if `neoforge.enabledGameTestNamespaces` is dropped from the
  `gameTestServer` run in `build.gradle`, or if the template id leaves the `rancraft` namespace:
  for generated tests, NeoForge's patch to `GameTestRegistry.register` keeps only those whose
  template namespace is enabled. When reading a green run, check for "2 tests are now running"
  (one per block).
- **Template.** Every game test is placed from a structure template, and NeoForge 21.1.251 ships no
  empty one (checked: nothing in the universal or sources jar). The mod carries
  `data/rancraft/structure/gametest/empty_3x3x3.nbt`: gzipped NBT, 87 bytes on disk, `DataVersion`
  3955 (1.21.1), `size` [3,3,3], empty `palette`, `blocks` and `entities`. Git stores it as binary,
  so `core.autocrlf` cannot corrupt it.
- **It ships in the jar**, as does the structure. Both are inert in production:
  `GameTestHooks.isGametestEnabled()` is false whenever `FMLLoader.isProduction()`, so NeoForge never
  registers the class outside a dev run.
- The spec asked for a survival check by hand ("survival world, iron pickaxe, break a mast"). The
  game test stands in for it headlessly. A quick look in game is still listed in `PHASE_3.md` as
  `[~]`: it also covers the client side (crack animation speed, the item entity popping out).

### Purity assertion: `PackagePurityTest`

**Spec vs tree** (recorded by the scout in `PHASE_3.md`): §2 says "extend the existing zero-import
grep assertion", but none existed; purity was only ever checked by hand with grep. This slice
creates it: `src/test/java/dev/rancraft/PackagePurityTest.java`, part of `./gradlew build`.

- **Scope.** Every `.java` file under `src/main/java/dev/rancraft/rf` and
  `src/main/java/dev/rancraft/util`. `util` does not exist until slice 6 (ColumnScan), so its test is
  a JUnit assumption that shows as **SKIPPED** until then and runs by itself once the folder
  appears.
- **What fails.** Any import (plain, static or wildcard) or fully qualified name of `net.minecraft`,
  `net.neoforged` or `com.mojang` in code or in a string literal (`Class.forName` is a dependency
  too). Whitespace around the dots is allowed for, as Java allows it. Comments are blanked first,
  so javadoc that mentions Minecraft in prose is fine. The failure lists `file:line: text` for every
  offending line.
- **Deviation, a tightening beyond the brief:** it also fails on any other `dev.rancraft` package
  (the root package included, e.g. `dev.rancraft.RanCraft`). `rf` and `util` may use each other.
  Every other package in the mod is game code, so importing one would bring Minecraft in one step
  removed. The literal rule would not see it, and neither would the unit tests: NeoForge is on the
  test classpath too, because `implementation` dependencies are. `rf` currently imports only
  `java.util`, `java.util.function` and `java.util.concurrent`, so nothing changed for it.
- **Paths.** The project root is the nearest directory at or above `user.dir` holding
  `src/main/java/dev/rancraft/RanCraft.java`. Gradle runs tests in the project directory, but an IDE
  may not. No root, or an `rf` with no `.java` files, fails rather than passing on nothing.
- **Scanner self-tests (7).** Imports, static imports, qualified names in code and reflective names
  in strings are caught; game-side `dev.rancraft` packages are caught and `rf`/`util` are not;
  comments, prose and look-alikes (`internet.minecraft`, `telecom.mojang`) are not; a string, char or
  text block that looks like a comment does not hide the code after it; line numbers survive a
  multi-line javadoc and CRLF; the root is found from a nested directory; no root fails loudly.
- **Proof that it bites on the real tree.** The earlier attempt added a throwaway
  `src/main/java/dev/rancraft/rf/PurityProbe.java` with one import and one fully qualified name. The
  build went red: `rfIsPure` reported "2 game reference(s) in a pure package (27 files scanned)" and
  listed both lines (the saved JUnit report). The probe was deleted. This attempt checked that no
  probe remains in `src/` (grep), that Gradle's next compile removed the stale
  `build/classes/.../rf/PurityProbe.class`, and that the rebuilt jar does not contain it. The
  message now says "forbidden reference(s)", since it covers project packages too.
- **Limit.** It reads source text, not bytecode. It cannot see a dependency that arrives through a
  JDK type (there are none in practice), and it does not check `src/test`, where `rf` tests may use
  test helpers freely.

### Verification

| Check | Result |
|---|---|
| `./gradlew build` | succeeds |
| Unit tests | **219: 218 passed, 0 failed, 1 skipped** (210 + 9 `PackagePurityTest`; the skip is `utilIsPure` until `util` exists) |
| `rf` references to Minecraft, NeoForge, Mojang or game-side `dev.rancraft` packages | **0** in 26 files (now asserted by `PackagePurityTest`, no longer grep) |
| `./gradlew runGameTestServer`, new layout | 2 of 2 passed, exit 0 (twice, the second on the final tree) |
| `./gradlew runGameTestServer`, old layout | 2 of 2 failed, exit 2 (see the table above) |
| Built jar | contains `loot_table/`, `tags/block/`, the template and `HarvestGameTests`; no `loot_tables/`, no `tags/blocks/`, no probe |

---

## Vision Step 3a follow-ups — trail cleared on logout, export link opens the folder

Two minor findings from the slice 0 gate, fixed together. Neither touches the server, the wire or a
save format, so no version moves (`PROTOCOL_VERSION` stays "4", `SignalSamplePayload.VERSION` 3).

### 1. The drive-test log is cleared on logging out

**The bug.** `ClientDriveTest` keyed its logs only by dimension and survived disconnect (slice 0's
"kept until clear" rule, above). The client cannot tell one world's `minecraft:overworld` from
another's, so after joining a different world the old trail was drawn at its old coordinates in the
new world, the new world's first sample was classified against the old world's last one (a
different serving cell, so a false RESELECTION), and the export wrote both worlds into one file.

**The fix.** `ClientEvents.onLoggingOut` now calls `ClientDriveTest.clear()`, next to the meter and
lens readouts it already clears. `onClone` (respawn, dimension change) still leaves the log alone,
so within a session the per-dimension split works as before: the Overworld trail is not drawn in
the Nether and is still there on return.

**When the event fires (checked in the patched sources).** `Minecraft.disconnect(Screen, boolean)`
calls `ClientHooks.firePlayerLogout`, which posts `ClientPlayerNetworkEvent.LoggingOut`. Every way
into a world goes through `disconnect()` first: `doWorldLoad` (singleplayer), and
`ConnectScreen.startConnecting` (a server, and a server transfer via `handleTransfer`). So each
session starts empty even if a previous one ended without a clean logout. `setLevel` (dimension
change) and respawn do not fire it.

**No stray sample after the clear (checked in the same sources).** The client handles a
`SignalSamplePayload` as a queued task: `ClientPayloadContext.enqueueWork` submits it to the
client's event loop. On every path out of a world, the channel is closed before
`Minecraft.disconnect` runs. The pause screen's quit calls `ClientLevel.disconnect`, which closes it
and waits (`channel.close().awaitUninterruptibly()`). A kick, a lost connection or a transfer reaches
`Minecraft.disconnect` from `onDisconnect`, after the channel has gone. `disconnect` then runs
`dropAllTasks()` before it posts `LoggingOut`. So nothing read from the old connection can run after
the clear and seed the next world's log.

**Residual limit (not fixed, logged in `PHASE_3.md`).** A proxy (Velocity, BungeeCord) that moves
a player to another backend server without a reconnect does not fire `LoggingOut`. A switch through
the configuration phase goes `handleConfigurationStart` → `Minecraft.clearClientLevel`, which posts no
logout, and a switch through a respawn packet looks like a dimension change. The trail then carries
over, as the meter and lens readouts already do. Vanilla and NeoForge do not reconfigure a
connection in normal play, so this needs a proxy. Possible fix: also clear on `LoggingIn`, which
`handleLogin` fires again after a reconfiguration. The respawn-packet case would still carry over.

**Trade-off, stated plainly.** Leaving a world now discards an unexported trail. Export first. The
README says so. Keying the log by server address or save name instead (the old follow-up's idea)
was not done: the client cannot name a singleplayer save reliably, and nothing in the design needs a
trail to outlive its session.

### 2. The export chat link opens the folder, not the CSV

**The problem.** The link used `ClickEvent.Action.OPEN_FILE` on the CSV itself. On Windows that hands
the file to its default app, usually Excel. Excel on a comma-decimal locale (the user's `es-PE`)
opened by double-click splits on `;` and reads `.` as a thousands separator, so `-82.4` can become
`-824`. That is exactly what the grey hint printed next to the link warns against.

**The fix.** The link text is still the file name. The `ClickEvent` value is the file's folder
(`<game dir>/rancraft/drivetests`), absolute and normalised, as vanilla does for profiler results
(`Minecraft.debugClientMetricsStart`: `OPEN_FILE` on `path.toFile().getParent()`). The folder opens
in Explorer with the new file in it, and the hint says how to import it. `Util.OS.openFile(File)` is
`openUri(file.toURI())`, which on Windows runs `rundll32 url.dll,FileProtocolHandler <uri>`, the same
path vanilla's profiler link takes. The CSV itself is unchanged (still RFC 4180, `.` decimals).

### Tests

| | |
|---|---|
| `ClientDriveTestTest` (new, 4) | Two dimensions keep separate logs in visit order; `ClientEvents.onClone` keeps both; `ClientEvents.onLoggingOut` empties every dimension; after a logout the next world's first Overworld sample starts a one-entry log with event `NONE`. Before the fix, that sample would have been classified RESELECTION against the old world. Drives the real handlers with hand-built events (null player and connection, which NeoForge itself passes when a world is being created); no client starts. |
| `DriveTestCommandsTest.linkOpensTheFolder` (new) | The link shows the file name, is underlined, and its `OPEN_FILE` value is the absolute, normalised folder (built from a relative `./rancraft/drivetests/...` path), never the `.csv`. |
| Seam | `ClientDriveTest.record(ResourceKey<Level>, DriveTestLog.Sample)` (package-private) is what `accept` calls after reading the client's level, so the tests can feed the log without a running client. `DriveTestCommands.fileLink` went from private to package-private. |

**The tests bite.** With `ClientDriveTest.clear()` taken out of `onLoggingOut` and the link pointed
back at the file (both temporarily, then restored), `logoutClearsEveryDimension`,
`nextWorldDoesNotInheritTheOldTrail` and `linkOpensTheFolder` failed (3 of 8 in the two classes).
With the fix, all pass.

### APIs verified against sources (new to this codebase)

`ClientPlayerNetworkEvent.LoggingOut(MultiPlayerGameMode, LocalPlayer, Connection)` and
`Clone(MultiPlayerGameMode, LocalPlayer, LocalPlayer, Connection)` constructors (NeoForge sources
jar; `LoggingOut` documents its nullable arguments); where `LoggingOut` is fired (`Minecraft.disconnect`,
above); `ClickEvent.getAction()` / `getValue()`, `Style.getClickEvent()` / `isUnderlined()`;
`ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace(...))` (the tests'
dimension keys, built the way `Level.OVERWORLD` is).

### Verification

| Check | Result |
|---|---|
| `./gradlew build` | succeeds |
| Unit tests | **224: 223 passed, 0 failed, 1 skipped** (219 + 4 `ClientDriveTestTest` + 1 `DriveTestCommandsTest`; the skip is still `utilIsPure`) |
| `rf` purity | unchanged, asserted by `PackagePurityTest` (no `rf` file touched) |

**Needs a human in game** (in `PHASE_3.md`): export, click the file name, and check that Explorer
opens the `drivetests` folder rather than Excel opening the CSV. Walk in world A, quit to the title
screen, join world B with the lens on TRAIL: no markers from A, and an export holds only B's rows.

---

## Slice 2 — `DeviceRequirement` (§3A.1)

### What was built

`rf/DeviceRequirement` exactly as the spec's signature: `record DeviceRequirement(ServiceLevel
minServiceLevel, int minCapacityTier)`, `NONE = (ServiceLevel.NONE, 0)`,
`Verdict check(SignalSample, BandTable)`, `enum Verdict { OK, NO_SERVICE, LOW_QUALITY, LOW_TIER }`.
It is the first reader of `Band.capacityTier`. Pure `rf`, no new imports beyond `java.util.Objects`.

Check order, first failing reason wins:

1. `NO_SERVICE` if `sample.isNoService()` (empty list, serving id not in the list, or level NONE).
2. `LOW_QUALITY` if `!sample.serviceLevel().atLeast(minServiceLevel)`.
3. `LOW_TIER` if the **serving** cell's band (`sample.serving()`, never `cells.get(0)`) has
   `capacityTier < minCapacityTier`.

### Decisions, stated plainly

- **`NONE` still reports `NO_SERVICE`.** The spec lists `NO_SERVICE: sample.isNoService()` with no
  condition, so it is checked first for every requirement. The verdict describes the link; a device
  with no requirement (the meter, the Network Locator) keeps working and shows its own degraded
  state. A device that wants "always works" ignores the verdict rather than getting `OK`.
- **Unknown band id → fallback band's tier** (`BandTable.getOrFallback`), which is what the engine
  already propagated that cell with. Removing a band from the datapack therefore judges its cells as
  `band_900` (tier 1), not as tier 0 or an error.
- The compact constructor rejects a null level. A negative `minCapacityTier` is allowed and means
  the same as 0 (every band passes).

### Tests (`DeviceRequirementTest`, 7) — spec test 1

Every verdict on hand-built samples (three kinds of no-service sample; quality before tier; tier at
and below the minimum; OK at exactly the minimum level and tier). **Serving vs strongest:** a sample
whose strongest cell is `band_3500` but whose serving cell (held by hysteresis) is `band_900` gives
`LOW_TIER` for `(GOOD, 3)`; the mirror case (strongest `band_900`, serving `band_3500`) gives `OK`.
Reading index 0 would have inverted both. The fixture asserts which cell is strongest and which is
serving, so the test cannot pass by accident.

### Verification

| Check | Result |
|---|---|
| `./gradlew build` | succeeds |
| Unit tests | **231: 230 passed, 0 failed, 1 skipped** (224 + 7 `DeviceRequirementTest`; the skip is still `utilIsPure`) |
| `rf` purity | asserted by `PackagePurityTest` (passes with the new file) |

Nothing to check in game: nothing calls `check` until slice 4 (SignalDevice) and later devices.

---

## Slice 3 — `Ranging` + `LocatorSolver` (§3A.4, §3A.5)

All pure `rf`, headless. Nothing in game calls it yet; the Locator item is slice 5.

### What was built

| Piece | Where | Notes |
|---|---|---|
| `Band.bandwidthMhz` | `rf/Band` (appended 7th component) | Default `DEFAULT_BANDWIDTH_MHZ = 10.0`. The six-argument constructor is kept and defaults it, so every existing call site compiles and means what it did. `DEFAULT_900` is 10 MHz, matching `band_900.json`. |
| Band JSON | `data/rancraft/rf/bands/*.json` | `"bandwidth_mhz"`: band_700 10, band_900 10, band_1800 20, band_3500 100. |
| Loader | `data/RfDataLoader.parseBandwidthMhz` | Absent → 10.0. Present but not a positive finite number → 10.0 with a warning, rather than dropping the band. Checked at runtime: `runGameTestServer` logs "loaded 4 band(s)" and no parse error. |
| `RangeMeasurement` | `rf/` (new record) | cell id, radiating centre x/y/z (voxel + 0.5), measured slant range, sigma, band id, obstruction dB. No true distance and no receiver position: the solver cannot cheat. |
| `Ranging` | `rf/` (new) | `resolutionMeters = 299.792458 / bandwidthMhz`, `resolutionBlocks = ... / metersPerBlock`, `quantise` (round half up, as `Math.round`), `nlosBiasBlocks = rate * obstructionDb`, `sigmaBlocks = res / sqrt(12)`, `usable = rsrp >= locatorMinRsrpDbm`, and `measure(cells, bands, metersPerBlock \| RfConfig, params)`, which keeps the usable cells in input order. |
| `LocatorParams` | `rf/` (new record) | `minRsrpDbm, maxCells, maxHdop, nlosBiasBlocksPerDb`, `DEFAULTS` (-100, 8, 6.0, 0.25). |
| `LocatorFix` | `rf/` (sealed interface) | `NoSignal`, `RangeOnly(cx, cz, radius)`, `Ambiguous(ax, az, bx, bz, likely)`, `PoorGeometry(hdop, cellsUsed)`, `Fix(x, y, z, hdop, errorBlocks, cellsUsed)`: exactly the spec's shapes. |
| `LocatorSolver.solve(ranges, ground, previous, params)` | `rf/` (new) | §3A.5, with the one deviation below (extra starts). |
| Config | `RanCraftConfig` + `RfConfig` | See "Where the tunables live". |

### Where the tunables live (§5 decision)

All five are in `RanCraftConfig` (COMMON) with accessors: `locatorMinRsrpDbm` (-100, range -140..-40),
`locatorMaxCells` (8, range 3..8), `locatorMaxHdop` (6.0, range 1..50), `nlosBiasBlocksPerDb` (0.25,
range 0..4), `locatorEmergencyMaxAgeTicks` (1200, range 0..72000).

**Four of them are also appended to `RfConfig`** (`locatorMinRsrpDbm, locatorMaxCells,
locatorMaxHdop, nlosBiasBlocksPerDb`, plus `RfConfig.locatorParams()`, like `sinrParams()`), and
`RanCraftConfig.snapshot()` fills them. Reasons: `rf` code reads them (`Ranging`, `LocatorSolver`);
`RfConfig`'s own rule is "exactly one config object crossing the boundary into `rf`"; and §3A.2's
`DeviceContext` already carries an `RfConfig`, so the Locator reaches them without touching the mod
config. **`locatorEmergencyMaxAgeTicks` stays out of `RfConfig`**: it is gameplay for the game-side
emergency record, no `rf` code reads it, and §5 says gameplay values stay out, as the lens settings
do. The three `new RfConfig(...)` call sites (`DEFAULTS`, `snapshot()`, one test helper) were
updated; no legacy constructor was added for `RfConfig`, so a copy can never silently reset the
locator values to defaults.

`locatorMaxCells` is capped at 8 because §3A.6's payload carries at most 8 rings, one per
contributing cell (the lens precedent: config upper bounds are the wire caps). Slice 5 should point
the cap at its payload constant. Its minimum is 3, because fewer can never give a FIX.

### Deviations and decisions

1. **Spec vs tree: `floor`, not `round`, for the altitude-aiding column.** §3A.5 writes
   `ground.surfaceY(round(x), round(z))`. Everywhere else in the tree the column a point is in is
   `floor` (a player at x = 10.7 stands in column 10; `BlockPos.containing` floors). `round` reads
   the next column for half of all positions, which on a slope is a wrong height and a wrong
   horizontal range. The solver uses `floor`. `LocatorSolverTest.perfectRangesOnASlope` (a slope of
   one block per column, receiver at x = 10.7) passes with `floor` and **fails with `round`**
   (checked by swapping it in temporarily).
2. **Deviation from §3A.5's algorithm: extra Gauss-Newton starts.** Measured before the change,
   with the spec's centroid-only start, over 20,000 seeded scenes per row (3-6 cells within ±40,
   ±120 or ±300 blocks, receiver anywhere within ±400, flat ground, default params):

   | Ranges | FIX results that were more than 3 x "±" + 2 blocks from the truth, centroid only | after the change |
   |---|---|---|
   | perfect (resolution → 0) | **10.5 % – 16.7 %** of fixes, worst 1,966 blocks | **0 %** |
   | band_900 (30 m) | 10.8 % – 15.9 % | 1.3 % – 5.2 % |
   | band_1800 (15 m) | 10.5 % – 15.7 % | 0.7 % – 2.1 % |
   | band_3500 (3 m) | 10.5 % – 15.6 % | 0.1 % – 0.3 % |

   Cause: once the receiver is outside the cells' footprint, Gauss-Newton from the centroid can
   settle in a wrong local minimum. With perfect ranges it then reports FIX "± 0" hundreds of blocks
   away (e.g. cells at (-17,-17), (17,-17), (0,17), (40,40), receiver at (-100, 200): FIX at
   (234, -7), HDOP 4.3). The HDOP gate does not catch it. That teaches the wrong thing about the
   "±". Change: the centroid stays the first start (§3A.5), and the same fit (same weights, same
   15-iteration cap, same 0.01 stop, altitude re-derived each iteration) is also started from the
   two circle crossings of each pair among the 3 strongest cells (at most 6 extra runs; with exact
   ranges one crossing *is* the answer). A run replaces the centroid's result only if it ends more
   than 1 block away and has a smaller weighted residual `sum w_i (rho_i - d_i)^2`. **When the
   centroid's run is the best fit, the result is exactly the spec's.** Exactly collinear cells (the
   centroid run is singular at its first step) stay PoorGeometry without trying other starts, since
   the two mirror images fit equally well. All pairs instead of the strongest 3 gave the same rates
   (checked), so 3 it is. `notTrappedOutsideTheFootprint` pins the example above and **fails with
   the extra starts switched off** (checked). The remaining band_900 misses are measurement
   ambiguity (a mirror position that genuinely fits the coarse ranges better), which no start can
   fix; see honest limits. Reverting is deleting one loop in `leastSquares`. Recorded in
   `PHASE_3.md` follow-ups for a decision. **Resolved: kept by the project owner** (see "Owner
   decisions on the slice 3 follow-ups" at the end of Phase 3).
3. **Spec conflict: test 10 vs the `errorBlocks` formula.** `errorBlocks = HDOP x rms(sigma_i)` is
   implemented to the letter. With it, **one band_3500 cell cannot cut the "±" 3x on bandwidth
   alone**: in a good 120° triangle it improves 9.99 → 7.77 (1.29x; the same site on band_900 gives
   8.93, 1.12x). The rms of the sigmas stays dominated by the three 8.66-block band_900 sigmas, and
   one range constrains only one direction. The weighted covariance `sqrt(trace((H^T W H)^-1))`
   would not reach 3x either (1.40x, computed). So test 10 uses a geometry where it holds and says
   so: **the edge of a network**, three band_900 sites clustered to the west (a 20° wedge, HDOP 4.10,
   ± 35.49) and a band_3500 site added 60 blocks north: ± 8.50 (**4.17x**). Most of that is geometry:
   the same site on band_900 gives ± 9.81 (3.62x), and the test asserts band_3500 beats it by exactly
   the rms ratio (1.15x). A second test (`oneWidebandCellInAGoodTriangle`) pins the 1.29x. **Open
   question for the spec owner** (in `PHASE_3.md` follow-ups): the weighted covariance equals
   `HDOP x sigma` for a single-band fix (so tests 7 and every single-band number are unchanged) but
   credits the weighting that makes band_3500 "dominate the fit": edge case 35.7 → 5.1 (7.0x, band_900
   control 3.6x), triangle 1.40x. It would make "Adding a band_3500 site nearby visibly shrinks the
   ±" much more visible. Not changed here, because §3A.5 gives the formula explicitly.
   **Resolved: the project owner switched `errorBlocks` to the weighted covariance** (measured:
   edge 35.49 → 5.12, 6.93x; triangle 9.99 → 7.15, 1.40x). See "Owner decisions on the slice 3
   follow-ups" at the end of Phase 3; the numbers in this item and in "Measured" below are the
   slice 3 (`HDOP x rms`) ones.
4. **`RangeOnly.radius` is the measured slant (3D) range**, exactly as measured (test 9 says "the
   measured radius"). A horizontal ring of that radius is the widest circle of the range sphere; a
   receiver below the radiating point is horizontally a little closer. A single cell gives no
   position to altitude-aid from. Slice 5 decides how to draw it.
5. **`Ambiguous.likely` is -1 (`NO_PREFERENCE`) with no previous estimate**, or when the previous
   one is exactly as far from both. §3A.5 only says "nearer `previous` if there is one"; picking 0
   would claim a preference the Locator does not have. A previous `Fix` counts by its (x, z), a
   previous `Ambiguous` by its likely candidate; `RangeOnly`, `PoorGeometry`, `NoSignal` and null do
   not count. Candidate `a` is on the `(-dz, dx)` side of first → second (standing on the first cell
   facing the second, `a` is on the right).
6. **Two cells: each candidate is refined on its own ground.** The circles are first crossed at the
   height under the midpoint, then each candidate follows the crossing nearest to it while the
   height is re-derived under it (same 15 / 0.01 rule). The two candidates sit on different ground
   (`twoCellsRefineAltitudePerCandidate`: a 16-block terrace, the true candidate exact to 0.01).
7. **`PoorGeometry.hdop` is capped at `HDOP_CEILING = 99.9`**, and a singular geometry reports the
   ceiling, because test 12 forbids an infinity. Read 99.9 as "99.9 or worse".
8. **`Fix.y` is the assumed eye height** (ground under the estimate + 1.62), the convention of the
   drive-test log and the sample payload. **An unloaded column** keeps the last height the solver
   knew; before any ground is read that is the previous fix's `y`, else the lowest radiating point
   among the cells. It never loads a chunk. After one step the estimate is normally on loaded
   ground again (`unloadedColumnsFallBack`: the centroid is unloaded, the result is still exact).
9. **Which cells:** the first `maxCells` usable ones in the order given. `Ranging` keeps the
   engine's order, RSRP descending, so the strongest are kept (the weakest are the likeliest to be
   NLOS-biased), not the ones with the smallest sigma.
10. **Robustness beyond the spec:** measurements with a non-finite or absurd (> 10^9) coordinate,
    range or sigma are ignored; a sigma of 0 is floored at 10^-6 (a finite weight); a cell closer
    than 10^-6 blocks horizontally adds no geometry row (the direction is 0/0);
    `det(H^T W H)` is computed pairwise (Cauchy-Binet), which stays exact when one weight is orders of
    magnitude above the rest; singularity is judged on the unweighted `H^T H`, relative to its
    trace (`det <= 1e-12 trace^2`); an estimate that runs past 10^9 blocks is a failed run.
11. `Band` is not on the wire (the client never receives the band table), so appending
    `bandwidthMhz` needs no protocol bump. `ModPayloads.PROTOCOL_VERSION` stays "4".

### Honest-abstraction notes (also at the code sites)

- **"Network Locator", not GPS** (`Ranging` javadoc): cellular positioning, the family of E-CID,
  OTDOA and NR multi-RTT.
- **Timing resolution c / BW** is real in kind: a receiver times an arrival to about one sample.
  Real receivers interpolate below that; this model does not.
- **Quantisation is deterministic.** The same true distance always rounds the same way, so a player
  standing still sees a fixed error, not noise that averages out. The reported sigma is the spread
  of that rounding error over positions, not over time.
- **NLOS bias is a flat stand-in** (`Ranging.nlosBiasBlocks`): blocks per dB of obstruction instead
  of a longer reflected first path. Directionally right (ranges read long behind terrain, the fix
  moves away from the hidden cell: test 11).
- **Absolute ranging, not time differences** (`Ranging`, `LocatorSolver`): like multi-RTT, with no
  clock error and no dedicated positioning reference signals.
- **"±" is a reported uncertainty, not a guarantee** (`LocatorFix.Fix.errorBlocks`): quantisation
  only. The NLOS bias is systematic and not in it (test 11 asserts the ± does not move when the
  bias does). Nor does it cover a mirror ambiguity (above). *(Since the owner decisions it is the
  1-sigma horizontal uncertainty of the weighted fit; still quantisation only.)*
- **Altitude aiding assumes the top surface** (`LocatorSolver`, `SurfaceProbe`): wrong in caves,
  under overhangs, while flying or on a tower. The reported `y` is that assumption.
- **Towers in a line** (`LocatorSolver`): exactly collinear towers are PoorGeometry (singular at the
  centroid; the mirror images fit equally). *Nearly* collinear towers can converge on either side
  of the line; the HDOP there describes local precision, not that ambiguity. *(Measured in the
  Phase 3A summary: with the middle of three band_900 masts 10 blocks off the line, 32 % of FIX
  results land on the wrong side with a normal-looking ±. Open follow-up; labelled at the code site
  since then.)*

### Measured

| | |
|---|---|
| Resolution | band_700/900 29.98 m, band_1800 14.99 m, band_3500 2.998 m (table: 30 / 30 / 15 / 3) |
| Sigma | 8.654 / 8.654 / 4.327 / 0.865 blocks at 1 m per block |
| Test 7 | HDOP 1.1547 at the true point (exact geometry); 1.1547 with band_1800 quantisation on the grid, ± 4.997 |
| Test 10 | 35.49 → 8.50 (4.17x) adding band_3500; 9.81 (3.62x) adding band_900 in the same spot. *Weighted "±" (owner decision): 35.49 → 5.12 (6.93x); the band_900 control unchanged.* |
| Good triangle + one band_3500 | 9.99 → 7.77 (1.29x); + band_900 8.93 (1.12x). *Weighted "±": 9.99 → 7.15 (1.40x); control unchanged.* |
| Narrow 6° wedge at 300 blocks | HDOP 13.29 → PoorGeometry at the default limit 6.0 |
| Cost | 18.8 µs per 8-cell mixed-band solve (7 Gauss-Newton runs, synthetic hilly `SurfaceProbe`), 0.38 µs to range 8 cells; JIT-warm, 200,000 repetitions, this PC. The in-game `SurfaceProbe` cost is not in this; slice 5 must measure it (up to 7 x 15 lookups per fix in the worst case; *corrected in the Phase 3A summary: 7 x 16 + 1 = 113, since each run also reads the ground at its end point and the extra starts once under the centroid*). |

### Tests

| | |
|---|---|
| `RangingTest` (9) | Test 2 (reads `bandwidth_mhz` from the four shipped JSON files and checks c / BW against the table), test 3 (44 → 30, 46 → 60, also through `measure` on an exact 30-block band), test 4 (0 at 0 dB, exactly 0.25 x dB, bias not in sigma), `Band` six-argument default, bad bandwidth/scale rejected, usable threshold at exactly -100, order kept, unknown band ranged as the fallback, the `RfConfig` form, non-finite inputs dropped. |
| `LocatorSolverTest` (19) | Tests 5 (flat, on a slope, unloaded centroid), 6 (three layouts x three receiver positions, off and on the line), HDOP threshold both ways, 7 (exact and quantised), 8 (likely from a Fix, from an Ambiguous, none, tie; refined per candidate on terraced ground; no crossing / nested / co-sited → RangeOnly on the nearer), 9 (+ NoSignal), `maxCells`, 10 (+ control) and its limit, 11, 12 (under a tower on four bands with 4/3/2/1 cells; estimate bit-exactly on a tower; degenerate inputs x 4 previous states x 2 grounds; 5,000 seeded random scenes), and the local-minimum case. |

**The tests bite** (each checked by breaking the code temporarily, then restoring it):
`round` instead of `floor` fails `perfectRangesOnASlope`; switching the extra starts off fails
`notTrappedOutsideTheFootprint` (and nothing else: tests 5-12 pass on the spec's centroid-only
algorithm too); removing the zero-row guard fails `estimateExactlyOnATower` (the random sweep alone
did not: an estimate lands bit-exactly on a tower only by symmetry, which that test builds).

### Verification

| Check | Result |
|---|---|
| `./gradlew build` | succeeds |
| Unit tests | **259: 258 passed, 0 failed, 1 skipped** (231 + 9 `RangingTest` + 19 `LocatorSolverTest`; the skip is still `utilIsPure`) |
| `rf` purity | asserted by `PackagePurityTest` (5 new `rf` files, `java.util` only) |
| `./gradlew runGameTestServer` | 2 of 2 passed; the log shows "RANCraft loaded 4 band(s)" and no parse error with the new `bandwidth_mhz` |
| Existing config file | that run's `run/gameTestServer/config/rancraft-common.toml` predates slice 3; NeoForge corrected it in place with the five new entries at their defaults (-100.0, 8, 6.0, 0.25, 1200) and kept the old file as `rancraft-common-1.toml.bak`. An upgraded server gets the same, with nothing to edit by hand. |

Nothing to check in game yet: the Locator item, payload and HUD are slice 5, which is where the
3A done-when items get their manual checks.

---

## Slice 4 — SignalDevice framework, ticker refactor, meter port (§3A.2, §3A.3)

Three commits: part 1 `a51e13a` (stale armed candidate), part 2 `85bd051` (framework, refactor,
meter port, regression tests), and the slice commit `6558460` (these notes and the tracker).

### What was built

| Piece | Where | Notes |
|---|---|---|
| `SignalDevice` | `device/` | Exactly §3A.2: `requirement(ItemStack)`, `onSample(ServerPlayer, ItemStack, boolean held, DeviceContext)`. |
| `DeviceContext` | `device/` | Exactly §3A.2: `(sample, verdict, bands, config, level, tick)`. `tick` is the dispatch's game time; `sample.timestampTick()` is the evaluation's. |
| `DeviceMemory<V>` | `device/` | Per-player device state (the Locator's last fix, a replay guard, ...). Every instance registers itself, and `SignalTicker.forget` / server stop clear them all. This is §3A.3's "add per-device server state to forget()". |
| `ReplayGuard` | `device/` | Helper for "devices must be idempotent on `sample.timestampTick()`": `firstSighting(player, sample)` is true once per evaluation, false for a cached replay of it. |
| `carried(ServerPlayer)` + `CarriedDevice` | `world/SignalTicker` | Replaces `holdsMeter()`. Main hand, offhand, hotbar 0-8; `held` = a hand. |
| `dispatch(...)` | `world/SignalTicker` | The one sample to every carried device, each with `requirement(stack).check(sample, bands)`. After the send, on fresh evaluations **and** cached replays. |
| `sendsSample(List<CarriedDevice>, LensSettings)` | `world/SignalTicker` | Was `(boolean meterHeld, LensSettings)`. Any carried device, or a lens showing the trail or links. |
| Stale candidate drop | `rf/CellSelector.expireStaleCandidate`, `rf/ReceiverStateStore` (tick per state, `resume`), `SignalTicker.staleCandidateGapTicks` | Part 1; below. |
| Meter port | `item/FieldTestMeterItem` | `implements SignalDevice`, requirement `NONE`, empty `onSample` (see deviation 1). |
| Pure helpers | `SignalTicker.isDue`, `linkLensOf`, `linkCap`, `canReplay`, `scanCarried`, `toPayload` | Extracted **unchanged** from the ticker so the regression gate can pin them headless. `Cached` is package-private for the same reason. |

### Part 1: the stale armed handover candidate (follow-up from the slice 0 gate, fixed)

**Defect (pre-existing since Phase 2).** Only `evaluate()` writes a player's `ReceiverState`. When a
player stops being evaluated (meter put away, lens taken off or switched to ANTENNAS/COVERAGE), the
state freezes, including an armed candidate and its `candidateSinceTick`. `CellSelector` measures
time-to-trigger as `tick - candidateSinceTick`, so on the first evaluation after the pause it saw a
huge held time and handed over at once if the same neighbour still qualified. The A3 condition was
never observed for the TTT.

**Fix.** `ReceiverStateStore` now remembers the tick of the evaluation that produced each state
(`put(key, state, tick)`; `select` records it). `evaluate()` reads the previous state through
`resume(key, now, maxGap)`, which calls `CellSelector.expireStaleCandidate`: if a candidate is armed
and `now - lastEvaluated > maxGap`, the candidate is dropped (serving cell, serving-since and the
handover tally are kept: a pause is not an outage and not a handover). If the neighbour still
qualifies, that evaluation re-arms it, and the handover fires one full TTT after evaluation resumed,
as if the player had just walked into the boundary.

**Threshold: exactly one evaluation interval (`staleCandidateGapTicks(interval) = max(1,
interval)`), config-free.** Why that is safe with no margin:

- The stagger (`isDue`) makes each player due exactly once every `interval` server ticks (pinned).
- While a candidate is armed the cache is skipped, so every due tick is a fresh evaluation that
  records its game time. Game time moves by at most one per server tick (not at all under
  `/tick freeze`), and every dimension reads the overworld's clock (checked in `MinecraftServer
  .tickServer`, `ServerLevel.tick`/`tickTime`). So a continuously observed player's gap is at most
  one interval; anything longer means a scheduled evaluation did not happen.
- A margin brings the bug back for short pauses: at the defaults (interval 20, TTT 40) a candidate
  armed at T with one evaluation skipped already reaches TTT at T + 40, observed only at T.

**One harmless exception, found while writing the tests (the part 1 javadoc had it wrong): a live
change of `evaluationIntervalTicks`.** The stagger re-phases, so the first gap after the change can
be up to `old + new - gcd(old, new)` ticks (20 → 10: 20; 10 → 20: 20; 7 → 3: 9), pinned exhaustively
over every id and switch-tick residue. When that exceeds the new interval, an armed candidate is
dropped once and re-armed: the handover is delayed by at most one TTT, never made early. Not worth
remembering the previous interval for an admin action. Javadoc corrected.

Tests (`CellSelectorTest`, 5): continuous evaluation still fires at exactly TTT; a pause re-arms at
resumption and fires one TTT later (next to the frozen-state control that fires at once); a pause
after which the neighbour no longer qualifies leaves nothing armed; the boundary (gap = interval
kept, +1 dropped, clock backwards dropped, never-evaluated kept); the store records and forgets the
tick. `SignalTickerTest`: threshold = interval, stagger spacing, the live-change bound.

### Deviations and decisions

1. **The meter's `onSample` sends nothing (spec: "sends SignalSamplePayload only when held").**
   Since the slice 0 gate fix the rule is "every evaluation is sent" (`sendsSample`): the client's
   drive-test log reads handovers off consecutive samples, so an evaluation it never saw puts a
   false HANDOVER pillar at the next one. A device in the hotbar now triggers evaluations, so a
   send-only-when-held meter would bring that bug back for anyone carrying the Locator in a pocket.
   The ticker sends the sample once per evaluation, whoever asked; "only when held" is enforced where
   it shows, in `SignalHudOverlay` (client, unchanged: it draws only while a meter is in a hand).
   Recorded as a spec-vs-tree follow-up since slice 0; resolved as that follow-up proposed.
2. **A meter only in the hotbar now makes its carrier evaluated.** That is §3A.3 read literally ("a
   player is evaluated if they carry any device"; the meter is a device). Observable: such a player
   costs one evaluation per interval (the same single evaluation, cache and stagger as anyone else),
   is sent the sample (83-425 bytes, measured in the slice 0 gate notes), and their handover counter
   keeps counting while the meter is pocketed. Taking the meter from the hotbar into a hand shows a
   current reading at once, where before it showed NO SERVICE (last sample over 5 s old) until the
   next evaluation, up to one interval. The values drawn are unchanged. It is the only difference the
   differential check (below) finds in who is evaluated.
3. **Per-device state is a registry (`DeviceMemory`), not a map per device.** §3A.3 says to add it
   to `forget()` but not how. A registry makes it impossible for a device to leak per-player state
   past logout, or to carry an Overworld "previous fix" into the Nether or past a respawn.
   `DeviceMemory` keys by player UUID only; fixed receivers (§3B.3) have a block lifecycle and need
   their own.
4. **`ReplayGuard` is per player, not per stack,** and keyed on `sample.timestampTick()` as §3A.3
   says. A player carrying two of the same device acts once per evaluation, on the first dispatched
   (main hand, offhand, hotbar left to right). Per-stack state belongs in a data component.
5. **`/tick freeze` (found while reviewing the draft `ReplayGuard` javadoc, which claimed two
   evaluations never share a tick; corrected before part 2 was committed).** The stagger runs on the server tick count, which keeps counting while frozen;
   game time does not (`ServerLevel.tick` calls `tickTime()` only when `runsNormally()`), and
   players still move (`TickRateManager.isEntityFrozen` exempts them). So while frozen, successive
   fresh evaluations carry the same `timestampTick` and a tick-keyed guard treats all but the first
   as replays: a device's once-per-evaluation state freezes with game time, as the handover timers
   already do. Output built from `ctx.sample()` on every dispatch still follows the player. Kept the
   spec's key; javadoc corrected in `ReplayGuard`, `SignalDevice`, `DeviceContext`, `dispatch`;
   pinned by `DeviceMemoryTest.frozenGameTimeLooksLikeAReplay`. Slice 5 note in `PHASE_3.md`.
6. **The cache entry keeps the `SignalSample`** (full cell list, up to `maxCellsEvaluated`, default
   12 `CellSample` records per player), so a replay can be dispatched with more than the payload's
   four cells. Server memory only, never sent; dropped by `forget()` like the rest of the entry.
7. **`toPayload` takes the band table** the evaluation used instead of reading
   `RfDataLoader.bands()` a second time, and lost its unused `EvaluationStats` parameter. Same table
   in practice (one volatile read vs two, on the server thread); proven byte-identical below.
8. **Dispatch runs after the send,** on both paths, so a device's own payload (the Locator's) follows
   the sample it was computed from. On a replay the verdict is checked against the band table of
   now; the cache is not keyed on the band table (pre-existing: a datapack reload does not invalidate
   cached samples either).
9. **A device that throws propagates** like any other ticker bug; no per-device try/catch that would
   hide it.

### Regression gate (§3A.3): method, checklist, result

**Method.**

1. The checklist below was written from the source of `bd996d6` (`git show`), the last commit
   before any slice 4 edit, branch by branch: who is evaluated, what is sent, when the cache
   replays, what `forget()` clears.
2. **Differential run against the old code.** `bd996d6`'s `SignalTicker` was copied verbatim into
   the test source set under another name (not subscribed to any bus), and a throwaway JUnit test
   ran its `toPayload` (private, by reflection), `sendsSample` and `chooseLinks` side by side with
   the refactored ones. Both files were deleted before committing. Results:
   - the three hex fixtures stored in `SignalTickerPayloadTest` are exactly what the old code
     produces (3/3), so that test pins the refactored meter payload against the real `bd996d6`;
   - **20,000 seeded random samples encode byte-identically** with the real `STREAM_CODEC` and are
     record-equal (14,171 served, 1,705 with a PCI conflict note, 5,829 without service; 0-8
     candidates; four band ids including one the table does not know; interference sometimes
     -Infinity);
   - `sendsSample`: identical in all 49 lens states (no lens, and 16 flag combinations x 3 band
     filters) with the meter in a hand or with nothing; with a device only in the hotbar, 13 of the
     49 states are newly evaluated (no lens, or a lens without trail and links). That is deviation 2
     and nothing else;
   - `chooseLinks` (the lens link cut): identical on 5,000 random samples and caps.
3. Walked the checklist against the refactored code (the `bd996d6..HEAD` diff of `SignalTicker`).
4. Extracted the remaining inline decisions unchanged into pure helpers and pinned them.
5. What cannot be run headless: `evaluate()` with a real `ServerPlayer`. A game test cannot do it
   either: `GameTestHelper.makeMockServerPlayerInLevel` puts the player on the real player list, so
   the real ticker would evaluate it, and its connection negotiated no channels, so the first
   `SignalSamplePayload` throws `UnsupportedOperationException` in `NetworkRegistry.checkPacket`
   (NeoForge 21.1.251 sources). A runtime test of the ticker needs a fake connection with the
   `rancraft` channels negotiated. Left to the in-game checks in `PHASE_3.md`.

**Checklist** (before = `bd996d6`; "same" = identical code or proven identical):

| # | Branch | Before | After | Checked by |
|---|---|---|---|---|
| A1 | Stagger | due iff `floorMod(id, interval) == floorMod(tickCount, interval)`, interval = max(1, config) | same (`isDue`) | `staggerSpacingIsOneInterval`, `staggerSpreadsPlayers` |
| A2 | Who is evaluated | meter in a hand, or lens shows trail or links; band filter irrelevant | any `SignalDevice` in a hand **or the hotbar**, or lens trail/links | differential (49 states); `SignalTickerTest` (7 `sendsSample` tests) |
| A3 | Evaluated ⇒ sent | every evaluation sends the sample | same, devices included | `sendsSample` tests; both `evaluate` paths call `send` unconditionally |
| A4 | Link rays wanted | `lens.showLinks()` | same (`linkLensOf`) | `SignalTickerCacheTest.linkLensOf` |
| A5 | One evaluation | one per due player | same; devices get the sample, never run the engine | `dispatchHandsTheOneSampleToEveryDevice` (`assertSame` sample) |
| B1 | Cache replays iff | caching on, **no armed candidate**, entry current (epoch, **site registry version**, moved < 0.5), and **not starving links** (same band filter and cap) | same (`canReplay`) | `SignalTickerCacheTest` (8 tests, one per condition) |
| B2 | Replay sends | cached sample; cached links iff wanted; state and cache untouched | same, then **dispatch of the cached sample** | code walk; `replaysAreDispatchedAndDevicesStayIdempotent` |
| B3 | Lens link cap | `min(lensMaxLinks, 12)`, 0 without links; part of the key | same (`linkCap`) | `SignalTickerCacheTest.linkCap`, `linksNeedTheSameFilterAndCap` |
| C1 | Fresh: previous state | `RECEIVERS.get` | `RECEIVERS.resume`: identical unless an armed candidate is older than one interval (part 1) | `CellSelectorTest` S4 tests |
| C2 | Fresh: engine, state store, slow warning | as is | same code; the store also records the tick | code walk |
| C3 | Meter payload | `toPayload` | **byte-identical** | differential (fixtures + 20,000); `SignalTickerPayloadTest` |
| C4 | Link rays | `traceLinks` / `chooseLinks` (serving kept in the cut) | same code | differential (5,000); existing tests |
| C5 | Cache entry | payload, links, filter, cap, eye, epoch, site version | same + the `SignalSample` | code walk |
| C6 | Send order | sample, then links | same, then dispatch | code walk |
| D1 | `forget` on logout, dimension change, respawn | cache, handover state, coverage survey | same + evaluation tick + every `DeviceMemory` | `forgetClearsDeviceState`, `storeTracksTheEvaluationTick`, `DeviceMemoryTest` |
| D2 | Server stop | cache, states, epochs, surveys, registry | same + `DeviceMemory.clearAll` | code walk; `clearAllEmptiesEverything` |
| E | Block epoch bump, chunk load/unload registration | as is | untouched (no diff) | diff |
| F | Client: HUD only while a meter is in a hand; NO SERVICE after 5 s | as is | untouched (no diff) | diff; in-game check |

**Result: passed.** Every branch is the same except A2 (deviation 2, the spec's own change) and the
stale-candidate fix in C1 (part 1, the follow-up this slice was asked to close). The in-game half
(meter HUD, link rays, handover counter) is listed in `PHASE_3.md` for the user.

Also run: `./gradlew runGameTestServer` boots with the refactor (4 bands loaded, "2 tests are now
running", "All 2 required tests passed", no error in the log). It has no player, so it checks
registration and class loading, not the ticker's per-player path.

### Honest-abstraction notes (also at the code sites)

- **A device measures only while carried in a hand or the hotbar** (`SignalTicker.carried`). A real
  UE measures all the time; here a device in the backpack is "switched off", which is what keeps the
  server cost bounded. The drive-test log is therefore only as complete as the evaluations (as
  recorded in slice 0).
- **Dropping a stale candidate is not a 3GPP mechanism.** A real UE never stops measuring, so the
  situation does not arise. The closest analogue is a phone switched off and on: after resuming,
  time-to-trigger restarts from what is observed now (`CellSelector.expireStaleCandidate`).
- **A replayed sample is the old measurement, not a new one.** Devices see the same sample again
  while the player stands still, as a real UE reports an unchanged measurement; `DeviceContext.tick`
  moves, `sample.timestampTick()` does not.

### Measured

| | |
|---|---|
| Evaluations | unchanged for every player evaluated before; +1 per interval for a player whose only reason is a device in the hotbar (deviation 2). `EvaluationStats` times the engine only and is untouched. |
| Scan | 11 slot reads and `instanceof` checks per due player per interval; not timed (next to an evaluation marching up to 12 rays it is noise). |
| Differential | 20,000 payloads byte-identical, 5,000 link cuts identical, 49 lens states (numbers above) |
| Tests | 259 (slice 3) → 265 (part 1) → **299** (part 2), 1 skipped (`utilIsPure`) |

### Tests

| | |
|---|---|
| `CellSelectorTest` (+5, part 1) | Stale candidate: continuous, pause, pause with the neighbour gone, threshold boundary, store tick bookkeeping. |
| `SignalTickerTest` (6 → 21) | `sendsSample` with carried devices (hand, hotbar only, none, every preset, band filter, hand-edited flags); the stale threshold; the stagger's spacing and spread and the live-change bound; `scanCarried` (main hand not counted twice, order and `held`, two meters are two devices, hotbar only, nothing / null / air); `dispatch` (one sample, own verdict incl. LOW_TIER on the serving band, `held`, order; NO_SERVICE still dispatched; nobody); replay idempotency through a guarded device; `forget` clears device state for that player only. |
| `SignalTickerCacheTest` (10, new) | Every `canReplay` condition, `linkLensOf`, `linkCap`. |
| `SignalTickerPayloadTest` (3, new) | The meter payload byte for byte against `bd996d6`: served (serving at rank 5, PCI collision beating mod-3), no service, unknown band. |
| `DeviceMemoryTest` (7, new) | Per-player store, `forget` across stores, `clearAll`, `ReplayGuard` replay / fresh / after forget / `/tick freeze` / independent guards. |

### APIs verified against sources (new to this codebase)

`Player.getInventory()`, `Inventory.items` (public `NonNullList`, hotbar = slots 0-8),
`Inventory.getSelectionSize()` (9), `Inventory.getSelected()` = `items.get(selected)` and
`Player.getItemBySlot(MAINHAND)` = `inventory.getSelected()` (so the main-hand stack **is** a hotbar
slot's object; `ServerPlayer` overrides none of these); `ItemStack.getItem()` is `AIR` for an empty
stack; `TickRateManager.runsNormally` / `isEntityFrozen` and `ServerLevel.tick` → `tickTime`;
`NetworkRegistry.checkPacket` (NeoForge sources jar).

### Verification

| Check | Result |
|---|---|
| `./gradlew build` | succeeds |
| Unit tests | **299: 298 passed, 0 failed, 1 skipped** |
| `rf` purity | `PackagePurityTest` (part 1 touched `CellSelector`, `ReceiverStateStore`: `java.util` only); `device` is game code, not in the pure set |
| `./gradlew runGameTestServer` | boots, 2 of 2 passed |
| Differential against `bd996d6` | passed (above); throwaway files deleted |

---

## Slice 5 — the Network Locator (§3A.6)

Commits: part 1 `bf5eb47` (pure and headless pieces), part 2 `e0fcfcd` (item, registration, HUD),
part 3 `610a79c` (world render, lang, model, tests, game tests), and the slice commit with these
notes. **3A ships here** (its in-game checks are listed in `PHASE_3.md`).

### What was built

| Piece | Where | Notes |
|---|---|---|
| Item `rancraft:network_locator`, "Network Locator" | `item/NetworkLocatorItem`, `registry/ModItems`, creative tab | A `SignalDevice`, requirement `NONE` (it degrades by fix type instead of switching off). Stack size 1. The javadoc says why it is **not GPS**: E-CID, OTDOA and NR multi-RTT, and which of them the model is (absolute ranging, like multi-RTT). No recipe (§3C.6). Placeholder model: the still vanilla compass face `compass_16`, no PNG shipped. |
| Server step | `device/LocatorTracker` (pure), `device/NetworkLocator` (glue) | Every dispatch, hand or hotbar: `Ranging.measure(sample.cells(), ...)` on the evaluation's **full** cell list (up to `maxCellsEvaluated`, not the payload's 4), `LocatorSolver.solve` with the previous fix, best resolution among the cells used. The reading is kept per player in a `DeviceMemory` (cleared by `SignalTicker.forget` on logout, dimension change and respawn, and at server stop). |
| Ground for altitude aiding | `world/LevelSurfaceProbe` | `CoverageSurveyor`'s loaded-chunks-only probe, **moved unchanged** so both share it (`getChunkNow`, never a chunk load; an empty column is `UNLOADED`). `forLocator(level)`: under a ceiling (the Nether) every column is `UNLOADED` (see honest notes). |
| `LocatorSolver.cellsUsed` | `rf/` (appended method) | Exactly the selection `solve` fits, so the rings are the cells that went in, in order. |
| `LocatorFixPayload` | `net/` (S2C, v1) | Version first; fix type; the estimate or candidates, HDOP, "±", cells used as the fix has them; up to 8 rings `(cx, cy, cz, radius, bandId)`; plus the fields the HUD needs (below). Band ids capped at `CellParams.MAX_BAND_ID_LENGTH`; every count checked before allocation; unknown version or type, over-cap counts, non-finite numbers, negative ranges/HDOP/±, a `likely` outside -1..1 all rejected. Sent **only while held**, by one Locator per evaluation. |
| Protocol | `ModPayloads` | `PROTOCOL_VERSION` "4" → "5" (new payload, new synced component). |
| HUD | `client/LocatorHudOverlay`, `LocatorHudText` (pure), `ClientLocatorState`, `HudStack` | Top-left while held, the §3A.6 layout; states NO SIGNAL / RANGE ONLY / AMBIGUOUS / POOR GEOMETRY (HDOP x) / FIX. |
| World render | `client/LocatorRenderer`, `LocatorStyle` (pure) | While held, from the payload only: rings in `BandColours`, the FIX marker with an error circle, two AMBIGUOUS markers (likely brighter). |
| Waypoints | `item/LocatorWaypoints` (data component `rancraft:locator_waypoints`, persistent + synced), `NetworkLocator.saveWaypoint / cycleWaypoint` | Up to 8. Sneak + use saves the current **estimate** (server side, from the Locator's own last FIX); use cycles; HUD distance and bearing from the estimate (`util/Navigation`). |
| Emergency record | `device/EmergencyRecord`, `registry/ModAttachments.LOCATOR_EMERGENCY` | NeoForge attachment on the player, `copyOnDeath()`, codec-serialised (an empty record is not written). Every FIX stamps `(dimension, x, y, z, errorBlocks, gameTime)`; at death a fix younger than `locatorEmergencyMaxAgeTicks` is frozen as "last fix before death" and shown on the Locator HUD after respawn until the next death. |
| `util` package | `util/Navigation` | The first `util` class (horizontal distance, compass bearing). `PackagePurityTest.utilIsPure` now runs instead of skipping. |
| Game tests | `gametest/LocatorGameTests` | The emergency record through the real death and clone events; the live-level ground lookup and solve cost. |

### HUD: layout and the rule next to the meter

```
Network Locator                                   FIX
Est  x 1204   z -3391   y ~87   ±9.0 m   HDOP 1.4
Cells 4   best res 15 m (band_1800)
WP 2/3 (saved ±9.0 m)   312 m   bearing 047°
Last fix before death: x 500  z -21  y ~70  ±12 m, 30 s before (minecraft:overworld)
```

Pinned line for line by `LocatorHudTextTest`. Coordinates are the block the estimate falls in
(floor, as F3's "Block" line); `y ~87` is the **assumed** surface (the fix's eye height minus
1.62); metres use the server's `metersPerBlock`, one decimal below 10 m so a band_3500 "±" does
not read 0; the ceiling HDOP reads "99.9+"; no bearing without a FIX, none to a waypoint in
another dimension. The last line appears only while the player has a frozen record.

**Collision rule with the meter (`HudStack`).** Only the meter's *detailed* readout is top-left
(its compact one is top-right). The meter keeps the corner and the Locator stacks directly under
whatever the meter drew this frame, 4 px below its last line. Both layers are registered in one
handler, meter first and the Locator `registerAbove` it (the only order `RegisterGuiLayersEvent`
guarantees; checked in the NeoForge sources), so each frame the meter renders first and claims its
rows, and the Locator takes the next free row and resets the claim. The meter's drawing is
unchanged (`renderDetailed` now also returns the y past its last line). With the meter compact or
not held, the Locator starts at the corner.

### Deviations and decisions

1. **Replays are recognised by the whole sample, not by `timestampTick` alone** (`LocatorTracker.isReplay`).
   §3A.3 says devices must be idempotent on `sample.timestampTick()`. A cached replay is the very
   same `SignalSample` (same object, same tick), and the Locator then **reuses the stored fix**
   (nothing solved, the ground not read; only the confirmation tick moves), rather than solving it
   again with its own answer as "previous". Keying on the whole sample instead of the tick also
   fixes the `/tick freeze` case recorded in slice 4: game time stops, so fresh evaluations of a
   walking player share a tick but not their cells, and the Locator still follows the player. Every
   replay the tick key would catch is caught too. No `ReplayGuard` is needed (the slice 4
   follow-up's suggestion); the state changes it would have guarded (previous fix, emergency stamp)
   are idempotent under this rule. Pinned by `LocatorTrackerTest`.
2. **One payload per evaluation.** Two held Locators (one per hand) send once, the main hand's,
   which is also the one the HUD reads waypoints from (`NetworkLocator.sendsPayload`, pinned). A
   Locator in the hotbar runs (emergency record) but sends nothing.
3. **The payload carries more than §3A.6 lists**, all server values the HUD needs: the evaluation
   tick, `metersPerBlock` (metres on the HUD), the best resolution in metres and its band ("best
   res 15 m (band_1800)"), `locatorMaxHdop` (the POOR GEOMETRY line names its limit),
   `locatorMinRsrpDbm` (NO SIGNAL says what was missing), and the frozen emergency record (so the
   HUD can show it after respawn). About 430 bytes for a FIX with 8 rings, about 490 with an
   emergency record (from the wire layout), once per interval while held.
4. **Waypoints have no names.** The mock-up's `"base"` needs a text input this slice does not add;
   the HUD shows the number and the "±" the waypoint was saved with instead. When all 8 are used, a
   save **overwrites the selected entry** (the player chooses what to lose by cycling to it first);
   nothing is dropped silently.
5. **Saving needs a current FIX.** The reading must be at most two evaluation intervals old
   (`LocatorTracker.freshForTicks`: a carried Locator is dispatched every interval; the second is
   slack for a live interval change) and must be a FIX. Otherwise the save is refused with a
   message naming the fix type. A waypoint saved from a stale reading would not even be the estimate
   of where the player stands.
   5a. *(Phase 3A review, round 1.)* **The solver's `previous` is used only while the reading is
   fresh, the same rule as saving a waypoint** (`LocatorTracker.update`: `isFresh(previous,
   dispatchTick, freshForTicks(interval))`, else `null`). The reading is a `DeviceMemory`, cleared
   only on logout, dimension change and respawn, so before this a Locator put in a chest, carried
   1 km and taken out where only two cells are heard marked whichever candidate was nearer the old
   place as "likely" (a coin flip drawn brighter), seeded the height from there, and the
   Ambiguous-to-Ambiguous chain then kept that choice. Slice 3's rule (decision 5 there) is that
   `likely` is `NO_PREFERENCE` without a previous estimate, so the Locator never claims a
   preference it does not have; a stale estimate broke it. The replay check still uses the stored
   reading at any age. **Known limit:** a same-dimension teleport (`/tp`, an ender pearl) while the
   Locator stays in the hotbar keeps a fresh but far-away previous for one evaluation, which can
   pick the likely candidate once. An optional extra guard, not implemented: drop the previous when
   its (x, z) is farther from both new candidates than the larger measured range.
6. **Only a FIX stamps the emergency record**, with the game time the Locator last *reported* it
   (a replay confirms it, so a player standing still keeps a young fix). RANGE ONLY, AMBIGUOUS,
   POOR GEOMETRY and NO SIGNAL say nothing new about where you are, so the last known position
   stays the last FIX, as a location server's would. "Younger than" is strict: at the default 1200,
   1199 ticks freezes and 1200 does not; 0 disables it. Death also clears the last fix, so the next
   life starts with no known position.
7. **The death hook** is `LivingDeathEvent` at `EventPriority.LOWEST`, not delivered when
   cancelled (a cancelled death is no death: `ServerPlayer.die` returns). A totem of undying never
   reaches it. NeoForge's own `PlayerEvent.Clone` subscriber (`AttachmentInternals.onPlayerClone`)
   copies exactly the `copyOnDeath` attachments on respawn (`ServerPlayer.restoreFrom` fires it with
   `wasDeath = !keepEverything`); returning from the End copies every serialisable attachment.
8. **A dead player's Locator does nothing** (found while writing the death path). The ticker
   evaluates every player on the list, including one on the death screen, and with
   `keepInventory` the dead entity still carries the Locator. Without the guard it would stamp a new
   last fix into the record death had just cleared, and the clone would carry it into the next life.
   *(Phase 3A review, round 1: slice 5 marked this verified, but no test covered the guard. It is
   now checked at runtime by `LocatorGameTests.dead_player_locator_is_inert`, a NeoForge
   `FakePlayer` with 0 health, which fails with the guard removed; see "Phase 3A review, round 1".)*
9. **Rings: where they are sliced, and where they are drawn.** A measured range is a slant distance,
   a sphere round the cell. The ring is its horizontal cross-section `sqrt(r^2 - dy^2)`, taken at
   the FIX's own assumed eye height (so the rings cross at the marker: the picture of what the
   solver did), or, without a FIX, at the viewer's eye height (so the rings pass through or near
   the player, off by quantisation and NLOS bias). The shapes are then **drawn on the ground
   1.62 below** with that radius: at eye height every ring, error circle and cross lies in the
   camera's plane and, in first person, collapses onto the horizon as one line. On a slope a ring
   dips into the hillside; the faint see-through pass (the lens's) keeps it traceable. RANGE ONLY's
   ring is its cell's ring (the slant radius sliced like the others); the HUD prints the measured
   slant range.
10. **AMBIGUOUS markers stand at the viewer's feet height:** the payload carries each candidate's
    (x, z), not the height the solver assumed there. They are vertical strokes, so the height reads
    as "somewhere on this column". With no preference (`likely` = -1) both are drawn alike.
11. **Follow-up (g) from slice 3 closed:** `locatorMaxCells`'s upper bound is now
    `LocatorFixPayload.MAX_RINGS` (8), so every cell in a fix gets its ring.
11a. *(Phase 3A review, round 1.)* **The client's reading.** It is dropped every client tick no
    Locator is held (`ClientEvents.onClientTick` → `ClientLocatorState.putAway()`), because the
    server sends only while one is held: re-selecting the Locator shows "No reading from the network
    yet" until the next payload instead of an old fix and its rings. Its stale limit is learned from
    the gaps between payloads (2.5 gaps in [5 s, 30 s], the lens's rule) instead of a fixed 5 s that
    blinked the HUD at intervals over 100 ticks. With no current reading the waypoint's saved "±"
    uses the last payload's `metersPerBlock`, or is left out before any payload, never an assumed
    1 m per block. Details in "Phase 3A review, round 1".
12. **The client does map arithmetic only.** Distance and bearing from the estimate to a waypoint
    (`util/Navigation`) are computed on the client between two server-supplied estimates; slicing a
    ring and placing the markers is drawing. No range, position, HDOP or "±" is computed there.
13. **Spec vs tree: "assert via the existing EvaluationStats log".** `EvaluationStats` is only
    logged by `SignalTicker.warnSlow`, for an evaluation over 2 ms, at most once per 30 s, and it
    counts nothing, so it cannot show how many evaluations ran. The "no extra evaluation" claim is
    verified structurally instead (the ticker evaluates each due player once whatever they carry,
    pinned since slice 4; the Locator's API takes no `WorldProbe`; a replay costs no solve) and the
    Locator's own cost is measured (below). Recorded in `PHASE_3.md` follow-ups.

### Honest-abstraction notes (also at the code sites)

- **Not GPS** (`NetworkLocatorItem`): cellular positioning, the family of E-CID, OTDOA and NR
  multi-RTT; modelled as absolute ranging like multi-RTT, with no positioning reference signals and
  no clock error (slice 3).
- **The emergency record is network-derived emergency caller location** (`EmergencyRecord`,
  `NetworkLocator.onLivingDeath`): on a 112/911 call a location server (E-SMLC in LTE, LMF in NR)
  can run E-CID, OTDOA or multi-RTT and hand the estimate, with its uncertainty, to the emergency
  service. The record stores that **estimate**, never the true position: a fix taken behind a hill
  sends you back to the wrong place, as a bad network fix sends responders to the wrong door.
- **Waypoints store estimates** (`LocatorWaypoints`): navigation error inherits fix error, and a
  waypoint saved under a bad fix is pointed at faithfully and wrongly. That is the lesson.
- **The "±" is quantisation only and is not a bound** (`LocatorHudText`). The game test's live
  solve shows it: a band_1800 FIX 6.51 blocks from the truth reports ± 3.13 (HDOP 0.72): the error
  is the deterministic rounding of those particular ranges, so a player standing still sees a fixed
  error, not noise that averages out. The NLOS bias is not in it either.
- **`y` is assumed** (`~` on the HUD): altitude aiding stands the receiver on the top surface. In
  the Nether (a ceiling) no column is trusted, because the heightmap finds the bedrock roof; the
  solver then falls back to its last known height (the previous fix's `y`, else the lowest
  radiating point among the cells heard). That is a guess, labelled as one, in a dimension where
  cells are rarely built.
- **A Locator measures only while carried** in a hand or the hotbar (slice 4's rule for every
  device): in a chest it is switched off, and its emergency record stops updating.

### Measured

| | |
|---|---|
| Ground lookup in a live level | **77.9 ns** per `LevelSurfaceProbe.surfaceY` (105/105 columns within 24 blocks loaded; JIT-warm, 2,000 x 105 lookups; `runGameTestServer`, flat world, this PC) |
| Worst-case fix | ~~105 lookups (7 runs x 15 iterations) = 8.2 µs~~ **113 lookups (7 runs x (15 iterations + 1 end-point read) + 1 under the centroid) = 8.8 µs** of ground reads. *Corrected in the Phase 3A summary (the slice 5 gate found the undercount; `LocatorTrackerTest` already asserted 113).* |
| One 8-cell band_1800 solve on live ground | **22.2 µs**, 37 lookups (FIX 6.51 blocks from the truth, ± 3.13, HDOP 0.72) |
| Replay (player standing still) | no solve, no ground read (`LocatorTrackerTest`) |
| Evaluations | none added: the Locator is handed the one sample the ticker produced; its API has no `WorldProbe`, so it cannot march a ray. A Locator is a device, so a player carrying only a Locator (hand or hotbar) is evaluated once per interval, like a meter carrier (slice 4). |
| Payload | about 430 bytes (FIX, 8 rings) once per interval while held |
| Tests | 299 (slice 4) → 345 (part 1, `utilIsPure` no longer skipped) → **367** (part 3), 0 skipped |

### Tests

| | |
|---|---|
| `LocatorTrackerTest` (11) | Full cell list, not the payload's 4; `maxCells` strongest with a ring each; below-threshold cells unranged; NO SIGNAL; best resolution; a replay reuses the fix with no solve and no ground read; confirmation never runs backwards; `/tick freeze` still follows the player; previous fix marks the likely candidate; freshness window; lookups per fix bounded. |
| `EmergencyRecordTest` (8) | Empty until a FIX; newer FIX replaces; death freezes a young fix and clears the last; 1199 freezes, 1200 does not; survives until the next death; bad numbers dropped; dimension clamped; codec round-trip. |
| `LocatorWaypointsTest` (10) | Empty; save appends and selects; a ninth save overwrites the selected; cycle wraps; invalid never saved; hand-edited data sanitised; dimension clamped; codec and stream codec round-trips; more than 8 rejected before reading. |
| `LocatorFixPayloadTest` (9) | Every fix type round-trips; version first; cells used; building (8 rings, non-finite dropped); band ids clamped; unknown version/type; ring count over the cap rejected before allocation; malformed numbers; bad scale. |
| `NavigationTest` (7) | Compass bearings, diagonals, the mock-up's 047°, never NaN or 360, whole degrees, horizontal distance. |
| `LocatorSolverTest` (+1) | `cellsUsed` is exactly what `solve` fits. |
| `LocatorHudTextTest` (10) | The mock-up line for line; scale; each state's text; waypoint guards; the emergency line; metres; coordinates. |
| `LocatorStyleTest` (5) | State colours; slice radius; slice height; candidate brightness incl. no preference; circle segments. |
| `HudStackTest` (4) | Corner when unclaimed; under the meter; a claim lasts one frame; the lowest claim wins. |
| `NetworkLocatorTest` (3) | Which Locator sends: never from the hotbar; main hand; offhand unless the main hand has one. |
| `LocatorGameTests` (2, `runGameTestServer`) | The attachment is registered as `rancraft:locator_emergency` and written with the player; a `LivingDeathEvent` on the real bus freezes the estimate (not the mock's position) at the death tick and clears the last fix; `PlayerEvent.Clone(wasDeath)` copies it to the new player; a fix exactly `maxAge` old freezes nothing, is removed, not written and not copied. The live cost above; fails only above 1 ms per solve. |

### APIs verified against sources (new to this codebase)

NeoForge 21.1.251 sources jar: `AttachmentType.builder(Supplier)`, `Builder.serialize(Codec,
Predicate)` (the predicate skips writing), `Builder.copyOnDeath()` (throws without a serializer),
`NeoForgeRegistries.Keys.ATTACHMENT_TYPES` / `ATTACHMENT_TYPES`, `IAttachmentHolder.getExistingData /
setData / removeData / hasData` (Supplier overloads), `AttachmentHolder.serializeAttachments` (keys
by registry id, `null` when nothing to write), `AttachmentInternals.onPlayerClone` →
`copyAttachmentsFrom(old, wasDeath)` (only `copyOnDeath` types on death), `EventHooks.onPlayerClone`
from `ServerPlayer.restoreFrom(old, keepEverything)` with `wasDeath = !keepEverything`,
`LivingDeathEvent(LivingEntity, DamageSource)` (cancellable), `RegisterGuiLayersEvent.registerAbove
/ registerAboveAll` and `GuiLayerManager`'s render order. Decompiled 1.21.1:
`LivingEntity.isAlive` (`!isRemoved() && health > 0`), `Player.isSecondaryUseActive`,
`GameTestHelper.makeMockPlayer` (not added to the level or player list), `GameTestServer` (flat
world preset), the vanilla `compass_16` texture in the client assets.

### Verification

| Check | Result |
|---|---|
| `./gradlew build` | succeeds |
| Unit tests | **367: 367 passed, 0 failed, 0 skipped** |
| `rf` / `util` purity | `PackagePurityTest` (both run now) |
| `./gradlew runGameTestServer` | "4 tests are now running", "All 4 required tests passed" (2 harvest + 2 locator); the cost line above is from this run |
| In game | not run by the agent (no `runClient`); the checks are listed in `PHASE_3.md`, slice 5 |

---

## Owner decisions on the slice 3 follow-ups

The two open slice 3 follow-ups in `PHASE_3.md` were decided by the project owner (2026-09-29).
Both are now **owner-approved deviations from §3A.5**, labelled as such at the code sites
(`LocatorSolver` class javadoc, `leastSquares`, `weightedErrorOf`; `LocatorFix.Fix`;
`LocatorHudText`). Code and tests: part 1 `f61a739`; these notes and the tracker: `b47bcbf`.

### 1. The extra Gauss-Newton starts are kept (deviation from §3A.5's centroid-only start)

§3A.5 says "Initial guess: centroid". `LocatorSolver` still starts there first, and also from the
circle crossings of each pair among the 3 strongest cells (at most 6 extra runs), keeping a run
that ends more than 1 block away with a smaller weighted residual (slice 3, deviation 2). **Kept
because a centroid-only start reports a wrong fix with a small "±" for 10.5-16.7 % of fixes with
the receiver outside the cells' footprint**, even with perfect ranges (e.g. FIX 390 blocks from the
truth, "± 0", HDOP 4.3; 20,000 seeded scenes per case, table in slice 3). A confident wrong answer
teaches the wrong thing about what the "±" means. With the extra starts: perfect ranges 0 %,
band_900 1.3-5.2 % (genuine mirror ambiguity, which no start can remove). When the centroid's run
is the best fit, the result is exactly the spec's. Cost: about 19 µs per 8-cell fix synthetic,
22.2 µs measured on live ground (slice 5). No code change in this step: only the comments now say
"deliberate deviation, kept by the owner" and why. `notTrappedOutsideTheFootprint` still pins it
(fails with the extra starts off, checked in slice 3).

### 2. `errorBlocks` is the weighted-fit covariance (deviation from §3A.5's errorBlocks formula)

§3A.5 writes `errorBlocks = HDOP × rms(σ_i)`. It is now

```
errorBlocks = sqrt(trace((HᵀWH)⁻¹)),   W = diag(1/σ_i²)
```

in the horizontal plane, with the **same H as HDOP** (rows: the horizontal unit vectors from the
estimate to each cell; a cell directly overhead adds no row). **HDOP is unchanged**: still the
unweighted, conventional `sqrt(trace((HᵀH)⁻¹))`, describing the geometry alone, and still what the
`locatorMaxHdop` gate and PoorGeometry read. The "±" is the reported **1-sigma horizontal
uncertainty of the weighted fit** (the root of the x and z variances summed, the same distance-RMS
convention as `HDOP × σ`), still from quantisation only: the NLOS bias is systematic and not in it,
nor is a mirror ambiguity.

**Why this is the statistically right estimate for this solver.** The fit is Gauss-Newton with
weights `1/σ_i²`, i.e. weighted least squares with W the inverse of the range-error covariance.
Linearised at the solution, the covariance of that estimate is exactly `(HᵀWH)⁻¹` (Gauss-Markov:
the best linear unbiased estimator's covariance; for Gaussian errors it is also the Cramér-Rao
bound). `HDOP × rms(σ)` is the error of a fit that weighs every cell alike, which is not the fit the
solver makes: it averages a band_3500 range's σ in as one of n equals although that range carries
100x the weight of a band_900 one, so it under-reports what the wideband cell buys along its line of
sight. §3A.5's own sentence, "a band_3500 cell has a 10x smaller σ, so it dominates the fit", is true
of the estimate; the weighted covariance makes the "±" say it too. **When every σ is equal**, W =
I/σ² and `sqrt(trace((HᵀWH)⁻¹)) = σ · sqrt(trace((HᵀH)⁻¹)) = HDOP × σ`: every single-band fix, test 7
included, reads exactly as before. (The quantisation errors are uniform, not Gaussian; σ = res/√12
is their true standard deviation, so the covariance is still right for the linearised fit and only
the "68 %" reading of one sigma is approximate.)

Implementation: `LocatorSolver.weightedErrorOf`, the 2x2 closed form `trace(M)/det(M)` with the
determinant summed pairwise (Cauchy-Binet, as the Gauss-Newton step already does), so it stays
exact to rounding with weights 100x apart. Since every weight is positive, `HᵀWH` is singular only
where `HᵀH` is, which the HDOP gate has already rejected; a non-finite result would still give
PoorGeometry (the ceiling) rather than an infinity (test 12). No record, wire or save format
changes: `Fix.errorBlocks` keeps its place and type, `PROTOCOL_VERSION` stays "5",
`LocatorFixPayload` v1. Waypoints and emergency records saved before the change keep the "±" they
were saved with.

**Measured** (the pinned tests; blocks at 1 m per block):

| Geometry | band_900 only | + band_3500 (was: `HDOP × rms`) | + band_900 in the same spot |
|---|---|---|---|
| Edge of network: 3 band_900 sites in a 20° wedge to the west (HDOP 4.10), 4th site 60 blocks north | ± 35.49 | **± 5.12, 6.93x** (was 8.50, 4.17x) | ± 9.81, 3.62x |
| Good 120° triangle (HDOP 1.15), 4th site inside at (52, 30) | ± 9.99 | **± 7.15, 1.40x** (was 7.77, 1.29x) | ± 8.93, 1.12x |

In the edge case the two four-cell fixes have the same HDOP (1.133) to 0.001, so the gap between
them (9.81 / 5.12 = 1.91x) is what the weighting is credited with; `HDOP × rms` credited 1.15x.
**Test 10's 3x** is asserted in the edge-of-network geometry. **Inside a good triangle one band_3500
cell still gives less than 3x (1.40x); the owner accepts that.** One range constrains only its own
line of sight, so the band_900 error across it remains; more wideband sites are what shrink it.
The weighted "±" is not always smaller than `HDOP × rms`: when the wideband cell's direction
duplicates a coarse one, the rms under-weights the remaining coarse direction and the weighted
figure can be slightly larger (it is the honest one either way).

### Honest-abstraction notes (also at the code sites)

- **The "±" is a reported uncertainty, not a guarantee**: quantisation only, deterministic (a player
  standing still sees a fixed error, not noise), no NLOS bias, no mirror ambiguity.
- **Slant σ used for horizontal ranges.** The fit works on horizontal ranges
  `ρ = sqrt(r² − dy²)`, whose error is σ·r/ρ, larger than σ close under a high tower. Both the
  weights and the "±" use the slant-range σ, as §3A.5 does. Small at the usual distances; it
  under-states the "±" only when you stand almost under a mast.

### Tests

| | |
|---|---|
| `LocatorSolverTest` (+2, now 22) | `equalSigmasGiveHdopTimesSigma`: 2,000 seeded single-band scenes (engine cells of band_900 / 1800 / 3500 quantised, or exact ranges with any common σ in 0.1-20): every FIX has `errorBlocks = HDOP × σ` to 1e-9 relative. `errorIsTheWeightedCovarianceAndHdopStaysUnweighted`: 2,000 seeded mixed-band scenes (over 500 of them mixed-band FIX results), both 2x2 normal matrices written out and inverted directly at the estimate; `hdop` matches the unweighted one and `errorBlocks` the weighted one to 1e-7 relative. |
| Test 10 (`widebandCellShrinksTheError`) | Still asserts ≥ 3x and band_3500 beating band_900 in the same spot; now also pins 35.49 / 5.12 / 9.81, 6.93x and 3.62x, the single-band fixes at `HDOP × σ` exactly, equal HDOP to 0.001, what `HDOP × rms` would have reported (8.50) and the 1.91x credited to the weighting. |
| `oneWidebandCellInAGoodTriangle` | Pins 9.99 → 7.15 (1.40x) and the band_900 control (1.12x), and asserts the ratio stays below 3x (the accepted limit). |

**The tests bite** (checked by breaking the code temporarily, then restoring it): putting the
spec's `HDOP × rms(σ)` back fails test 10, the triangle pin and the weighted-covariance test, while
the equal-σ equivalence and test 7 still pass (as they must: the formulas agree there). Weighting
the "±"'s trace by `1/σ` instead of `1/σ²` fails all five, including the equivalence and test 7.

### Verification

| Check | Result |
|---|---|
| `./gradlew build` | succeeds |
| Unit tests | **369: 369 passed, 0 failed, 0 skipped** (367 + 2) |
| `rf` purity | `PackagePurityTest` (`LocatorSolver`, `LocatorFix`: `java.util` only) |
| `./gradlew runGameTestServer` | not rerun: nothing game-side changed, and the game test's solve is single-band (band_1800), where the "±" is identical by construction |
| In game | the slice 5 check "add a band_3500 sector nearby: the ± shrinks" in `PHASE_3.md` now expects about 1.4x inside a good triangle and much more at the edge of the network |

---

## Phase 3A review, round 1 — fixes

Nine confirmed findings from the Phase 3A review (round 1), two pairs of them the same defect found
twice (3 = 7, 5 = 9). All applied; the build is green (**382 unit tests**, 0 skipped) and
`runGameTestServer` passes **5 of 5**. Commits: part 1 `5221c46` (sites), part 2 `62fef10`
(drive-test anchor, fresh-only prior), part 3 `2f1306f` (client reading), part 4 `a755f7f`
(dead-player game test), and the docs commit `a998f3f` ("Phase 3A review round 1: fixes").

### 1. [major] The Locator counts sites, not cells

**The defect.** `LocatorSolver` chose its branch (1 → RANGE ONLY, 2 → circle crossing, 3+ → least
squares) by the number of *cells*. In this tree a sector is its own cell on its own block (Phase 2,
"Sector antenna": "Independent cell per block — no 'site' container"), so a three-sector site is three
cells one block apart, and the solver took them for three towers. Their ranges are near-identical,
and on one band they round the same way, so their errors are perfectly correlated, yet each added an
independent weighted row. The only co-location guard (`MIN_DIRECTION_BLOCKS`, 1e-6) catches sectors
stacked in one column, not sectors on neighbouring blocks.

**Reproduced before the fix** (the reviewer's scratch programs, re-run on the pre-fix classes;
flat ground, sectors at (0,-1), (1,0), (-1,0) round a mast column):

| Scene | band_900 | band_1800 | band_3500 |
|---|---|---|---|
| One site, two sectors heard, receiver 60-150 away (2000 scenes) | AMBIGUOUS 1938 (1944 in the per-band run), the nearer candidate over 50 blocks off in 1295 | AMBIGUOUS 1892 | AMBIGUOUS 1403 |
| Two three-sector sites 200 apart, 6 cells (2000 scenes) | **FIX 1997, 921 on the wrong mirror** (per-band run: 911, mean error 180) | FIX 2000, 851 wrong mirror | FIX 2000, 425 wrong mirror |
| The example: receiver (100.5, 80.5) | `Fix[z = -64.3, hdop 0.90, ± 7.76]`, 145 blocks off; one cell per site: `Ambiguous(z = +64 / -65)` | | |
| Three three-sector sites in a triangle | **mean ± 6.45 against an rms true error of 10.21** (3000 scenes); one cell per site ± 10.43 vs 10.67 | ± 3.21 vs 4.69 | ± 0.64 vs 0.63 |

**The fix.** `LocatorSolver.siteRepresentatives(ranges, params)` replaces the old `select`, in
both `solve()` and `cellsUsed()`: (i) keep every valid measurement (the validity checks are
unchanged, no cap yet); (ii) union-find over all pairs, merged when the horizontal distance between
radiating points is at most `siteMergeBlocks` (transitive, independent of input order); (iii) one
representative per site: smallest sigma, then smallest range (the least NLOS-biased), then the
first given; (iv) sites in the order of each one's first (strongest) member; (v) at most `maxCells`
sites. `solve()` switches on the number of sites, so the centroid, `SINGULAR_AT_START`, HDOP, the
"±" and the extra starts all see one row per site. `cellsUsed()` returns the same list, so
`solve(...).cellsUsed() == cellsUsed(...).size()` still holds and the Locator draws one ring per
site (co-sited rings were near-identical anyway).

**Tunable.** `LocatorParams.siteMergeBlocks` (appended; `DEFAULT_SITE_MERGE_BLOCKS = 3.0`, the
widest three-sector site spans 2 blocks, (1,0) to (-1,0)); the four-argument constructor is kept
and defaults it. `RfConfig.locatorSiteMergeBlocks` (appended; `DEFAULTS`, `snapshot()` and the one
test helper updated, still no legacy constructor) and `RanCraftConfig.locatorSiteMergeBlocks`
(`defineInRange(3.0, 0.0, 16.0)`, "antennas within this horizontal distance are one site for
positioning"). 0 groups only antennas stacked in one column (distance 0); a negative or NaN value
groups nothing. `Band`, the payload and the saves are untouched: no protocol bump.

**Honest-abstraction note (also at the code site, `LocatorSolver` class javadoc and
`siteRepresentatives`, and in the config comment).** A real network knows each site's (TRP's)
location, and co-sited sectors are one position to range from: they add no independent geometry.
The mod has no site container, so **horizontal proximity stands in for that identity**. Two
consequences, stated plainly: two genuinely separate masts closer than 3 blocks count as one site
(no real planner puts sites that close); and on band_3500, whose 3-block step is comparable to the
sector spacing, co-sited sectors round *partly* independently, so one range per site discards a
little real information there (the ± becomes slightly conservative, below).

**After the fix** (same programs, same seeds):

| Scene | band_900 | band_1800 | band_3500 |
|---|---|---|---|
| One site, two sectors | **RANGE ONLY 2000 / 2000**, AMBIGUOUS 0 | RANGE ONLY 2000 | RANGE ONLY 2000 |
| Two three-sector sites | **FIX 0, wrong-mirror FIX 0**; AMBIGUOUS 1999 (1 other), a candidate within one ranging step of the truth in 1984 | AMBIGUOUS 2000, 1978 within a step | AMBIGUOUS 2000, 2000 within a step |
| The example (100.5, 80.5) | `Ambiguous(z = +63.99 / -64.99)`, identical to one cell per site | `Ambiguous(+88.8 / -89.8)` | `Ambiguous(+79.5 / -80.5)` |
| Triangle of three-sector sites | **mean ± 10.42 against rms 10.44** (3000 scenes); per-band run ± 10.41 vs 10.43 | ± 5.21 vs 5.07 | ± 1.04 vs 0.93 |

**Cost:** the grouping is 0.4-0.5 µs for 8-12 measurements (default `maxCellsEvaluated` 12) and
7 µs at the configurable maximum of 64 (scratch benchmark, JIT-warm), run twice per fresh fix
(`solve` and `cellsUsed`). Squared distances are compared (no square root per pair).

**Tests** (`LocatorSolverTest`, +5; all flat ground, band_900, quantised): `oneSiteIsOneRange` (24
receiver positions round a two-sector site: RANGE ONLY on the shorter range's sector, one ring);
`twoThreeSectorSitesAreAmbiguous` (the example; AMBIGUOUS, mirror images across the sites' line,
identical to solving the two representatives alone); `triangleOfSectorSitesCountsSites` (FIX with
`cellsUsed` 3 and the one-cell-per-site ± to 1e-9; per cell the ± drops below 0.7x);
`noMergeIsPerCell` (`siteMergeBlocks` 0: the old per-cell answers, including the wrong-mirror FIX);
`siteRepresentativeRule` (transitive chain in four orders, finest band then shortest range then
first given, site order, `maxCells` counts sites, stacked column, negative/NaN). The existing
`twoCellsThatDoNotMeet` concentric case stays green through the shortest-range tie-break; test 12 and
both random sweeps stay green.

**Deviation from the review's test (2):** it asked for "one candidate within 10 blocks of the
truth" at (100.5, 80.5). There both sites' ranges round down by band_900's 30-block step, so the
true-side candidate is 16.5 blocks off: that is quantisation, identical with one cell per site. The
test asserts within one ranging step (29.98 blocks), the mirror across the sites' line, and
equality with the one-cell-per-site answer.

**Spec conflict, recorded for the owner (`PHASE_3.md` follow-ups).** §3A.5 says "3 or more usable
cells". In this tree a sector is its own cell on a neighbouring block, so the solver now counts
sites. `LocatorFix.cellsUsed` and the HUD's "Cells N" therefore count sites (one representative
cell each); a player next to a three-sector site hears three cells and reads "Cells 1".

### 2. [minor] Drive-test log: "stationary" is measured from where the still period began

`DriveTestLog.record` compared each sample with the previous entry, which may itself be a
replacement, so any movement under `STATIONARY_EPSILON_BLOCKS` (0.5) per evaluation kept replacing
one entry and a whole walk collapsed into one moving point in the trail and the CSV. Reproduced
before the fix: a 100-block straight walk at 4.317 b/s with a 2-tick interval (0.43 blocks per
sample) gave **1 entry, at x = 99.72**; the same happens to sneaking at 5 ticks and to any drift
under 0.5 b/s at the default 20 ticks (soul sand, honey). Now the log keeps an anchor (the position
of the last *appended* sample): a replacement keeps the newest sample but does not move the anchor,
and "stationary" is the distance from the anchor. The same walk now gives **116 entries**; the
exact-replay check, the event checks and the cell/level checks are unchanged (against the previous
sample); `clear()` drops the anchor. Tests (`DriveTestLogTest`, +2): 0.4 blocks per sample over
100 blocks gives 126 entries (at least 80 required), 60 samples at one point stay 1 entry with the
newest tick, and a clear starts a new still period; `stationaryReplaces` (0, 0.1, 0.2) still passes.

Why nobody saw it: **the server's sample cache hid it, but only while caching was enabled and the
block epoch was not changing.** A cached replay carries the position of the evaluation it replays,
and the cache is only refreshed after a 0.5-block move, so the replays were exact duplicates
(ignored) and every fresh sample was at least 0.5 blocks from the last one: the cache's own move
threshold acted as the anchor. With `enableSampleCaching` off, or any block placed or broken in the
dimension every interval (the epoch is dimension-wide until slice 7), every evaluation is fresh and
the collapse happened.

### 3 (= 7). [minor] The solver's `previous` is used only while the reading is fresh

See slice 5, "Deviations and decisions", item 5a. `LocatorTracker.update` passes the stored fix to
`LocatorSolver.solve` only if `isFresh(previous, dispatchTick, freshForTicks(interval))`; the replay
check still uses the stored reading as is. Tests (`LocatorTrackerTest.stalePreviousPicksNothing`):
a FIX at tick 4000, the two-cell sample at 4020 and at exactly 4040 (the boundary, fresh) still
marks the candidate near the truth; at 4041 `likely` is `NO_PREFERENCE`; a replay is still
recognised at any age.

### 4. [minor] The client drops the Locator reading when no Locator is held

The server sends `LocatorFixPayload` only while a Locator is held, but the client kept the last one
for 5 s of wall-clock time: switching to a sword, sprinting 20 blocks and switching back showed
"FIX Est x/z = A" with the marker, error circle and rings at A, and the waypoint distance and
bearing measured from A, for up to one interval. `ClientEvents.onClientTick` (`ClientTickEvent.Post`,
already used by `LensKeys`) now calls `ClientLocatorState.putAway()` every tick no Locator is held
(`LocatorHudOverlay.heldLocator`), so re-selecting it shows "No reading from the network yet" until
the first payload sent after it is held again. It is a tick handler, not in the HUD layer, because
the layer returns early under F1 (`hideGui`) while `LocatorRenderer` would still draw the old
rings. **Refinement of the review's text:** the review had the handler call `clear()`, which with
fix 6 would also forget the learned send cadence every time the Locator is put away (so a slow
server's first interval after taking it back would blink). `putAway()` drops the reading and keeps
the cadence; `clear()` (logout, respawn, dimension change) still forgets both. Consequence for the
slice 17 CSV follow-up: `fix_x/fix_z/fix_err` read from the client state are blank whenever the
Locator is not held, which matches that follow-up's "document that the columns are blank then"
option.

### 5 (= 9). [minor] With no current reading, the waypoint's saved "±" uses the server's scale or none

`LocatorHudText.screen` converted the saved "±" at a hardcoded 1 m per block whenever it had no
current reading, so on a server at `metersPerBlock` 2.0 the same waypoint read "saved ±10 m" in the
save message and while readings arrived, and "saved ±5.0 m" just after taking the Locator in hand or
once the payload was stale. Now it uses **the last payload's `metersPerBlock`** (a stale payload
still carries the server's scale), and **with no payload at all the "±" is left out** ("WP 1/1   no
FIX, no bearing"): the client cannot read COMMON config, so it never assumes 1 m per block, and a
"? m" would read as a fault. Tests (`LocatorHudTextTest`, +1 and one changed expectation): stale at
2.0 m/block with ± 9 blocks reads "saved ±18 m" and with ± 5 "saved ±10 m"; no payload prints no
metres value; a zero or NaN scale is treated as unknown. The more robust option (store the scale
on the waypoint when it is saved, an appended optional field plus a protocol bump) was not needed
for this defect and is not done.

### 6. [minor] The Locator HUD's stale limit is learned from the payload cadence

`ClientLocatorState` treated a payload as stale after a fixed 5 s, while the server counts a reading
fresh for two evaluation intervals (`LocatorTracker.freshForTicks`). At `evaluationIntervalTicks`
200 (10 s), or under `/tick rate` below 4, the HUD said NO SIGNAL and the rings blinked off for
about half of every interval while the server still saved waypoints from that fix. Now
`accept` learns the last gap between payloads (0 < gap ≤ 30 s) and `isStale` uses the lens's rule,
`ClientLensState.staleAfterMillis(gap)`: 2.5 gaps, clamped to [5 s, 30 s], slightly longer than
the server's two-interval window, which is the intended slack. No wire change, no version bump.
Tests (`ClientLocatorStateTest`, 4, new; the wall clock is passed in): a 10 s cadence gives a 25 s
limit (6 s after a payload is no longer stale, as it was at 5 s); the 1 s default keeps the 5 s
floor; the 30 s cap and a longer gap is not learned; `putAway()` keeps the cadence, `clear()`
forgets it. The meter's `ClientSignalState` still has the same pre-existing fixed 5 s (open
follow-up in `PHASE_3.md`).

### 8. [minor] A dead player's Locator does nothing: now verified at runtime

`PHASE_3.md` marked "a dead player's Locator does nothing" `[x]` under the headless checks, but no
test covered the `isAlive()` guard in `NetworkLocator.onSample`. Option (a) of the review, a
runtime check: `LocatorGameTests.dead_player_locator_is_inert` builds a NeoForge `FakePlayer`
directly (`new FakePlayer(level, new GameProfile(randomUUID, "rancraft_dead_locator"))`: not on the
player list, so the ticker never evaluates it; its connection sends nothing; not through
`FakePlayerFactory`, so no cached shared fake player is altered), sets its health to 0
(`isAlive()` is `!isRemoved() && health > 0`), and calls `NetworkLocator.onSample` with a
hand-built sample of three well-spread band_900 masts that solves to a FIX: no reading is kept and
no emergency record is written. With health 20 the same call keeps both. **It bites:** with the
guard removed temporarily, `runGameTestServer` fails with "the dead player's Locator acted: record
Optional[EmergencyRecord[lastFix=...]], reading kept true" (1 required test failed; restored,
5 of 5 pass). `NetworkLocator.hasReading(UUID)` is the small public test hook (`READINGS` is
package-private). APIs checked in the NeoForge 21.1.251 sources jar: `FakePlayer(ServerLevel,
GameProfile)` (public; `FakePlayerNetHandler` as its connection; `FakePlayerAdvancements` via the
patched `PlayerList.getPlayerAdvancements`), `FakePlayerFactory.get / unloadLevel`; decompiled
1.21.1: `ServerPlayer`'s constructor, `LivingEntity.setHealth` (clamped to [0, max]) and `isAlive`.

### Verification

| Check | Result |
|---|---|
| `./gradlew build` | succeeds |
| Unit tests | **382: 382 passed, 0 failed, 0 skipped** (369 + 5 `LocatorSolverTest` + 2 `DriveTestLogTest` + 1 `LocatorTrackerTest` + 1 `LocatorHudTextTest` + 4 `ClientLocatorStateTest`) |
| `rf` / `util` purity | `PackagePurityTest` (`LocatorSolver`, `LocatorParams`, `RfConfig`, `DriveTestLog`: `java.util` only) |
| `./gradlew runGameTestServer` | "5 tests are now running", "All 5 required tests passed" (2 harvest + 3 locator); the locator cost line on this run: 80.3 ns per ground lookup, one 8-cell band_1800 solve on live ground 29.0 µs (FIX 6.51 blocks off, ± 3.13, HDOP 0.72) |
| Before/after numbers | the reviewer's scratch programs (flat ground, seeded), run on the pre-fix classes and on the fixed `rf` package; tables above |
| The new tests bite | the game test was run with the guard removed (fails, above). The other new assertions pin behaviour the pre-fix code demonstrably lacked (the "before" runs above: AMBIGUOUS on one site, the wrong-mirror FIX at (100.5, 80.5), 1 drive-test entry for the walk; the 1.0 scale and the fixed 5 s are gone from the code) |
| Config | `locatorSiteMergeBlocks` is a new COMMON entry; NeoForge adds it to an existing `rancraft-common.toml` at its default (as it did for the slice 3 entries) |
| In game | not run by the agent (no `runClient`); the checks are in `PHASE_3.md`, "Phase 3A review round 1" |

---

## Phase 3A summary — docs and tracker (row 5b)

Part 3A (the device framework and the Network Locator), with its precondition Vision Step 3a, is
complete in code. Every check that can run headless passes: `./gradlew build` **382 tests, 0
failed, 0 skipped**, `runGameTestServer` **5 of 5**. What is left needs a person at the client: the
route is "How to test Part 3A in game" at the end of `PHASE_3.md`. This section is the one-page view.
The detail stays in the slice sections above, which this step re-read against the code and the git
log.

Commits of this step: part 1 `0482a74` (two code-site labels, the lookup bound) and the docs commit
`2cc912f` "Phase 3A: docs and tracker" (two more code-site labels in `LocatorSolver`, one in `Ranging`, these
notes, the tracker with the in-game checklist, `VISION_STEP3.md`, `README.md`, `MILESTONES.md`). Part
2 was finished by a second docs agent after the first hit the usage limit with its edits
uncommitted; it kept them after checking each against the code and the scratch programs, and
added the review-round detail, the owner-decision table, the follow-up markers and the checked
checklist numbers.

### Slices and commits

| Row | What | Commits (tracker-only commits in brackets) | Unit tests after |
|---|---|---|---|
| 0 | Vision Step 3a: drive-test trail and CSV export | af20bb4 (30e5f23) | 203 |
| 0 gate | Every evaluation is sent (LINKS-only lens) | 3906418 (7b35d4f) | 210 |
| 1 | Datapack folders, harvest game test, purity test | 212f86c, 1471bf3 (ac36a74) | 219, 1 skipped |
| 0a | Trail cleared on logout, export link opens the folder | 0afda73, f2f8453 (4a2aae2) | 224, 1 skipped |
| 2 | `DeviceRequirement` | 4ab5967 | 231, 1 skipped |
| 3 | `Ranging`, `LocatorSolver` | 0837945 (99fc694, bd996d6) | 259, 1 skipped |
| 4 | `SignalDevice`, ticker refactor, meter port | a51e13a, 85bd051, 6558460 (6e1b982) | 299, 1 skipped |
| 5 | Network Locator (**3A ships**) | bf5eb47, e0fcfcd, 610a79c, 6c5d391 (334edff) | 367 |
| 3a | Owner decisions: extra starts kept, weighted ± | f61a739, b47bcbf (27a2328) | 369 |
| 5a | Phase 3A review round 1 fixes | 5221c46, 62fef10, 2f1306f, a755f7f, a998f3f (c54e86f) | 382 |
| 5b | This step | 0482a74, 2cc912f (the hash-recording commit after it) | 382 |

The one skipped test until slice 5 was `PackagePurityTest.utilIsPure`, waiting for `util` to exist.

### Regression gate (§3A.3): method and result

**Method** (full table in slice 4): a branch-by-branch checklist written from `bd996d6`'s
`SignalTicker`, the last commit before the refactor; a **differential run**, with the old ticker
copied into a throwaway test and run side by side with the new one (deleted before committing); and
the remaining inline decisions extracted unchanged into pure helpers and pinned.

**Result: passed.** The meter payload is byte-identical (3 of 3 stored fixtures, and 20,000 seeded
random samples through the real `STREAM_CODEC`); who is sent a sample is identical in all 49 lens
states; the lens link cut is identical on 5,000 random cases; the cache decision is pinned condition
by condition. Two differences, both intended: a device only in the hotbar now makes its carrier
evaluated (§3A.3 read literally; 13 of the 49 states with a hotbar-only device), and an armed
handover candidate older than one interval is dropped (the stale-candidate fix). The in-game half
(meter HUD, link rays, handover counter) is step 1 of the in-game checklist. A runtime test of the
ticker's per-player path is still not possible headless (a mock `ServerPlayer` negotiated no
channels; open follow-up for slice 8).

### Datapack folders (§0 known problem 3): before and after

| | Before (pre-1.21 names) | After (1.21 names) |
|---|---|---|
| Folders | `loot_tables/blocks/`, `tags/blocks/mineable/` | `loot_table/blocks/`, `tags/block/mineable/` |
| `runGameTestServer`, `HarvestGameTests` | 2 of 2 failed, Gradle exit 2 (no tag, bare-hand speed, no harvest, empty loot table) | 2 of 2 passed |
| Survival, iron pickaxe (computed from `getDestroyProgress`) | 15 s, drops nothing | 0.75 s, drops itself |

Kept as a permanent check (one generated test per block), now next to three Locator game tests.

### Measured numbers (Part 3A)

| What | Number | Where |
|---|---|---|
| Ranging resolution (c / BW) | band_700/900 29.98 m, band_1800 14.99 m, band_3500 2.998 m; σ 8.654 / 4.327 / 0.865 blocks | slice 3 |
| Test 7, 120° triangle | HDOP 1.1547 (2/√3) | slice 3 |
| Test 10, edge of a network + band_3500 | ± 35.49 → 5.12, **6.93x** (weighted ±; the spec's `HDOP x rms` gave 8.50, 4.17x; band_900 in the same spot 9.81, 3.62x) | owner decisions |
| Good triangle + one band_3500 cell | ± 9.99 → 7.15, **1.40x** (below 3x, accepted by the owner) | owner decisions |
| Extra Gauss-Newton starts | wrong FIX outside the footprint, centroid only 10.5-16.7 % → perfect ranges 0 %, band_900 1.3-5.2 % | slice 3 |
| Sites, not cells | wrong-mirror FIX 921 / 2000 → 0; one site AMBIGUOUS 1938 / 2000 → 0 (all RANGE ONLY); triangle of three-sector sites ± 6.45 vs rms error 10.21 → 10.42 vs 10.44 | review round 1 |
| Near-collinear towers (open) | middle mast 10 blocks off a 300-block line: **32 %** of FIX results on the wrong side, 139 blocks off, ± 11.6 reported (table below) | this step |
| Solve cost, synthetic ground | 18.8 µs per 8-cell mixed-band solve; 0.38 µs to range 8 cells | slice 3 |
| Solve cost, live level (`runGameTestServer`) | ground lookup 77.9 / 80.3 / 36.2 ns (three runs; it varies about 2x); one 8-cell band_1800 solve 22.2 / 29.0 / 29.4 µs, 37 lookups | slice 5, round 1, this step |
| Worst-case ground lookups per fix | **113** (7 x 16 + 1; slice 5 recorded 105, corrected here) = 4.1-9.1 µs at the measured lookup times | this step |
| Site grouping | 0.4-0.5 µs at 8-12 measurements, 7 µs at the configurable 64 | review round 1 |
| Replay (standing still) | no solve, no ground read | slice 5 |
| Evaluations | none added by the Locator; +1 per interval for a player whose only reason is a device in the hotbar | slices 4, 5 |
| Wire | `SignalSamplePayload` 425 / 176 / 83 bytes (4 cells / 1 / no service), +25 bytes for v3; `LocatorFixPayload` about 430 bytes (FIX, 8 rings), about 490 with an emergency record, once per interval while held | slice 0 gate, slice 5 |
| Drive-test walk at 0.43 blocks per sample, 100 blocks | 1 entry → 116 entries | review round 1 |
| Versions | `PROTOCOL_VERSION` "3" → "4" (slice 0) → "5" (slice 5); `SignalSamplePayload.VERSION` 2 → 3; `LocatorFixPayload` v1; `AntennaBlockEntity.DATA_VERSION` 2 (unchanged); `LensSettings` 5 of 6 codec fields | slices 0, 5 |

#### Near-collinear towers: a confident FIX on the wrong side (slices 2-3 gate finding, measured here)

The slices 2-3 gate reported that towers almost, but not exactly, in a line give a confident FIX on
the wrong side about a third of the time. Measured in this step with a scratch program compiled
outside the project against the current `rf` sources (flat ground, band_900 quantisation, default
parameters; three single masts at x = -150, 0, +150 on one line, the middle one pushed off it;
receiver anywhere with |x| < 120 and 20-120 blocks from the line, on either side; 4,000 seeded scenes
per row; "wrong side" is the side of the outer masts' line). Re-run in part 2 of this step,
recompiled against the `rf` sources as committed: identical to the digit.

| Middle mast off the line | FIX | wrong side (of FIX) | mean error of those | their mean ± reported | their mean HDOP | POOR GEOMETRY |
|---|---|---|---|---|---|---|
| 0 | 0 | – | – | – | – | 4000 |
| 2 blocks | 3949 | 1830 (46.3 %) | 142.4 | 11.4 | 1.31 | 51 |
| 5 blocks | 3881 | 1570 (40.5 %) | 141.2 | 11.7 | 1.36 | 119 |
| 10 blocks | 3909 | 1248 (31.9 %) | 139.3 | 11.6 | 1.34 | 91 |
| 20 blocks | 3854 | 679 (17.6 %) | 120.4 | 11.8 | 1.36 | 146 |
| 40 blocks | 4000 | 88 (2.2 %) | 84.1 | 12.3 | 1.42 | 0 |

With exact ranges the wrong-side rate is 0 at every offset, so this is measurement ambiguity, not a
convergence failure: the mirror image fits the 30-block-quantised ranges about as well, and the
rounding picks the side. Only an exact line is caught (singular from the centroid); the HDOP gate is
read at the estimate, where the lines of sight fan out. **Not fixed in this step** (a solver change
and a spec question): an open follow-up in `PHASE_3.md` with a candidate fix (also fit from the
mirrored start and report AMBIGUOUS or POOR GEOMETRY when both basins fit about equally). Labelled
at the code site (`LocatorSolver`, honest limits) in this step. How far off a wrong-side FIX lands
depends on where the receiver stands: roughly its mirror image across the line. One scene from
the in-game checklist (receiver 50 blocks from the line, middle mast 10 blocks toward it): FIX 70
blocks off, ± 10.5, HDOP 1.21; with the middle mast 10 blocks away from it, a correct FIX 4 blocks
off.

### Fidelity notes for Part 3A (§6), and where each is labelled

§6 asks for three lists. Below are the entries that concern Part 3A, and every label the
implementation added, each checked at its code site in this step.

**Real, modelled faithfully**

| Entry | Code site | Pinned by |
|---|---|---|
| Ranging resolution set by bandwidth, c / BW | `Ranging` class javadoc, `resolutionMeters` | `RangingTest` (test 2) |
| NLOS ranging bias in the right direction (ranges read long behind terrain, the fix moves away) | `Ranging.nlosBiasBlocks` | `LocatorSolverTest` (test 11) |
| Dilution of precision and the collinear-tower failure | `LocatorSolver` class javadoc, `hdopOf` | tests 6 and 7 |
| Altitude aiding, and where it breaks (caves, overhangs, flying, the Nether) | `LocatorSolver` honest limits, `LevelSurfaceProbe.forLocator`, `LocatorHudText` ("y ~") | `perfectRangesOnASlope`, `unloadedColumnsFallBack` |

**Abstracted, labelled at the code site**

| Entry | Code site |
|---|---|
| NLOS bias as a flat per-dB constant (`nlosBiasBlocksPerDb`, 0.25) instead of a longer reflected first path | `Ranging.nlosBiasBlocks` ("a flat stand-in"), config comment |
| Absolute ranging instead of time differences (like multi-RTT; no clock error, no positioning reference signals) | `Ranging` class javadoc, `LocatorSolver` honest limits, `NetworkLocatorItem` |
| "Network Locator", not GPS (E-CID, OTDOA, NR multi-RTT) | `Ranging`, `NetworkLocatorItem`, the item tooltip |
| The "±" is a reported uncertainty, not a guarantee (quantisation only; no NLOS bias, no mirror ambiguity) | `LocatorFix.Fix`, `LocatorSolver`, `LocatorHudText` |
| Quantisation is deterministic and not interpolated: a fixed error while standing still, not noise | `Ranging` (**labelled in this step**), `LocatorHudText` |
| The emergency record is network-derived emergency caller location, and stores the estimate | `EmergencyRecord`, `NetworkLocator.onLivingDeath` |
| Waypoints store estimates: navigation inherits fix error | `LocatorWaypoints` |
| Sites by horizontal proximity (`locatorSiteMergeBlocks`, 3.0) stand in for the site identity a real network knows | `LocatorSolver` class javadoc and `siteRepresentatives`, `LocatorParams`, config comment |
| Slant-range σ used for the horizontal ranges (under-states the ± only almost under a mast) | `LocatorSolver.weightedErrorOf` (**labelled in this step**) |
| Nearly collinear towers can give a confident wrong-side FIX | `LocatorSolver` honest limits (**labelled in this step**) |
| A device measures only while carried in a hand or the hotbar (a real UE measures all the time) | `SignalTicker.carried` (**labelled in this step**, 0482a74) |
| Dropping a stale armed handover candidate is not a 3GPP mechanism | `CellSelector.expireStaleCandidate` (**labelled in this step**, 0482a74) |
| A replayed sample is the old measurement, not a new one | `SignalDevice`, `DeviceContext` |
| The drive-test log is only as complete as the evaluations | `DriveTestLog.classify`, `SignalTicker.sendsSample` |

Two deviations from §3A.5 are owner-approved and labelled as such in the code: the extra
Gauss-Newton starts (`LocatorSolver.leastSquares`) and the weighted-fit ± (`weightedErrorOf`). One
spec-vs-tree correction is labelled too: `floor`, not `round`, for the altitude-aiding column
(`LocatorSolver.eyeY`).

**Deliberately absent** (§6; none of them is in Part 3A): load and capacity sharing, a scheduler,
cell sleep, per-user throughput, height gain in propagation. Also absent from the Locator, and said
so above: positioning reference signals, receiver clock error, sub-sample interpolation, multipath
beyond the flat NLOS bias, and averaging over time.

**Server authority** holds: the client never computes a range, a position, an HDOP or a "±". It
draws the server's payload and does map arithmetic between two server-supplied estimates (the
waypoint distance and bearing, `util/Navigation`).

### Review rounds

| Review | Reported | Outcome |
|---|---|---|
| Slice 0 gate (Vision 3a) | 1 major, 2 minor | Major (a LINKS-only lens evaluated but not sent: false handover pillars) fixed in 3906418; both minors (trail across worlds, export link opening Excel) fixed in 0a. A pre-existing defect found while fixing (the stale armed handover candidate, Phase 2) fixed in slice 4 part 1 |
| Slices 2-3 gate | 1 minor | Near-collinear wrong-side FIX: measured and labelled in this step, **open** for the owner |
| Slice 4 gate | 2 minor | The suggested `ReplayGuard` on the emergency stamp would have aged the record out for a player standing still: slice 5 never used one (a replay moves the fix's confirmation tick, and the stamp uses it; checked in the code). Two abstractions not labelled at their code sites: labelled in this step |
| Slice 5 gate | 4 minor | Dead player's Locator untested, old fix shown after re-selecting, saved ± at a hardcoded 1 m per block: fixed in review round 1 (fixes 8, 4, 5). Worst case 105 lookups undercounted: corrected to 113 in this step |
| Owner decisions (row 3a) | 2 slice 3 follow-ups | Extra solver starts kept; the ± is the weighted-fit covariance |
| Phase 3A review, round 1 (row 5a) | **11 reported: 9 confirmed and fixed, 2 rejected** (the 9 are 7 distinct defects: 3 = 7, 5 = 9) | All 9 applied: 1 major (the Locator counted cells, not sites), 6 minor. Three recorded refinements of the review's wording: test (2)'s "within 10 blocks" is not reachable by any correct solver at that spot (asserted within one ranging step instead); `putAway()` instead of `clear()`, to keep the learned cadence; a `FakePlayer` built directly rather than through `FakePlayerFactory`. The 2 rejected: below |
| Phase 3A review, rounds 2+ | not run | The workflow stopped after round 1 (usage limit), as RF Vision Step 2's review did |

#### Phase 3A review round 1 in one paragraph

Five reviewers, each with one brief: **rf-math** (`DeviceRequirement`, `Ranging`, `LocatorSolver`
against §3A.1, §3A.4, §3A.5), **ticker-regression** (`SignalTicker` before Phase 3 and after slice
0 against now: meter payload, link rays, handover counter, the cache skip while a candidate is
armed, `forget()`, the lens link cap, the stagger, the registry-version cache key, "links are never
starved", evaluated ⇒ sent, one evaluation per player, replays dispatched, devices idempotent, and
the stale-candidate fix never suppressing a legitimate handover), **mc-api-lifecycle** (every
Minecraft/NeoForge API used in Phase 3 against the decompiled sources), **net-protocol** (the new
and changed payloads) and **spec-conformance** (§0, §3A.1-§3A.6, the 3A tests and done-when, and
VISION_STEP3.md Part 3a). They reported 11 findings; an independent verifier per finding tried to
refute each against the code. **9 held** (the ticker-regression reviewer reported none) and were
fixed in row 5a, sections 1-8 above. **2 were rejected** as not defects in the current tree:

- *net-protocol:* `SignalSamplePayload`'s v3 decoder does not reject a wrong version, a bad cell
  count, a negative `cellsHeard` or a non-finite receiver position, although `LocatorFixPayload`
  rejects all of these. The facts were right, but nothing in the tree can make it fail (the list's
  initial capacity is capped at `MAX_CELLS`, and only the server writes this server-to-client
  payload): hardening and consistency, not a defect. A slice that changes this payload's format
  anyway can add the same checks at no extra cost.
- *rf-math:* the drive-test CSV writes the band id with RFC 4180 quoting only
  (`DriveTestLog.csvRow`), with no step that neutralises a leading spreadsheet-formula character.
  On an unmodified server the field is always a loaded band id, and a hostile server gains nothing
  it could not already do: speculative hardening, not a defect.

The ticker-regression brief also settles the RF Vision Step 2 follow-up (the Step 2 review never
re-covered the ticker's link path): that path was in its brief, and it found nothing. Recorded in
`PHASE_3.md` follow-ups as done.

### Owner decisions (Part 3A)

| Decision | Commit | What it changed |
|---|---|---|
| Keep the extra Gauss-Newton starts (a deviation from §3A.5's centroid-only start) | b47bcbf | Nothing in the code: labelled a deliberate, owner-approved deviation in `LocatorSolver.leastSquares` and above. Why: from the centroid alone 10.5-16.7 % of fixes outside the cells' footprint are wrong with a small ±, even on perfect ranges; with the extra starts 0 % on perfect ranges, 1.3-5.2 % on band_900 (genuine mirror ambiguity) |
| `errorBlocks` is the weighted-fit covariance `sqrt(trace((HᵀWH)⁻¹))`, W = diag(1/σ²) (a deviation from §3A.5's `HDOP x rms(σ)`) | f61a739, b47bcbf | Test 10 passes on the weighting (35.49 → 5.12, 6.93x at the edge of a network); a good triangle gains 1.40x (below 3x, accepted); identical to HDOP x σ when every cell has the same σ; HDOP stays unweighted |

Waiting for the owner (open follow-ups): the near-collinear wrong-side FIX; sites vs cells (§3A.5
says cells; keep the site grouping, and relabel the HUD "Sites N"?).

### Verification of this step

| Check | Result |
|---|---|
| `./gradlew build` | succeeds after part 1 and again after part 2; **382 passed, 0 failed, 0 skipped** (comment changes and one game-test constant; no test added or removed) |
| `rf` / `util` purity | `PackagePurityTest` (only comments changed in `rf`) |
| `./gradlew runGameTestServer` (after part 1; part 2 changed only comments and docs, no block or game test) | "5 tests are now running", "All 5 required tests passed"; locator cost line: 36.2 ns per ground lookup (113/113 columns loaded), worst case 113 lookups = 4.10 µs, one 8-cell band_1800 solve 29.4 µs with 37 lookups (FIX 6.51 blocks from the truth, ± 3.13, HDOP 0.72) |
| Near-collinear table | scratch program in the session scratchpad, compiled against the `rf` sources outside the project; nothing added to `src/`. Re-run in part 2: identical |
| In-game checklist numbers | a second scratch program ran the checklist's layouts through the current solver (flat ground, band_900 unless stated, receiver at the test spot). Step 2: one mast RANGE ONLY radius 89.9 (true about 100); two masts AMBIGUOUS, the near candidate 15 blocks off, `likely` -1; three masts FIX ± 10.53, HDOP 1.22, 1.8 blocks off; over 441 standing spots in a 21 x 21 square, the error was within the ± at 194 (44 %) and within twice it at all 441. Step 3: exact line POOR GEOMETRY (99.9). Step 4: + band_3500 50 north ± 10.53 → 7.18 (1.47x; band_900 there 9.24); wedge ± 35.66, HDOP 4.12 → 5.12 with band_3500 60 south (band_900 there 9.80). Step 5: a 3-block NLOS bias (12 dB) on the east mast moves the FIX 2.0 blocks away from it, 6 blocks (24 dB) 4.1 blocks; the ± stays 10.5-10.6. The first draft of the checklist said "usually within the ±", "about 3 blocks" and "around 140 blocks"; corrected to these numbers |
| In game | not run by the agent (no `runClient`); the route is at the end of `PHASE_3.md` |

### Open for Part 3A (details in `PHASE_3.md` follow-ups)

Decisions for the owner: the near-collinear wrong-side FIX; sites vs cells (§3A.5 says cells; also
whether the HUD should say "Sites"). Spec vs tree: EvaluationStats cannot count evaluations. Test
harness: no runtime test of the ticker's per-player path (fake connection needed; slice 8). Minor:
waypoint names; AMBIGUOUS marker heights; storing the scale on a waypoint; a same-dimension teleport
with the Locator in the hotbar can pick "likely" once; the meter's fixed 5 s stale limit; a proxy
server switch keeps the drive-test trail; a game test run with nothing registered still passes;
the optional slice 17 CSV columns. Standing rules for later slices: `rf`/`util` purity, and a loot
table and pickaxe tag for every new block. And every in-game check (`PHASE_3.md`, "How to test
Part 3A in game", 11 steps).

---

## Slice 6 — mast columns (§3B.1)

Part 3B's first slice closes §0 known problem 1: nine Signal Masts in one column were nine
co-channel cells shouting over each other (VISION.md's "(9 co-channel)" incident). Now a
contiguous column is one cell. Code checkpoint `703c3a1`; this section and the tracker land in the
slice commit "Phase 3 slice 6: mast columns".

### What was built

- **`util/ColumnScan`** (pure). `bounds(y, isMast, maxHeight)` walks down from `y` to the lowest
  contiguous mast (the base), then up to the highest, and returns `Bounds(baseY, topY, highestY)`:
  `topY` is the top of the signal part (at most `maxHeight` masts), `highestY` the top of the whole
  run, `radiatingY() = topY + 1`. Also `isBase(y, isMast)` (two reads), `mountingPole(bounds,
  isAntenna)` (an antenna directly above `highestY`) and `any(bounds, test)` (the power gate). Each
  walk is capped at 4096 steps, so a predicate that never says "no" cannot hang it.
  `PackagePurityTest.utilIsPure` checks it.
- **`block/MastColumn`**: `ColumnScan` applied to a `BlockGetter` (server level or client level),
  one x/z column, `MutableBlockPos` reads; `ownsCell` (base and not a pole) and
  `refresh(ServerLevel, pos)` (the re-scan).
- **`SignalMastBlock`**: `updateShape` for `UP`/`DOWN` and `neighborChanged` (after updating the
  mast's own `POWERED`) call `MastColumn.refresh`, on a server level only (never on the client or in
  a `WorldGenRegion`).
- **`SignalMastBlockEntity`**: `isTransmitting()` is true only for a column's base with no sector on
  top (and, with `requireRedstone`, any mast of the column powered); `radiatingPoint()` is the
  column's `top.above()` for a base; PCI planning waits until the mast is a base; a structure mast
  promoted to base gets a fresh plan; a base loaded from saved data reports its column to the
  census.
- **`AntennaBlockEntity`**: an `onAir` flag, set by `refreshRegistration` and pushed with
  `syncToClients()` when it changes; `OnAir` appended to the update tag only and read back on the
  client; a `readyForPciPlan()` hook (true except for a structure mast).
- **`RanCraftConfig.maxMastHeight`** (COMMON, default 64, range 1-4096), with an accessor that falls
  back to the default because the client's lens reads it too. Not in `RfConfig`: no `rf` code uses
  it.
- **RF Lens**: `LensRenderer` skips structure masts and mounting poles, draws one lobe per cell at
  its radiating point (the same scan the server does), and greys an off-air cell
  (`LensStyle.lobeRgb` / `lobeAlpha`: `OFF_AIR_RGB` 0x8C8C8C at half opacity).
- **`world/MastColumnCensus`**: the one-line log §3B.1 asks for.
- **`ModPayloads.PROTOCOL_VERSION` 5 → 6** (decision 6).
- **`gametest/MastColumnGameTests`** (8 game tests) on a new empty 3x14x3 template
  (`data/rancraft/structure/gametest/empty_3x14x3.nbt`: the 3x3x3 one with a taller size). No block
  was added, so no loot table or pickaxe tag changes.

### The rule, and where each part of §3B.1 lives

| §3B.1 | Where | Pinned by |
|---|---|---|
| A contiguous run is one site; a gap ends it | `ColumnScan.bounds` | `ColumnScanTest.nineStack`, `.gapInTheColumn`; game test `gaps_split_and_a_new_bottom_mast_takes_over` |
| The base owns the cell (`cellId = base.asLong()`) | only the base registers, and `cellId()` is still the entity's own position | game tests `nine_masts_are_one_cell`, `extending_keeps_id_and_pci` |
| Radiating point = `top.above()` | `SignalMastBlockEntity.radiatingPoint` | the same |
| Only the base registers | `SignalMastBlockEntity.isTransmitting` (structure: false) | `nine_masts_are_one_cell` (8 structure masts not transmitting, `OnAir` false) |
| A sector on top silences the column | `ColumnScan.mountingPole`, above `highestY` | `ColumnScanTest.antennaOnTop`; game test `sector_on_top_silences_the_column` |
| `requireRedstone`: any mast powered | `MastColumn.powered` (`ColumnScan.any`) | `ColumnScanTest.anyMastPowersTheColumn`; game test `any_powered_mast_powers_the_column` (only the top mast powered) |
| Height cap `maxMastHeight` | `ColumnScan.bounds` | `ColumnScanTest.heightCap`; game test `height_cap_limits_the_radiating_point` (cap 3, six masts) |
| Re-scan on `updateShape` / `neighborChanged`, refresh the base | `SignalMastBlock`, `MastColumn.refresh` | every game test that edits a column |
| Breaking the base promotes the next mast | follows from the rule; fresh PCI plan | `ColumnScanTest.breakingTheBasePromotesTheNextMast`; game test `breaking_the_base_promotes_the_next_mast` |
| Lens: one lobe per transmitting cell, at the new point | `LensRenderer` via `SignalMastBlockEntity.ownsColumnCell` | in game only (`PHASE_3.md`, slice 6) |
| `OnAir` in the update tag, off-air cells greyed | `AntennaBlockEntity.getUpdateTag`, `LensStyle` | `nine_masts_are_one_cell` (tag true / false, never saved); `LensStyleTest.offAirLobesAreGreyed` |
| Log once: "N stacked masts now form M columns; N−M masts stopped transmitting." | `MastColumnCensus` | `MastColumnCensusTest`; game test `saved_stack_is_logged_once` |

### Deviations and decisions

1. **`ColumnScan.bounds` returns a third value, `highestY`** (the spec writes `(baseY, topY)`).
   With the cap in play, `topY` is the top of the signal part, but "a sector antenna directly above
   the top mast" has to look above the *highest* mast (a sector cannot sit on a mast in the middle
   of the structure), and the power gate covers the whole column. The record is new, so nothing
   else is affected.
2. **The power gate and the pole test cover the whole run, structure above the cap included.**
   §3B.1 says "any mast in it"; the masts above the cap are still part of the tower, only not of its
   signal part. A sector on the highest mast of a capped column therefore still silences it (pinned
   by the cap game test).
3. **The re-scan refreshes two entities, not one**: the mast that received the shape or neighbour
   update, and its column's base. §3B.1 says "call refreshRegistration() on the base's block entity
   only", meaning not on every mast. But when a mast is placed *under* a base, the old base gets the
   update and must unregister (it is structure now) while the new base registers. Refreshing a
   structure mast costs its two-read `isBase` check and an unregister that does nothing, so the
   rule's intent (no per-mast work) holds. No other mast is touched.
4. **PCI planning waits until a mast is a base.** A mast placed on top of a column is structure:
   planning it would log a plan for a cell that never transmits, and the plan would be stale by the
   time (if ever) it became a base. `AntennaBlockEntity.readyForPciPlan()` holds the plan back; a
   transient `seenAsStructure` flag spots a structure mast that became a base while loaded (base
   broken, column split) and plans it then, so §3B.1's "it gets a fresh PCI plan" is literally true.
   Consequences, all intended: a base is planned on its first refresh, which while building a column
   by hand is when the second mast goes on (the plan uses the radiating point at that moment, a block
   or two below the final top: irrelevant against a 500-block planning radius); a base loaded from
   disk keeps its saved PCI. Sectors are planned exactly as before.
5. **Only extending upward keeps the cell.** A mast placed under the base becomes the new base: a
   new `cellId` (its position) and a fresh PCI plan, and the old base stops transmitting. That is
   §3B.1's design ("using the top as owner would re-plan the PCI every time someone adds a block"),
   applied to the downward case. Pinned by the gaps game test.
6. **`PROTOCOL_VERSION` 5 → 6, although §4 lists no bump for the update tag.** The update tag is
   part of the wire format, and the client reads it differently now: a slice 5 client on a slice 6
   server would draw a lobe on every stacked mast (none greyed), and a slice 6 client on a slice 5
   server one lobe where nine cells transmit. The project rule is one bump per wire change; this is
   one. No payload changed shape. `DATA_VERSION` stays 2: nothing persisted changed (`OnAir` is
   written in `getUpdateTag`, not `saveAdditional`; the game test checks the saved tag has no
   `OnAir`).
7. **`OnAir` starts true** on a new entity. On the server `refreshRegistration` sets it before any
   chunk is sent (the chunk-load refresh runs first); on the client an absent flag (a tag from a
   server that never sends it) draws the antenna as before. A crafted `block_entity_data` value is
   overwritten by the server's next refresh. Structure masts carry `OnAir` false; the lens does not
   draw them anyway.
8. **The census line is written per server run, per batch of loads.** Nothing persisted records
   that a world was already converted (slice 6 changes no save data), so a world saved after this
   slice logs the same line on every start: it describes the rule, not a migration step. A column is
   counted when its base loads *from saved data*, once per base position and dimension per run, and
   the line is written after 100 quiet ticks with the running total (chunks load a few at a time; a
   line per tower is the spam the spec asks to avoid). Mounting poles stopped transmitting
   altogether, the base included, so they get a second sentence ("K masts under P sector antennas
   are mounting poles now and stopped transmitting.") instead of distorting N−M. Singulars are
   handled ("1 column"). Caveat: a mast placed with copied data (creative pick-block with Ctrl) at
   the bottom of a column counts as loaded from data.
9. **`maxMastHeight` is COMMON, so it is not synced to clients** (NeoForge syncs SERVER configs
   only). The lens reads the client's own copy. In single player both sides read the same value; on
   a dedicated server whose admin changed the cap, a column taller than the lower of the two caps is
   drawn with its lobe at the client's cap. Recorded as a follow-up; a fix would send the server's
   value, or put the radiating point in the update tag.
10. **A config change applies at the next re-scan.** Changing `maxMastHeight` (or `requireRedstone`,
    as before) re-registers nothing by itself; the next block change at the column or the chunk's
    next load does.

### Known behaviours (not bugs)

- **Breaking the base promotes the next mast up**, which gets a new cell id and a fresh PCI plan
  (§3B.1). Neighbours see a new cell; the planner may or may not pick the old PCI again.
- **A single mast with a sector antenna directly on it is now a mounting pole and silent.** In a
  Phase 2 world both transmitted. The census names these. A sector *under* a mast does not count:
  the mast above it starts a column of its own.
- **The radiating point of a capped column sits inside a structure mast.** Masts are
  `noOcclusion()`, which the material table resolves to 0 dB, so the structure above the cap does
  not attenuate the column's own rays.
- A mast placed by a tool that skips shape updates (block flag 16) leaves its column stale until the
  next update at the column or the chunk's next load, which re-scans every antenna
  (`SignalTicker.onChunkLoad`).

### Honest-abstraction notes (also at the code sites)

- **No height term in the propagation model** (`ColumnScan` class javadoc). Log-distance path loss
  has no antenna-height term; Okumura–Hata does (a taller base-station antenna lowers the loss at
  every distance). In RANCraft a taller column helps only by lifting the radiating point clear of
  obstruction and, for a sector on its own mast, through the vertical pattern (its downtilt is aimed
  from higher up). In a voxel world obstruction is the dominant effect, so this is the right
  first-order behaviour, but it is not the whole story. It stays on §6's "deliberately absent" list
  (height gain in propagation).
- **A column is one site, and its masts are structure** (`SignalMastBlock` javadoc): a real tower is
  steel plus one antenna system per sector; here the tower is built from the same block that
  radiates, and only the base's entity carries the cell's configuration.
- **The mounting-pole rule is a game rule** (`SignalMastBlock` javadoc): a real pole can carry more
  than one antenna; here the sector on top replaces the omni, which keeps one cell per column.
- **The lens works the column out itself** (`LensRenderer` and `MastColumn` javadocs), from the
  blocks, which are public world data like the antenna's declared pattern. It never computes what
  anyone receives. `OnAir` (`AntennaBlockEntity.onAir`) is the antenna's own public state, like a lit
  furnace: the client learns *that* a cell is off the air, not why.
- **The height cap is a game rule** (the `maxMastHeight` config comment): a structural limit, not RF.

### Measured

- **Scan cost on live ground** (`MastColumnGameTests.nine_masts_are_one_cell`, 20,000 repetitions
  after a warm-up): **624 ns** per full scan of the nine-mast column from its base (11 block reads),
  **812 ns** from the sixth mast (16 reads), **124 ns** per `isBase` (2 reads): about 57 ns per
  read, so a 64-mast column (about 66 reads) costs roughly 4 µs per scan. A scan runs on a block
  change next to a mast (a handful per placement), on a chunk load (one full scan per base; a
  structure mast stops after two reads) and, on the client, about twice per drawn column per frame.
  Never per evaluation: the ticker reads the registry. Wall-clock in a shared JVM, an order of
  magnitude only.
- **Registry**: the nine-mast column registers **1 cell instead of 9**, so the tower is no longer a
  co-channel interferer to itself (VISION.md's "(9 co-channel)" from that tower becomes 0).
- **Census line** as logged in the game test run: "9 stacked masts now form 1 column; 8 masts
  stopped transmitting."

### Tests

- `ColumnScanTest` (12, new): single block; 9-stack seen from every mast; extending keeps the base;
  gap; breaking the base; antenna on top (and one block higher, and a pole of one mast); height cap
  (at, over and under it, cap 1, a nonsense cap, a huge cap); negative heights; the power gate over
  the whole run; no mast at y is an error; the walk is bounded; the read count (15 for a scan from
  the middle of a 9-stack, 2 for `isBase`).
- `MastColumnCensusTest` (3, new): §3B.1's sentence, a lone mast not counted, mounting poles named
  apart, singulars.
- `LensStyleTest` (+1): off-air lobes grey at half opacity, never fully transparent.
- `MastColumnGameTests` (8 game tests, new): `nine_masts_are_one_cell` (one cell, owned by the base,
  radiating from above the ninth mast; `OnAir` true on the base and false on the eight structure
  masts, in the update tag and never in the save; the scan cost); `extending_keeps_id_and_pci`;
  `sector_on_top_silences_the_column` (and back on the air without it);
  `breaking_the_base_promotes_the_next_mast`; `gaps_split_and_a_new_bottom_mast_takes_over` (two
  cells across a gap, one again when it is filled, a mast under the base takes over);
  `any_powered_mast_powers_the_column` (a redstone block beside the top mast only);
  `height_cap_limits_the_radiating_point` (cap 3; a sector on the highest mast still silences); and
  `saved_stack_is_logged_once`. The redstone and cap tests set the COMMON value in memory
  (`ConfigValue.set`, never `save`, so the file is untouched: afterwards it still reads
  `requireRedstone = false`, `maxMastHeight = 64`) and put it back, also on a failed assertion, in
  batches of their own because the value is global. The census test labels its harness abstraction
  in its javadoc: it hands each placed mast its own saved data before `onLoad`, the order a chunk
  load uses, because there is no Phase 2 save to load.

### APIs verified against sources (new to this codebase)

- `BlockBehaviour.updateShape(BlockState, Direction, BlockState, LevelAccessor, BlockPos, BlockPos)`
  (1.21.1 patched sources). `Level.markAndNotifyBlock` calls the neighbours' `updateShape` through
  `updateNeighbourShapes` unless flag 16 (`UPDATE_KNOWN_SHAPE`) is set, so a `POWERED` change with
  `UPDATE_CLIENTS` (2) also reaches the masts above and below (harmless: an idempotent re-scan).
- `LevelChunk.setBlockEntity` sets the entity's level before the neighbours are notified, and
  `addAndRegisterBlockEntity` defers `onLoad` through `Level.addFreshBlockEntities` (NeoForge) to the
  next block-entity tick. That is why a new bottom mast can be refreshed from its old base's
  `updateShape` before its own `onLoad`, and why the game tests wait a few ticks.
- `ChunkStatusTasks.full` (NeoForge patch): `GenerationChunkHolder.currentlyLoading` is set around
  `registerAllBlockEntitiesAfterLevelLoad` and the `ChunkEvent.Load` post, so the load-time refresh
  reading the loading chunk's own blocks does not wait on that chunk's future. A column never leaves
  its chunk.
- `ServerChunkCache.blockChanged` → `ChunkHolder.blockChanged` does nothing while the chunk is not
  ticking, so an `OnAir` sync during a chunk load sends nothing (the chunk packet carries the tag).
- `ModConfigSpec.ConfigValue.set` (NeoForge 21.1.251 sources): in memory, and in the cache for a
  no-restart value; written to the file only by `save()`.
- `BlockEntity.loadWithComponents` / `saveWithoutMetadata` (public final), `GameTestSequence`
  (`thenIdle`, `thenExecute`, `thenWaitUntil`, `thenSucceed`), `GameTestHelper.destroyBlock`
  (`destroyBlock(pos, false, null)`: no drops).

### Verification

| Check | Result |
|---|---|
| `./gradlew build` | **398 passed, 0 failed, 0 skipped** (382 + 12 + 3 + 1) |
| `rf` / `util` purity | `PackagePurityTest` passes with `ColumnScan` in `util` |
| `./gradlew runGameTestServer` | "13 tests are now running", "All 13 required tests passed" (8 new; the 5 existing unchanged); the scan-cost and census lines above are from that run |
| In game | not run by the agent (no `runClient`); the checks are in `PHASE_3.md`, slice 6 |
