package dev.rancraft.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The export's file naming. Pure string work; no game, no file system. */
class DriveTestCommandsTest {

    @Test
    @DisplayName("the export is named drivetest-<stamp>-<dimension>.csv")
    void fileNameShape() {
        assertEquals("drivetest-20260928-143012-minecraft_overworld.csv",
                DriveTestCommands.fileName("20260928-143012", "minecraft:overworld", 0));
    }

    @Test
    @DisplayName("a second export in the same second gets a numeric suffix instead of overwriting")
    void fileNameSuffix() {
        assertEquals("drivetest-20260928-143012-minecraft_the_nether-2.csv",
                DriveTestCommands.fileName("20260928-143012", "minecraft:the_nether", 2));
    }

    @Test
    @DisplayName("dimension ids become file-safe on every OS, including datapack paths with slashes")
    void fileSafe() {
        assertEquals("minecraft_the_end", DriveTestCommands.fileSafe("minecraft:the_end"));
        assertEquals("mypack_worlds_moon.base-1", DriveTestCommands.fileSafe("MyPack:worlds/moon.base-1"));
        assertEquals("a_b_c_d", DriveTestCommands.fileSafe("a\\b*c?d"));
    }
}
