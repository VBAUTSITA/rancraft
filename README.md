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

See `VISION.md` for the full design of the vision feature, `VISION_STEP3.md` for Step 3 (the
drive-test log, and the planned automatic problem diagnosis), and `NOTES.md` for the full build log,
every tuning decision, every deviation from spec, and what's honestly modeled vs. simplified.

## Tests

```
.\gradlew.bat build
```

Runs the full `dev.rancraft.rf` unit test suite (pure Java, no Minecraft on the classpath) alongside
the mod compile.

```
.\gradlew.bat runGameTestServer
```

Starts a headless game test server, runs the in-game regression tests in `dev.rancraft.gametest`
(for now: every block drops itself when mined with an iron pickaxe in survival), and exits. The task
fails if any test fails. It is separate from `build`.
