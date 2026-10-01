# RANCraft — milestones and what is left

A map of the whole project: where it started, every landmark crossed, and what still stands
between here and "Phase 3 done". Detailed engineering notes live in `NOTES.md`; the Phase 3
checklist lives in `PHASE_3.md`.

**Where we are (2026-10-01):** Phases 1 and 2, RF Vision Steps 1–3a, and all the code for Phase 3
Parts A and B are done, reviewed and documented. **485 unit tests** and **30 game tests** pass.
Phase 3 Part C has not started.

```
Phase 1 ████████████ done
Phase 2 ████████████ done
Vision  ██████████░░ Steps 1, 2, 3a done · 3b designed, not built
Phase 3 ████████░░░░ Parts A and B done (in-game checks pending) · Part C not started
```

---

## Run it

```
cd C:\Users\k_ale\Downloads\rancraft
.\gradlew.bat runClient
```

Use **only** the `rancraft` folder. The `testmod-template-26.3` folder is an unrelated Fabric
scaffold (it opens a "Minecraft 26.3" window with none of our items).

GitHub: https://github.com/VBAUTSITA/rancraft. `main` is Phases 1–2 and Vision 1–2; the
`phase-3` branch has everything since.

---

## Landmarks crossed

### 1. Phase 1: can I hear this tower? (≈2026-09-20)

- Pure-Java RF engine (`dev.rancraft.rf`) with **no Minecraft code in it**, so the physics is
  unit-tested without the game. Log-distance path loss + voxel ray-marched obstruction.
- Signal Mast, Field Test Meter, live HUD with RSRP and bars. 18 tests.
- **The toolchain had never built.** The Minecraft decompile ran out of memory and left a broken
  `Blocks.java`. Once that was fixed, `gradlew build` passed for the first time in this repo.

### 2. Phase 2: did I plan this network well? (≈2026-09-20/21)

- Sector Antenna with the real 3GPP antenna pattern (azimuth, tilt, beamwidth; gain derived from
  beamwidth so "wide and strong" can't be had for free).
- 4 bands (700 / 900 / 1800 / 3500 MHz), SINR with co-channel interference, PCI planning with
  collision/confusion/mod-3 checks, A3 handover with hysteresis, antenna config GUI with a live
  pattern preview. 75 tests.
- Performance: **0.0077 ms per evaluation** (budget 1 ms); 56% of cells are pruned before any ray
  is cast.
- Range cap raised 600 → 1400 blocks so band choice actually changes reach.

### 3. First in-game test and the bugs it found (≈2026-09-21)

- **Signal Masts all got PCI 0** (only sectors asked for a PCI), so every two masts "collided".
  Fixed.
- **"SINR −6 dB, NO SERVICE" next to a strong signal** was correct: nine masts stacked in one
  column are nine cells shouting over each other. That incident is why stacked masts become one
  cell in Phase 3B.

### 4. RF Vision: seeing radio (2026-09-22 → 09-24)

- **Step 1:** RF Lens (helmet slot) draws every antenna's radiation pattern as a 3D wireframe lobe.
- **Step 2:** link rays (which cells hit you, and where terrain eats the signal), best-server
  coverage painted on the ground, band/layer cycling on keys **B** / **V**.
- The governing rule, used ever since: *the client may draw an antenna's declared pattern; it may
  never compute what you receive.* The server owns every measurement.

### 5. On GitHub (2026-09-28 00:54)

- Public repo created, first commit `ebb97a0`. Caught and excluded 126 compiled `.class` files
  that were about to be committed.

### 6. Phase 3 set up (2026-09-28 01:09)

- `CLAUDE.md` (project rules for every agent), `phase-3` branch, spec saved as `PHASE_3_PROMPT.md`,
  checklist as `PHASE_3.md`.
- Scout found **the datapack bug**: Minecraft 1.21 renamed data folders, so masts and sectors
  dropped nothing in survival. Nobody had noticed because all testing was in creative.

### 7. Phase 3, Part A: devices and the Network Locator (2026-09-28 → 09-29)

| Slice | What it gave you | Commit |
|---|---|---|
| 0 | **Drive-test trail**: markers where the server measured you, pillars at handovers, CSV export (`/rancraftc drivetest export`) | `af20bb4` |
| 1 | **Blocks drop in survival.** A game test failed 2/2 on the old folders and passes on the new ones (iron pickaxe: 15 s and no drop → 0.75 s and it drops). Plus an automatic check that the physics package stays Minecraft-free | `1471bf3` |
| 2 | Device requirements: "needs tier 3", "low quality", "no service" | `4ab5967` |
| 3 | Positioning maths: ranging from bandwidth, location solver, HDOP | `0837945` |
| 4 | Device framework; the tick loop rebuilt so any device shares one evaluation; stale-handover fix | `6558460` |
| 5 | **Network Locator**: HUD, rings, waypoints, emergency "last fix before death" | `6c5d391` |
| — | Your decisions: keep extra solver starts; use the proper weighted ± formula | `b47bcbf` |
| — | Review round: 11 findings, **9 real bugs fixed**, 2 rejected | `a998f3f` |

Real bugs caught along the way, each now fixed and tested:

- The HUD showed NO SERVICE near stacked masts even when you had a serving cell (it was cut from
  the packet).
- The trail drew a fake handover pillar after using the LINKS lens view.
- Putting the meter away mid-handover made the handover fire instantly when you took it back out.
- The Locator counted cells where it should count sites, among other review findings.

Tests: **161 at the start of Phase 3 → 382 now.**

### 8. Phase 3, Part B: towers and fixed devices (2026-09-30 → 10-01)

| Slice | What it gave you | Commit |
|---|---|---|
| 6 | **Mast columns**: nine stacked masts are one cell, radiating from the top; adding a mast keeps its PCI; a sector on top makes a mounting pole; the lens draws one lobe per cell | `5299fb4` |
| 7 | **Region epochs**: a block placed far from your link paths no longer forces everyone to recalculate; explosions and pistons now do invalidate | `9c6d39f` |
| 8 | **Fixed receivers**: blocks that listen to the network, evaluated in the background under a time budget | `7866ca9` |
| 9 | **Radio Link**: remote redstone over the network. About half the updates get lost at POOR service (SINR about 1 dB), none at FAIR. 200 links cost 0.03-0.06 ms per tick | `10c1de7` |
| — | Review round: 4 findings, **all 4 real and fixed**, 0 rejected | `48e4da8` |
| — | Docs, labels and the "How to test Part 3B in game" list | `b8c9299` |

Real bugs the review caught, each now fixed and tested:

- A chunk loading or unloading on a cached link path did not invalidate the cache, so a fixed
  receiver could keep a reading that saw a hill as air, forever.
- A Radio Link receiver in a chunk at the edge of the loaded area forced that chunk back to full
  load whenever a message arrived.
- Phase 1 masts in an old world could pick their PCI before their neighbours had loaded.
- On a dedicated server with a different height cap, the lens drew a tall column's lobe at the wrong
  height.

Tests: **382 → 485** unit tests, **5 → 30** game tests.

### 9. Process lessons

- The account **usage limit** stopped the agents several times, and the internet dropped twice.
  Nothing was lost: work is committed in small green steps and resumed from the last one.
- Messaging a running workflow agent spawns a duplicate of it, so that is never done any more.
- Switched to **lean mode** on 2026-09-29: one builder per slice, one review per part, about 3–4×
  cheaper, so each limit window gets further.

---

## What is left

### A. Finish Part A (running now)

- [x] All code, your two decisions, and the 9 review fixes. 382 tests green.
- [x] Docs: `PHASE_3.md` / `NOTES.md` updated, and "How to test Part 3A in game" written at the end
      of `PHASE_3.md` (`2cc912f`).
- [x] Pushed to GitHub (`phase-3`, through the Part B review fixes, `980e858`).

### B. Part 3B: towers and fixed devices (done; in-game checks pending)

- [x] **6 · Mast columns:** a stack of masts becomes **one** cell, radiating from the top. Adding a
      block keeps its PCI. Fixes the "(9 co-channel)" problem for good.
- [x] **7 · Region epochs:** a block placed far away stops forcing everyone to recalculate.
- [x] **8 · Fixed receivers:** blocks that listen to the network, evaluated cheaply in the
      background.
- [x] **9 · Radio Link:** remote redstone over the network. Flaky at POOR service, solid at FAIR.
- [x] Review (4 findings, all fixed) and docs: "How to test Part 3B in game" at the end of
      `PHASE_3.md`.
- [ ] Push the docs commits to GitHub.

### C. Part 3C: infrastructure and progression (7 slices, not started; the biggest part)

- [ ] **10 · Radio tiers:** band_3500 needs a Wideband Radio Unit (saves migrate to v3).
- [ ] **11 · Microwave backhaul maths:** link budget, Fresnel clearance, rain fade.
- [ ] **12 · Backhaul in game:** core site, dish, link tool; cells without backhaul go off air
      (off by default).
- [ ] **13 · Storage Terminal:** open a data-centre chest remotely while service is GOOD.
- [ ] **14 · Proximity Scanner:** the reward for deploying band_3500.
- [ ] **15 · Power:** antennas burn fuel; +10 dB Tx costs about 10× the energy (off by default).
- [ ] **16 · Recipes and loot tables:** everything craftable in survival.

### D. Wrap-up

- [ ] Merge `phase-3` into `main`.
- [ ] **Your in-game checks.** Each part's "How to test" list in `PHASE_3.md` covers the things
      only a person playing can confirm (rings render, trail pillars land in the right place,
      blocks drop, and so on).

### Plan to get there

Part C is next: seven slices, the biggest part. If the usage limit or the internet stops a run,
say **"continue"** and it picks up from the last green commit. Two small decisions from Part B can
wait for you (both in `PHASE_3.md` follow-ups): whether fixed devices should re-measure at least
every so often even when nothing nearby changed, and whether a Radio Link receiver should forget a
transmitter whose chunk has been unloaded for a long time.
