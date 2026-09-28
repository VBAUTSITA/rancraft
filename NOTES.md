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
