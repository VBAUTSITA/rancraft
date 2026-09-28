package dev.rancraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.rancraft.RanCraft;
import dev.rancraft.RanCraftConfig;
import dev.rancraft.item.RfLensItem;
import dev.rancraft.rf.DriveTestLog;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Draws the RF Lens drive-test trail (RF Vision Step 3a): one marker per logged sample, at the point
 * the server measured it, coloured by the service level the server reported there; a tall white
 * pillar where a handover fired and a tall yellow one where the serving cell changed without one.
 *
 * <p><b>Every value drawn is server-supplied.</b> Positions, levels and handover counts all come
 * from {@link ClientDriveTest}, which copies {@code SignalSamplePayload} field for field. This class
 * computes no RSRP, SINR, obstruction or serving cell; it picks a colour for a level the server
 * named ({@link LensStyle#levelRgb}) and a pillar for an event read off the server's own counter.
 *
 * <p><b>The trail is a record, not a map.</b> It shows what was measured where the wearer walked,
 * nothing in between. The line joining consecutive markers is drawn in a faint neutral white for
 * that reason: colouring it would claim a measurement along a stretch where none was taken.
 * Samples are 1 Hz by default, so a sprinting player leaves gaps, as a real scanner does at its own
 * sample rate.
 *
 * <p>Markers hang at eye height, because that is where the server evaluates the receiver. In first
 * person the newest marker sits in the camera while you stand still, so anything within
 * {@value #NEAR_CLIP_BLOCKS} blocks of the camera is not drawn.
 *
 * <p>Only the current dimension's log is drawn. The lens band filter does not apply: the trail
 * records what the receiver got, and the serving band is part of that result.
 */
@EventBusSubscriber(modid = RanCraft.MOD_ID, value = Dist.CLIENT)
public final class TrailRenderer {

    private TrailRenderer() {
    }

    /** Half the side of a marker, in blocks. Big enough to read at 50 blocks, small enough to walk through. */
    private static final double MARKER_HALF_SIZE = 0.12;
    private static final int MARKER_ALPHA = 255;

    /** Event pillars: from about the feet (the marker is at the eye) to above the head. */
    private static final double PILLAR_BELOW = 1.6;
    private static final double PILLAR_ABOVE = 1.9;
    private static final double PILLAR_HALF_WIDTH = 0.08;
    private static final int PILLAR_ALPHA = 200;

    /** The joining line: neutral and faint, because nothing was measured along it. */
    private static final int JOIN_RGB = 0xFFFFFF;
    private static final int JOIN_ALPHA = 70;

    /**
     * Longest gap joined by a line, in blocks. A 1 Hz sprint step is about 5.6 blocks and elytra
     * flight can cover 20 or more; a longer gap is a teleport, respawn or relog (see
     * {@link LensStyle#joins}).
     */
    private static final double JOIN_MAX_BLOCKS = 24.0;

    /** Anything this close to the camera is not drawn (see the class comment). */
    private static final double NEAR_CLIP_BLOCKS = 0.75;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            return;
        }
        ItemStack lens = RfLensItem.wornBy(player);
        if (lens == null || !RfLensItem.settingsOf(lens).showTrail()) {
            return;
        }
        DriveTestLog log = ClientDriveTest.logFor(level.dimension());
        if (log == null || log.isEmpty()) {
            return;
        }

        Camera camera = event.getCamera();
        Vec3 eye = camera.getPosition();
        double range = RanCraftConfig.trailRenderDistance();

        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        poseStack.pushPose();
        Matrix4f matrix = poseStack.last().pose();

        // Lines and quads each finish before the next asks for a buffer, as in LinkRenderer.
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        drawJoins(lines, matrix, eye, log, range);
        buffers.endBatch(RenderType.lines());

        VertexConsumer quads = buffers.getBuffer(RenderType.debugQuads());
        drawMarkers(quads, matrix, eye, camera.getLeftVector(), camera.getUpVector(), log, range);
        buffers.endBatch(RenderType.debugQuads());

        poseStack.popPose();
    }

    /**
     * A faint line between each pair of consecutive samples close enough to be one walk, when
     * either end is in range. Coordinates are made camera-relative in double precision before they
     * become floats, so the trail stays steady far from the world origin.
     */
    private static void drawJoins(VertexConsumer consumer, Matrix4f matrix, Vec3 eye, DriveTestLog log, double range) {
        int red = (JOIN_RGB >> 16) & 0xFF;
        int green = (JOIN_RGB >> 8) & 0xFF;
        int blue = JOIN_RGB & 0xFF;
        double rangeSq = range * range;
        double nearSq = NEAR_CLIP_BLOCKS * NEAR_CLIP_BLOCKS;
        DriveTestLog.Sample[] previous = {null};

        log.forEach(entry -> {
            DriveTestLog.Sample from = previous[0];
            DriveTestLog.Sample to = entry.sample();
            previous[0] = to;
            if (!LensStyle.joins(from, to, JOIN_MAX_BLOCKS)) {
                return;
            }
            double fromSq = distanceSq(from, eye);
            double toSq = distanceSq(to, eye);
            if ((fromSq > rangeSq && toSq > rangeSq) || fromSq < nearSq || toSq < nearSq) {
                return;
            }
            double dx = to.x() - from.x();
            double dy = to.y() - from.y();
            double dz = to.z() - from.z();
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (length < 1.0e-4) {
                return;
            }
            // RenderType.lines() wants a normal per vertex; the line direction is the honest one.
            float nx = (float) (dx / length);
            float ny = (float) (dy / length);
            float nz = (float) (dz / length);
            consumer.addVertex(matrix,
                            (float) (from.x() - eye.x), (float) (from.y() - eye.y), (float) (from.z() - eye.z))
                    .setColor(red, green, blue, JOIN_ALPHA)
                    .setNormal(nx, ny, nz);
            consumer.addVertex(matrix,
                            (float) (to.x() - eye.x), (float) (to.y() - eye.y), (float) (to.z() - eye.z))
                    .setColor(red, green, blue, JOIN_ALPHA)
                    .setNormal(nx, ny, nz);
        });
    }

    /**
     * One camera-facing square per sample in its level colour, plus a camera-facing vertical strip
     * for a handover or reselection.
     */
    private static void drawMarkers(
            VertexConsumer consumer, Matrix4f matrix, Vec3 eye, Vector3f left, Vector3f up,
            DriveTestLog log, double range) {

        double rangeSq = range * range;
        double nearSq = NEAR_CLIP_BLOCKS * NEAR_CLIP_BLOCKS;
        float lx = (float) (left.x() * MARKER_HALF_SIZE);
        float ly = (float) (left.y() * MARKER_HALF_SIZE);
        float lz = (float) (left.z() * MARKER_HALF_SIZE);
        float ux = (float) (up.x() * MARKER_HALF_SIZE);
        float uy = (float) (up.y() * MARKER_HALF_SIZE);
        float uz = (float) (up.z() * MARKER_HALF_SIZE);

        log.forEach(entry -> {
            DriveTestLog.Sample sample = entry.sample();
            double distanceSq = distanceSq(sample, eye);
            if (distanceSq > rangeSq || distanceSq < nearSq) {
                return;
            }
            float cx = (float) (sample.x() - eye.x);
            float cy = (float) (sample.y() - eye.y);
            float cz = (float) (sample.z() - eye.z);

            int rgb = LensStyle.levelRgb(sample.level());
            int red = (rgb >> 16) & 0xFF;
            int green = (rgb >> 8) & 0xFF;
            int blue = rgb & 0xFF;
            consumer.addVertex(matrix, cx - lx - ux, cy - ly - uy, cz - lz - uz).setColor(red, green, blue, MARKER_ALPHA);
            consumer.addVertex(matrix, cx - lx + ux, cy - ly + uy, cz - lz + uz).setColor(red, green, blue, MARKER_ALPHA);
            consumer.addVertex(matrix, cx + lx + ux, cy + ly + uy, cz + lz + uz).setColor(red, green, blue, MARKER_ALPHA);
            consumer.addVertex(matrix, cx + lx - ux, cy + ly - uy, cz + lz - uz).setColor(red, green, blue, MARKER_ALPHA);

            int pillar = LensStyle.pillarRgb(entry.event());
            if (pillar != LensStyle.NO_PILLAR) {
                drawPillar(consumer, matrix, eye, sample, pillar);
            }
        });
    }

    /**
     * A vertical strip turned to face the camera about the vertical axis, so it reads as a pillar
     * from any side. Skipped when the camera stands inside it.
     */
    private static void drawPillar(VertexConsumer consumer, Matrix4f matrix, Vec3 eye, DriveTestLog.Sample sample, int rgb) {
        double dx = sample.x() - eye.x;
        double dz = sample.z() - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < NEAR_CLIP_BLOCKS) {
            return;
        }
        // Perpendicular to the camera-to-pillar direction in the horizontal plane.
        float px = (float) (-dz / horizontal * PILLAR_HALF_WIDTH);
        float pz = (float) (dx / horizontal * PILLAR_HALF_WIDTH);
        float cx = (float) dx;
        float cz = (float) dz;
        float bottom = (float) (sample.y() - PILLAR_BELOW - eye.y);
        float top = (float) (sample.y() + PILLAR_ABOVE - eye.y);

        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        consumer.addVertex(matrix, cx - px, bottom, cz - pz).setColor(red, green, blue, PILLAR_ALPHA);
        consumer.addVertex(matrix, cx + px, bottom, cz + pz).setColor(red, green, blue, PILLAR_ALPHA);
        consumer.addVertex(matrix, cx + px, top, cz + pz).setColor(red, green, blue, PILLAR_ALPHA);
        consumer.addVertex(matrix, cx - px, top, cz - pz).setColor(red, green, blue, PILLAR_ALPHA);
    }

    private static double distanceSq(DriveTestLog.Sample sample, Vec3 eye) {
        double dx = sample.x() - eye.x;
        double dy = sample.y() - eye.y;
        double dz = sample.z() - eye.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
