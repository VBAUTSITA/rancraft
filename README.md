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
a dev client with RANCraft loaded. Look for the **RANCraft** tab in the creative inventory; in
survival, everything is craftable (see the recipe book).

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
  before death".
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
- **Phase 3C** (in progress) — infrastructure and progression:
  - *Radio tiers:* a Sector Antenna's radio takes band_700, band_900 and band_1800; band_3500 needs a
    **Wideband Radio Unit** (right-click the sector with it; breaking the sector drops it again). The
    configuration screen shows band_3500 greyed with "needs Wideband Radio Unit" until then. A sector
    already on band_3500 in an older world keeps it. The RF Lens is never tier-gated.
  - *Backhaul:* a **Core Site** (the core network; cells and dishes within `fiberRadiusBlocks`, 24,
    are on fiber), **Backhaul Dishes** (a dish within `siteRadiusBlocks`, 8, of a cell's base serves
    it) and the **Link Tool** (use it on one dish, then on the far one, to pair them; sneak + use
    unpairs). The server measures each 18 GHz hop on the real terrain (free-space loss, line of sight,
    first Fresnel zone clearance, rain fade in rain and thunderstorms; figures in
    `data/rancraft/rf/backhaul/microwave.json`): UP, DEGRADED or DOWN. Use a dish to read its hop's RSL
    and margin. With the RF Lens on (lobes layer), hops are drawn green, orange or red. With
    `requireBackhaul` on (COMMON config, **off by default**, so existing worlds are unaffected), a cell
    with no path to a Core Site goes off the air, and one reached only over a DEGRADED hop caps the
    devices it serves at FAIR (the meter shows `BH: LIMITED (capped FAIR)`). `/rancraft backhaul status
    [radius]` lists off-air and limited cells and every link's figures. Also `backhaulRecomputeTicks`
    and `enableRainFade`.
  - *Storage Terminal:* opens a chest or barrel in the data centre over the mobile network.
    - **Binding:** sneak + use it on a chest or barrel within `fiberRadiusBlocks` (24) of a Core Site.
    - **Opening:** use it in the air. The chest's ordinary screen opens if you have GOOD service on
      band_1800 or band_3500 (carry it in a hand or the hotbar for a moment first), the chest's chunk
      is loaded and it is in your dimension. Otherwise the action bar says why ("storage unreachable",
      "needs band tier 2", "signal too weak", "backhaul limited").
    - **Losing the session:** the screen closes with "connection lost" when you lose that service, as
      a download stops.
    - It never loads a chunk and never lifts the chest's lid.
  - *Proximity Scanner:* held with GOOD service on band_3500, it lists the hostile mobs within 24
    blocks on the HUD (not radar: a stand-in for a sensor feed only a high-capacity link can carry);
    otherwise it says why ("needs tier 3, you're on band_1800 (tier 2)").
  - *Power:* with `requirePower` on (COMMON config, **off by default**, so existing worlds are
    unaffected), a cell on the air draws energy every tick: `baseFe + 20 x P_rf_W / 0.3` FE/t (a sector
    at 20 dBm about 10.7, at 30 dBm about 70.7: 10 dB more power is 10x the amplifier's energy). Its
    buffer (10,000 FE; a mast column's base holds it) runs out and the cell goes off the air (the lens
    greys it), back on above 10 %. A **Site Generator** burns furnace fuel into 40 FE/t, only while
    the antennas next to it take it, and feeds any mast of a tower; use it with fuel to fill its slot
    (hoppers work too), sneak + use with an empty hand to take it out, use to see its state.
    `/rancraft power status [radius]` lists each cell's draw, buffer and state.
  - *Recipes:* every block and item is craftable in survival (shaped recipes in
    `data/rancraft/recipe/`, data only, so a datapack can retune them). Early: Field Test Meter,
    RF Lens (deliberately cheap: copper, glass, an amethyst shard), Signal Mast (4 per craft), Network
    Locator, Radio Link Transmitter and Receiver. Mid: Sector Antenna (from a Signal Mast), Core Site,
    Backhaul Dish, Link Tool, Site Generator. Late-mid: Wideband Radio Unit, Storage Terminal. Late:
    Proximity Scanner (a sculk sensor). The recipe book shows each one once you hold a key ingredient or
    what it builds on (copper shows the meter, the lens and the mast; a Signal Mast shows the network
    blocks). Every block drops itself to a pickaxe.

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
and survive a chunk reload; microwave backhaul chains go LIMITED and off the air as specified, and a
marginal hop drops in a thunderstorm and recovers; a 30 dBm sector burns about 7x the fuel of a 20
dBm one and an empty cell goes off the air until its buffer is above 10 %; the costs are logged too;
48 tests in Phase 3C slice 15), and exits. The task fails
if any test fails. It is separate from `build`.

Phases 3A and 3B are complete in code and every headless check passes; what still needs a person
at the client is listed step by step at the end of `PHASE_3.md` ("How to test Part 3A in game",
"How to test Part 3B in game").
