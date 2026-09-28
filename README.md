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

See `VISION.md` for the full design of the vision feature, `VISION_STEP3.md` for what's planned
next (drive-test logging and automatic problem diagnosis), and `NOTES.md` for the full build log,
every tuning decision, every deviation from spec, and what's honestly modeled vs. simplified.

## Tests

```
.\gradlew.bat build
```

Runs the full `dev.rancraft.rf` unit test suite (pure Java, no Minecraft on the classpath) alongside
the mod compile.
