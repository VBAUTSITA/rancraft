package dev.rancraft.gametest;

import com.mojang.authlib.GameProfile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.jetbrains.annotations.Nullable;

/**
 * A real {@link ServerPlayer} for game tests that open menus (Phase 3 slice 13): built directly, never
 * on the server's player list (so the real {@code SignalTicker} never evaluates it, and no mod payload
 * is ever sent to it), with a connection that drops every packet, and keeping every message it is
 * told ({@link #messages}) and every custom payload sent to it ({@link #payloads}; Phase 3 slice 14, so a
 * test sees what a device sends straight to its player, as the Proximity Scanner does).
 *
 * <p><b>Why not NeoForge's {@code FakePlayer}:</b> it overrides {@code openMenu} to open nothing and
 * {@code tick} to do nothing (NeoForge 21.1.251 sources), and both are what a Storage Terminal test
 * must run: vanilla's {@code ServerPlayer.openMenu} and the {@code stillValid} check in
 * {@code ServerPlayer.tick} that closes a menu. This class keeps both as vanilla wrote them and
 * replaces only the network: the connection is a {@link ServerGamePacketListenerImpl} whose
 * {@code send} does nothing (as {@code FakePlayer}'s does), over a {@link Connection} that is never
 * opened. It is not added to the level either, so it holds no chunk ticket; the test positions it.
 *
 * <p>Like any {@code ServerPlayer} built by hand (and like {@code FakePlayer}), its constructor registers
 * its advancements and stats with the player list under its own random id; nothing saves them.
 */
final class SilentServerPlayer extends ServerPlayer {

    /** Every message the game tried to show this player (chat and action bar), oldest first. */
    final List<Component> messages = new ArrayList<>();

    /**
     * Every custom payload sent to this player (a {@link ClientboundCustomPayloadPacket}, as NeoForge's
     * {@code PacketDistributor.sendToPlayer} sends a single one), oldest first. Dropped like every packet,
     * but kept here first. Slice 14.
     */
    final List<CustomPacketPayload> payloads = new ArrayList<>();

    SilentServerPlayer(ServerLevel level, String name) {
        super(level.getServer(), level,
                new GameProfile(UUID.nameUUIDFromBytes((name + "/" + System.nanoTime()).getBytes(StandardCharsets.UTF_8)),
                        name),
                ClientInformation.createDefault());
        this.connection = new SilentConnection(level.getServer(), this, payloads);
    }

    @Override
    public void displayClientMessage(Component message, boolean actionBar) {
        messages.add(message);
    }

    /** The last message whose translation key is {@code key}, or {@code null}. */
    @Nullable
    Component last(String key) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getContents() instanceof TranslatableContents contents && contents.getKey().equals(key)) {
                return messages.get(i);
            }
        }
        return null;
    }

    /** The key of a translatable message's first argument, when that argument is itself translatable. */
    @Nullable
    static String innerKey(@Nullable Component message) {
        if (message != null && message.getContents() instanceof TranslatableContents contents
                && contents.getArgs().length > 0 && contents.getArgs()[0] instanceof Component inner
                && inner.getContents() instanceof TranslatableContents innerContents) {
            return innerContents.getKey();
        }
        return null;
    }

    /** A game connection that sends nothing, keeping each custom payload in {@code sink}. */
    private static final class SilentConnection extends ServerGamePacketListenerImpl {

        private final List<CustomPacketPayload> sink;

        SilentConnection(MinecraftServer server, ServerPlayer player, List<CustomPacketPayload> sink) {
            super(server, new Connection(PacketFlow.SERVERBOUND) {
                @Override
                public void setListenerForServerboundHandshake(PacketListener listener) {
                }
            }, player, CommonListenerCookie.createInitial(player.getGameProfile(), false));
            this.sink = sink;
        }

        @Override
        public void send(Packet<?> packet) {
            keep(packet);
        }

        @Override
        public void send(Packet<?> packet, @Nullable PacketSendListener listener) {
            keep(packet);
        }

        /** {@code sink} is null only if the superclass constructor sends something, before it is set. */
        private void keep(Packet<?> packet) {
            if (sink != null && packet instanceof ClientboundCustomPayloadPacket custom) {
                sink.add(custom.payload());
            }
        }
    }
}
