package dev.rancraft.client;

import dev.rancraft.RanCraft;
import dev.rancraft.rf.DriveTestLog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * {@code /rancraftc drivetest export|clear} (RF Vision Step 3a): client commands over the drive-test
 * log in {@link ClientDriveTest}.
 *
 * <p>Client commands because the log lives on the client: the server never keeps it, so there is
 * nothing for a server command to read, and exporting writes into the player's own game directory.
 * The root is {@code rancraftc} so it can never shadow the server's {@code /rancraft}.
 *
 * <p>{@code export} writes one RFC 4180 CSV per dimension that has samples, to
 * {@code <game dir>/rancraft/drivetests/drivetest-<yyyyMMdd-HHmmss>-<dimension>.csv}. One file per
 * dimension because Overworld and Nether coordinates are different places; mixing them in one table
 * would put rows side by side that describe unrelated ground. Numbers are always written with a
 * full stop ({@link DriveTestLog#csvRow}); the chat hint after an export says how to open that in
 * Excel on a comma-decimal locale without it being misread. For the same reason the chat link opens
 * the folder, not the file ({@link #fileLink}).
 *
 * <p>The log is kept for one session only ({@link ClientDriveTest}): export before leaving a world.
 */
@EventBusSubscriber(modid = RanCraft.MOD_ID, value = Dist.CLIENT)
public final class DriveTestCommands {

    private DriveTestCommands() {
    }

    static final String ROOT = "rancraftc";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    /** Give up finding a free file name after this many same-second exports. */
    private static final int MAX_NAME_ATTEMPTS = 100;

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(ROOT)
                .then(Commands.literal("drivetest")
                        .then(Commands.literal("export").executes(context -> export(context.getSource())))
                        .then(Commands.literal("clear").executes(context -> clear(context.getSource())))));
    }

    private static int export(CommandSourceStack source) {
        if (ClientDriveTest.size() == 0) {
            source.sendFailure(Component.translatable("commands.rancraftc.drivetest.empty"));
            return 0;
        }

        Path folder = Minecraft.getInstance().gameDirectory.toPath().resolve("rancraft").resolve("drivetests");
        String stamp = LocalDateTime.now().format(STAMP);
        int written = 0;
        try {
            Files.createDirectories(folder);
            for (Map.Entry<ResourceKey<Level>, DriveTestLog> dimension : ClientDriveTest.logs().entrySet()) {
                DriveTestLog log = dimension.getValue();
                if (log.isEmpty()) {
                    continue;
                }
                String dimensionId = dimension.getKey().location().toString();
                Path file = freshFile(folder, stamp, dimensionId);
                Files.writeString(file, log.toCsv(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                int rows = log.size();
                written += rows;
                source.sendSuccess(() -> Component.translatable(
                        "commands.rancraftc.drivetest.exported", rows, dimensionId, fileLink(file)), false);
            }
        } catch (IOException exception) {
            RanCraft.LOGGER.warn("RANCraft could not write the drive-test log under {}", folder, exception);
            source.sendFailure(Component.translatable(
                    "commands.rancraftc.drivetest.failed", String.valueOf(exception.getMessage())));
            return written;
        }

        source.sendSuccess(() -> Component.translatable("commands.rancraftc.drivetest.excel_hint")
                .withStyle(ChatFormatting.GRAY), false);
        return written;
    }

    private static int clear(CommandSourceStack source) {
        int dropped = ClientDriveTest.clear();
        source.sendSuccess(() -> Component.translatable("commands.rancraftc.drivetest.cleared", dropped), false);
        return dropped;
    }

    /**
     * The file name, underlined; clicking it opens the <em>folder</em> that holds the file, as vanilla
     * does for profiler results ({@code Minecraft.debugClientMetricsStart}), not the file itself.
     * Opening the CSV directly hands it to the default app, which on Windows is usually Excel, and
     * Excel on a comma-decimal locale (such as {@code es-PE}) misreads a double-clicked CSV: it splits
     * on {@code ;} and takes {@code .} for a thousands separator, so {@code -82.4} can become
     * {@code -824}. The grey hint printed after the export says how to import it instead. The path
     * is absolute and normalised, so a relative game directory still opens the right folder.
     * Package-private for the tests.
     */
    static Component fileLink(Path file) {
        String folder = file.toAbsolutePath().normalize().getParent().toString();
        return Component.literal(file.getFileName().toString())
                .withStyle(ChatFormatting.UNDERLINE)
                .withStyle(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, folder)));
    }

    /** The first name not already taken; two exports in one second get {@code -1}, {@code -2}, ... */
    private static Path freshFile(Path folder, String stamp, String dimensionId) throws IOException {
        for (int attempt = 0; attempt < MAX_NAME_ATTEMPTS; attempt++) {
            Path candidate = folder.resolve(fileName(stamp, dimensionId, attempt));
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IOException("no free file name for " + fileName(stamp, dimensionId, 0));
    }

    /**
     * {@code drivetest-<stamp>-<dimension>.csv}, with {@code -<attempt>} before the extension after
     * the first try. Pure, for the tests.
     */
    static String fileName(String stamp, String dimensionId, int attempt) {
        String base = "drivetest-" + stamp + "-" + fileSafe(dimensionId);
        return (attempt == 0 ? base : base + "-" + attempt) + ".csv";
    }

    /**
     * A dimension id made safe as part of a file name on every OS: {@code minecraft:the_nether}
     * becomes {@code minecraft_the_nether}. Anything outside {@code [a-z0-9_.-]} becomes an
     * underscore, which also covers the {@code /} a datapack dimension path may contain.
     */
    static String fileSafe(String dimensionId) {
        String lower = dimensionId.toLowerCase(Locale.ROOT);
        StringBuilder safe = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
            safe.append(allowed ? c : '_');
        }
        return safe.toString();
    }
}
