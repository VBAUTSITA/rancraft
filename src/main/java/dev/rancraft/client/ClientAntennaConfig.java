package dev.rancraft.client;

import dev.rancraft.net.OpenAntennaConfigPayload;
import net.minecraft.client.Minecraft;

/**
 * Client-only entry point for {@link OpenAntennaConfigPayload}.
 *
 * <p>Exists so that {@code ModPayloads} -- a common class -- never has to name a {@code Screen}.
 * A dedicated server never receives an S2C payload, so this class is never resolved there.
 */
public final class ClientAntennaConfig {

    private ClientAntennaConfig() {
    }

    public static void open(OpenAntennaConfigPayload payload) {
        Minecraft.getInstance().setScreen(new AntennaConfigScreen(payload));
    }
}
