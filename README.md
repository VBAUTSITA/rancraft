# RANCraft

A Minecraft mod where antennas are real radio sites: real RF parameters, real signal propagation
computed against the voxel world, real SINR and interference, and a wearable lens to see it all.

## Run it

This folder — `rancraft` — is the mod. (If you also have a `testmod-template-26.3` folder on this
machine, that's an unrelated Fabric scaffold from a different Minecraft version; ignore it.)

From **this** folder, run:

```
.\gradlew.bat runClient
```

That downloads/decompiles Minecraft 1.21.1 + NeoForge on first run (slow, one-time), then launches
a dev client with RANCraft loaded. Look for the **RANCraft** tab in the creative inventory.

To run a headless dedicated server instead: `.\gradlew.bat runServer`

## Requirements

- Java 21 (Gradle will provision it automatically if it's missing)
- ~4 GB free RAM for the first build (the Minecraft decompile step)

## What's implemented

- **Phase 1** — Signal Mast, Field Test Meter, log-distance path loss + voxel ray-marched
  obstruction, RSRP/bars.
- **Phase 2** — Sector Antenna with real 3GPP antenna pattern (azimuth/tilt/beamwidth/gain), 4 radio
  bands, SINR with co-channel interference and PCI mod-3 penalty, PCI planning with collision
  detection, A3-style handover with hysteresis, an antenna configuration GUI with a live pattern
  preview.
- **RF Vision, Step 1** — wearable RF Lens: put it on and see every antenna's real radiation pattern
  as a 3D wireframe lobe in the world.
- **RF Vision, Step 2** — link rays (see exactly which cells are hitting you and where the signal is
  lost to terrain) and best-server coverage painting on the ground, with band and layer cycling
  (default keys `B` / `V`).
- **RF Vision, Step 3a** — drive-test trail: with the lens on, every server measurement leaves a
  marker where it was taken, coloured by service level, with pillars at handovers. Export it as CSV
  with `/rancraftc drivetest export` (written to `<game dir>/rancraft/drivetests/`; the chat link
  opens that folder), reset it with `/rancraftc drivetest clear`. The trail lasts one session:
  leaving the world clears it, so export first.
- **Phase 3A** — device framework and the **Network Locator** (cellular positioning, not GPS): held,
  it shows the position the network works out from timing ranges to the cells you hear (FIX with a
  ± and HDOP, or RANGE ONLY / AMBIGUOUS / POOR GEOMETRY / NO SIGNAL), draws the range rings in the
  world, saves waypoints of the *estimate* (sneak + use; use cycles them) and keeps a "last fix
  before death". Creative tab only for now (recipes come in Phase 3C).
- **Phase 3B** — towers and fixed devices:
  - *Mast columns:* Signal Masts stacked in one column are one cell, owned by the lowest mast (so
    adding masts on top keeps its PCI) and radiating from above the top one (at most
    `maxMastHeight`, default 64, masts count). A Sector Antenna on top turns the column into a
    mounting pole: the column goes quiet and the sector is its own cell. The lens draws one lobe per
    cell, at the height the server reports, and greys a cell the server reports off the air.
  - *Smarter caching:* a block placed or broken far from your link paths no longer forces a fresh
    measurement; one on a path (or an explosion, a piston, a tree growing, a chunk loading there)
    does.
  - *Radio Link Transmitter and Receiver:* remote redstone through the cell network. Give both the
    same address (0-15; use counts up, sneak + use counts down); the receiver outputs redstone while
    a transmitter on its address is powered. Both ends need POOR service or better, updates arrive
    about once a second by design, and at low SINR some are lost (a lost update leaves the old
    state; at FAIR or better the link is solid). The lamp on top lights while the block has service.
    Creative tab only for now (recipes come in Phase 3C).
- **Phase 3C** (in progress) — infrastructure and progression:
  - *Radio tiers:* a Sector Antenna's radio takes band_700, band_900 and band_1800; band_3500 needs a
    **Wideband Radio Unit** (right-click the sector with it; breaking the sector drops it again). The
    configuration screen shows band_3500 greyed with "needs Wideband Radio Unit" until then. A sector
    already on band_3500 in an older world keeps it. The RF Lens is never tier-gated. Creative tab
    only for now.
  - *Backhaul (the maths so far; the blocks come next):* an 18 GHz point-to-point microwave link
    budget (free-space loss, line of sight, first Fresnel zone clearance, rain fade; figures in
    `data/rancraft/rf/backhaul/microwave.json`) and the graph that decides whether each cell reaches a
    core network at full rate, limited, or not at all. New COMMON config: `requireBackhaul` (off by
    default, so existing worlds are unaffected), `fiberRadiusBlocks`, `siteRadiusBlocks`,
    `backhaulRecomputeTicks`, `enableRainFade`.

See `VISION.md` for the full design of the vision feature, `VISION_STEP3.md` for Step 3 (the
drive-test log, and the planned automatic problem diagnosis), and `NOTES.md` for the full build log,
every tuning decision, every deviation from spec, and what's honestly modeled vs. simplified.

## Tests

```
.\gradlew.bat build
```

Compiles the mod and runs the full unit test suite (382 tests at the end of Phase 3A, 485 at the
end of Phase 3B): the RF maths in `dev.rancraft.rf` and `dev.rancraft.util`, which is pure
Java and must stay free of Minecraft (`PackagePurityTest` fails the build otherwise), plus the
payloads, HUD text and device logic.

```
.\gradlew.bat runGameTestServer
```

Starts a headless game test server, runs the in-game regression tests in `dev.rancraft.gametest`
(30 at the end of Phase 3B: every block drops itself when mined with an iron pickaxe in survival;
the Network Locator's emergency record survives death and a dead player's Locator does nothing;
stacked masts form one cell per column, and extending, breaking, splitting, redstone and the height
cap behave as specified; block changes invalidate only the cached measurements whose paths they
touch; fixed receivers are evaluated, replayed, unloaded and reloaded correctly; Radio Links follow
their transmitters, drop about half their updates at POOR (SINR about 1 dB) and none at FAIR,
and survive a chunk reload; the costs are logged too), and exits. The task fails if
any test fails. It is separate from `build`.

Phases 3A and 3B are complete in code and every headless check passes; what still needs a person
at the client is listed step by step at the end of `PHASE_3.md` ("How to test Part 3A in game",
"How to test Part 3B in game").
