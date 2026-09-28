package dev.rancraft.registry;

import dev.rancraft.RanCraft;
import dev.rancraft.device.EmergencyRecord;
import java.util.function.Supplier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * NeoForge data attachments: state kept on a game object (here, the player) rather than on an item
 * stack or in server memory. Phase 3 slice 5 adds the first one.
 *
 * <p>Checked against the NeoForge 21.1.251 sources: {@code AttachmentType.builder(Supplier)},
 * {@code serialize(Codec, Predicate)} (the predicate skips writing, so an empty record costs the
 * player file nothing), {@code copyOnDeath()} (needs a serializer); attachment types are registered
 * to {@code NeoForgeRegistries.Keys.ATTACHMENT_TYPES}. On respawn {@code ServerPlayer.restoreFrom}
 * fires {@code PlayerEvent.Clone} with {@code wasDeath = true}, and NeoForge's own subscriber
 * ({@code AttachmentInternals.onPlayerClone}) then copies exactly the {@code copyOnDeath} attachments
 * to the new player entity. Returning from the End copies every serializable attachment.
 */
public final class ModAttachments {

    private ModAttachments() {
    }

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, RanCraft.MOD_ID);

    /**
     * The Network Locator's emergency record ({@link EmergencyRecord}): the last FIX, and the
     * "last fix before death" frozen from it. Survives death ({@code copyOnDeath}), because the
     * frozen record is exactly what the player needs after respawning. Saved with the player; an
     * empty record is not written.
     */
    public static final Supplier<AttachmentType<EmergencyRecord>> LOCATOR_EMERGENCY = ATTACHMENT_TYPES.register(
            "locator_emergency",
            () -> AttachmentType.builder(() -> EmergencyRecord.EMPTY)
                    .serialize(EmergencyRecord.CODEC, record -> !record.isEmpty())
                    .copyOnDeath()
                    .build());

    public static void register(IEventBus modBus) {
        ATTACHMENT_TYPES.register(modBus);
    }
}
