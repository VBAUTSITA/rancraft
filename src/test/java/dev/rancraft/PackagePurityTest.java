package dev.rancraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The pure packages stay pure: {@code dev.rancraft.rf}, and {@code dev.rancraft.util} once it
 * exists, reference no {@code net.minecraft}, {@code net.neoforged} or {@code com.mojang} code
 * (CLAUDE.md ground rule 1, {@code PHASE_3_PROMPT.md} §2). That is what lets the RF maths be
 * unit-tested headless and drawn by the client from the very object the server evaluates.
 *
 * <p>Until Phase 3 this was checked by hand with grep. {@code PHASE_3_PROMPT.md} §2 says to "extend
 * the existing zero-import grep assertion", but none existed; this test is that assertion.
 *
 * <p>It scans the source files, not the compiled classes: any import (static or not, wildcard or
 * not) and any fully qualified name in code fails, including one inside a string literal, because
 * {@code Class.forName("net.minecraft...")} is a dependency too. Comments do not count, so a javadoc
 * that mentions Minecraft in prose is fine.
 *
 * <p>It also fails on a reference to any other {@code dev.rancraft} package (the root package
 * included). Every other package is game code, so such an import would bring Minecraft in one step
 * removed. The unit tests would not notice: NeoForge is on the test classpath too
 * ({@code implementation} dependencies are). {@code rf} and {@code util} may use each other.
 *
 * <p>The package roots are found from the working directory, walking up to the folder that holds
 * {@code src/main/java/dev/rancraft/RanCraft.java}. Gradle runs tests in the project directory,
 * but an IDE may not.
 */
class PackagePurityTest {

    private static final String ANCHOR = "src/main/java/dev/rancraft/RanCraft.java";
    private static final String RF = "src/main/java/dev/rancraft/rf";
    private static final String UTIL = "src/main/java/dev/rancraft/util";

    /**
     * {@code net.minecraft}, {@code net.neoforged} or {@code com.mojang} as whole identifiers. Java
     * allows whitespace around the dot of a qualified name, so the pattern does too.
     */
    static final Pattern GAME_PACKAGE =
            Pattern.compile("\\b(?:net\\s*\\.\\s*(?:minecraft|neoforged)|com\\s*\\.\\s*mojang)\\b");

    /** A {@code dev.rancraft} name outside the pure packages {@code rf} and {@code util}. */
    static final Pattern GAME_SIDE_PROJECT_PACKAGE =
            Pattern.compile("\\bdev\\s*\\.\\s*rancraft\\s*\\.\\s*(?!(?:rf|util)\\b)[A-Za-z_$]");

    /** What a pure package may not name: either of the above. */
    static final Pattern FORBIDDEN =
            Pattern.compile(GAME_PACKAGE.pattern() + "|" + GAME_SIDE_PROJECT_PACKAGE.pattern());

    /** One offending source line. */
    record Violation(String file, int line, String text) {
        @Override
        public String toString() {
            return file + ":" + line + ": " + text;
        }
    }

    // ---- the assertions -------------------------------------------------------------------------

    @Test
    @DisplayName("dev.rancraft.rf references no net.minecraft, net.neoforged or com.mojang code, nor game-side RANCraft packages")
    void rfIsPure() throws IOException {
        Path root = projectRoot(Path.of(System.getProperty("user.dir")));
        Path rf = root.resolve(RF);
        assertTrue(Files.isDirectory(rf), "missing " + rf);
        assertPure(root, rf);
    }

    @Test
    @DisplayName("dev.rancraft.util references no net.minecraft, net.neoforged or com.mojang code, nor game-side RANCraft packages (skipped until util exists)")
    void utilIsPure() throws IOException {
        Path root = projectRoot(Path.of(System.getProperty("user.dir")));
        Path util = root.resolve(UTIL);
        Assumptions.assumeTrue(Files.isDirectory(util),
                "dev.rancraft.util does not exist yet; PHASE_3_PROMPT.md §3B.1 creates it for ColumnScan");
        assertPure(root, util);
    }

    // ---- the scanner cannot pass by being blind ---------------------------------------------------

    @Test
    @DisplayName("the scanner catches imports, static imports, qualified names in code and reflective names in strings")
    void scannerCatchesReferences() {
        String source = String.join("\n",
                "package dev.rancraft.rf;",
                "import net.minecraft.core.BlockPos;",
                "import static com.mojang.logging.LogUtils.getLogger;",
                "class A {",
                "    net.neoforged.bus.api.Event event;",
                "    Object zero = net . minecraft.world.phys.Vec3.ZERO;",
                "    Class<?> c = Class.forName(\"net.minecraft.client.Minecraft\");",
                "    Object codec = com.mojang.serialization.Codec.INT;",
                "}");
        assertEquals(List.of(2, 3, 5, 6, 7, 8), lines(scan("A.java", source)));
    }

    @Test
    @DisplayName("a game-side RANCraft package counts, rf and util do not")
    void scannerCatchesGameSideProjectPackages() {
        String source = String.join("\n",
                "package dev.rancraft.rf;",
                "import dev.rancraft.rf.sub.Pure;",
                "import dev.rancraft.util.ColumnScan;",
                "import dev.rancraft.world.LevelWorldProbe;",
                "import dev.rancraft.RanCraft;",
                "import static dev.rancraft.RanCraftConfig.COMMON;",
                "class E {",
                "    dev.rancraft.item.RfLensItem lens;",
                "    dev . rancraft . rf . Band band;",
                "    Object a = dev.rancraft.rfx.Thing.X, b = dev.rancraft.utility.Thing.Y;",
                "    Object mydev = mydev.rancraft.world;",
                "}");
        assertEquals(List.of(4, 5, 6, 8, 10), lines(scan("E.java", source)));
    }

    @Test
    @DisplayName("comments, prose and look-alike identifiers do not count")
    void scannerIgnoresCommentsAndLookAlikes() {
        String source = String.join("\n",
                "/** Pure: no Minecraft. Unlike {@link net.minecraft.world.phys.Vec3}, this is a record. */",
                "// import net.neoforged.bus.api.SubscribeEvent;",
                "/* com.mojang.logging.LogUtils",
                "   spans lines: net.minecraft */",
                "class B {",
                "    Object internet, minecraft, mojang;",
                "    Object a = internet.minecraft, b = telecom.mojang;",
                "}");
        assertEquals(List.of(), scan("B.java", source));
    }

    @Test
    @DisplayName("a string, char or text block that looks like a comment does not hide the code after it")
    void literalsDoNotOpenComments() {
        String source = String.join("\n",
                "class C {",
                "    String a = \"/* not a comment\"; net.minecraft.core.BlockPos p;",
                "    String b = \"// nor this\"; com.mojang.serialization.Codec<?> c;",
                "    char q = '\"'; net.neoforged.fml.ModList m;",
                "    char e = '\\''; String t = \"\"\"",
                "        // inside a text block",
                "        \"\"\"; com.mojang.datafixers.util.Pair<?, ?> pair;",
                "}");
        assertEquals(List.of(2, 3, 4, 7), lines(scan("C.java", source)));
    }

    @Test
    @DisplayName("line numbers survive a multi-line javadoc and CRLF line endings")
    void lineNumbersSurviveCommentsAndCrlf() {
        String source = "/**\r\n * Doc.\r\n */\r\nclass D {\r\n    net.minecraft.core.BlockPos p;\r\n}\r\n";
        List<Violation> found = scan("D.java", source);
        assertEquals(List.of(5), lines(found));
        assertEquals("net.minecraft.core.BlockPos p;", found.get(0).text());
    }

    @Test
    @DisplayName("the project root is found from a nested working directory")
    void rootFoundFromNestedDirectory(@TempDir Path temp) throws IOException {
        Path project = temp.resolve("rancraft");
        Files.createDirectories(project.resolve(ANCHOR).getParent());
        Files.writeString(project.resolve(ANCHOR), "package dev.rancraft;");
        Path nested = Files.createDirectories(project.resolve("build/tmp/test/work"));

        assertEquals(project.toAbsolutePath().normalize(), projectRoot(nested));
        assertEquals(project.toAbsolutePath().normalize(), projectRoot(project));
    }

    @Test
    @DisplayName("no project root fails loudly rather than checking nothing")
    void noRootFailsLoudly(@TempDir Path temp) {
        // A made-up anchor: the JVM's temp directory may itself sit inside this project (build/tmp).
        String nowhere = "no-such-project-" + UUID.randomUUID() + "/Anchor.java";
        assertThrows(AssertionError.class, () -> findUp(temp, nowhere));
    }

    // ---- implementation -------------------------------------------------------------------------

    /** The nearest directory at or above {@code start} that holds the mod's main class. */
    static Path projectRoot(Path start) {
        return findUp(start, ANCHOR);
    }

    /** The nearest directory at or above {@code start} containing the file {@code anchor}. */
    static Path findUp(Path start, String anchor) {
        Path from = start.toAbsolutePath().normalize();
        for (Path dir = from; dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve(anchor))) {
                return dir;
            }
        }
        throw new AssertionError("no RANCraft project (" + anchor + ") at or above " + from);
    }

    private static void assertPure(Path root, Path packageRoot) throws IOException {
        List<Path> sources;
        try (Stream<Path> walk = Files.walk(packageRoot)) {
            sources = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        assertFalse(sources.isEmpty(), "no .java files under " + packageRoot + ", so nothing was checked");

        List<Violation> violations = new ArrayList<>();
        for (Path file : sources) {
            String name = root.relativize(file).toString().replace('\\', '/');
            violations.addAll(scan(name, Files.readString(file, StandardCharsets.UTF_8)));
        }
        assertTrue(violations.isEmpty(), () -> violations.size() + " forbidden reference(s) in a pure package ("
                + sources.size() + " files scanned):\n"
                + violations.stream().map(Violation::toString).collect(Collectors.joining("\n")));
    }

    /** Every line of {@code source} whose code (comments blanked) names a forbidden package. */
    static List<Violation> scan(String fileName, String source) {
        String code = blankComments(source);
        String[] sourceLines = source.split("\n", -1);
        List<Violation> found = new ArrayList<>();
        Matcher matcher = FORBIDDEN.matcher(code);
        int lastLine = -1;
        while (matcher.find()) {
            int line = lineOf(code, matcher.start());
            if (line != lastLine) {
                found.add(new Violation(fileName, line, sourceLines[line - 1].strip()));
                lastLine = line;
            }
        }
        return found;
    }

    /**
     * {@code source} with every comment character replaced by a space, and every newline, string,
     * char and text-block literal kept, so offsets and line numbers still match the original.
     */
    static String blankComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int n = source.length();
        int i = 0;
        while (i < n) {
            char c = source.charAt(i);
            char next = i + 1 < n ? source.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && next == '*') {
                out.append("  ");
                i += 2;
                while (i < n && !(source.charAt(i) == '*' && i + 1 < n && source.charAt(i + 1) == '/')) {
                    char ch = source.charAt(i);
                    out.append(ch == '\n' || ch == '\r' ? ch : ' ');
                    i++;
                }
                if (i < n) {
                    out.append("  ");
                    i += 2;
                }
            } else if (source.startsWith("\"\"\"", i)) {
                out.append("\"\"\"");
                i += 3;
                while (i < n && !source.startsWith("\"\"\"", i)) {
                    i = copyChar(source, i, out);
                }
                if (i < n) {
                    out.append("\"\"\"");
                    i += 3;
                }
            } else if (c == '"' || c == '\'') {
                out.append(c);
                i++;
                while (i < n && source.charAt(i) != c && source.charAt(i) != '\n') {
                    i = copyChar(source, i, out);
                }
                if (i < n && source.charAt(i) == c) {
                    out.append(c);
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** Copies one literal character, or a backslash escape as a pair; returns the next index. */
    private static int copyChar(String source, int i, StringBuilder out) {
        if (source.charAt(i) == '\\' && i + 1 < source.length()) {
            out.append(source, i, i + 2);
            return i + 2;
        }
        out.append(source.charAt(i));
        return i + 1;
    }

    private static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static List<Integer> lines(List<Violation> violations) {
        return violations.stream().map(Violation::line).toList();
    }
}
