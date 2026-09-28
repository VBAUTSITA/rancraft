# RANCraft — working notes for Claude

## Where the project is

- The mod lives in `C:\Users\k_ale\Downloads\rancraft`. The editor often opens in
  `C:\Users\k_ale\Downloads\testmod-template-26.3`, which is an unrelated Fabric scaffold. Never
  edit or run it.
- Minecraft 1.21.1, NeoForge 21.1.251, NeoGradle 7.1.39, Java 21 (toolchain auto-provisioned).
- GitHub: `https://github.com/VBAUTSITA/rancraft` (public). `main` is stable. Each phase gets its
  own branch (`phase-3`, ...) that is merged when the phase is done.

## Commands (run from the project root)

| | |
|---|---|
| `.\gradlew.bat build` | compile + full unit test suite. The gate for every change. |
| `.\gradlew.bat runClient` | dev client with the mod loaded (for the user to test by hand) |
| `.\gradlew.bat runServer` | dedicated server, headless; `run/server/logs/latest.log` |
| `.\gradlew.bat runGameTestServer` | in-game regression tests (`dev.rancraft.gametest`), headless; runs every test, then exits. The task fails if a required test fails. Not part of `build`. Log: `run/gameTestServer/logs/latest.log` |

## Machine constraints

- ~7 GB RAM. Run **one** Gradle build at a time. Never run two builds or a build next to
  `runClient`. If the decompile step ever OOMs, `gradle.properties` already holds the fix
  (`decompiler.maxMemory=4g`); delete `build/neoForm/` to force a clean re-decompile.
- Write Java with the Write/Edit tools, not bash heredocs. Heredocs have mangled apostrophes here.
- Verify every Minecraft/NeoForge API against the decompiled sources rather than guessing:
  `build/neoForm/neoFormJoined1.21.1-*/steps/patchUserDev/outputs.jar` (read with python `zipfile`).

## Ground rules (binding across phases)

1. `dev.rancraft.rf` has **zero** `net.minecraft` / `net.neoforged` / `com.mojang` imports. All RF
   math lives there and is unit-tested headless.
2. **The server is authoritative.** The client never computes RSRP, SINR, obstruction, serving cell
   or coverage. It may render an antenna's *declared* configuration; it may never compute what you
   *receive*.
3. Changes to public `rf` records are **additive**: append components, never remove or reorder.
   Bump the payload version / `ModPayloads.PROTOCOL_VERSION` when a wire format changes.
4. Everything tunable goes in JSON datapacks or `RanCraftConfig` (ModConfigSpec). No new hardcoded
   Java tables.
5. Server budget: ≤ 1 ms average per evaluation. Measure it, do not assume it.
6. Label every game abstraction honestly, in code comments and in `NOTES.md`. This is a teaching
   tool for real RAN concepts; an unlabelled simplification teaches a wrong thing.

## Docs to keep current

- `NOTES.md`: running log. Append a section per phase: deviations from spec, tuning decisions,
  measured numbers, bugs found and fixed.
- `VISION.md` / `VISION_STEP3.md`: the RF Lens feature design.
- `README.md`: how to run. Keep the folder + command at the top.

## Status at the start of Phase 3

- Phases 1–2 and RF Vision Steps 1–2 are done and pushed; 161 tests green.
- RF Vision Step 2's adversarial review stopped after round 1 (account usage limit). Round 1
  fixes are applied; rounds 2+ never ran.
- RF Vision Step 3a has a pure `DriveTestLog` + 19 tests in the session scratchpad, not yet in `src/`.
- Known wart: stacked Signal Masts are N independent cells. Phase 3's "stack masts for height"
  should collapse a contiguous stack into one cell.
