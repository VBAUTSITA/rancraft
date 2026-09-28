package dev.rancraft.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The export's file naming and its chat link. Pure string and component work; no game, no file system. */
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

    /**
     * Double-click opening a CSV in Excel on a comma-decimal locale (es-PE) misreads it, so the link
     * must never hand the CSV itself to the OS. It shows the file name and opens the folder, as
     * vanilla's profiler link does. A relative game directory ({@code .}) still gives an absolute,
     * normalised folder.
     */
    @Test
    @DisplayName("the chat link shows the file name but opens the drivetests folder, not the CSV")
    void linkOpensTheFolder() {
        String name = "drivetest-20260928-143012-minecraft_overworld.csv";
        Path file = Path.of(".", "rancraft", "drivetests", name);

        Component link = DriveTestCommands.fileLink(file);

        assertEquals(name, link.getString());
        assertTrue(link.getStyle().isUnderlined());
        ClickEvent click = link.getStyle().getClickEvent();
        assertNotNull(click);
        assertEquals(ClickEvent.Action.OPEN_FILE, click.getAction());
        String expectedFolder = Path.of("").toAbsolutePath().resolve("rancraft").resolve("drivetests").toString();
        assertEquals(expectedFolder, click.getValue());
        assertTrue(Path.of(click.getValue()).isAbsolute());
        assertFalse(click.getValue().endsWith(".csv"), "the link must not open the CSV itself");
    }
}
