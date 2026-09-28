package dev.rancraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.rancraft.RanCraft;
import dev.rancraft.net.LocatorFixPayload;
import dev.rancraft.rf.LocatorFix;
import dev.rancraft.rf.LocatorSolver;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * Draws what the Network Locator worked out, in the world, while a Locator is held (Phase 3 slice 5,
 * §3A.6):
 * <ul>
 *   <li>a horizontal <b>ring</b> round every cell that went into the answer, at the range the server
 *       measured to it, in the band's colour ({@link BandColours});</li>
 *   <li>for a FIX, a <b>marker</b> at the estimate and an <b>error circle</b> of radius "±";</li>
 *   <li>for AMBIGUOUS, <b>two markers</b>, the likely one brighter (alike when there is no
 *       preference).</li>
 * </ul>
 *
 * <p><b>From the payload only.</b> Every centre, range, estimate, candidate, "±" and likely index
 * is a server value ({@link LocatorFixPayload}). This class draws circles and crosses at them; it
 * ranges nothing, solves nothing and picks no candidate. The one piece of geometry done here is
 * where to <em>slice</em> each range: a measured range is a slant distance, a sphere round the
 * cell, and a ring is that sphere's cross-section at one height ({@link LocatorStyle#sliceRadius}).
 * With a FIX it is sliced at the fix's own assumed eye height, so the rings visibly cross at the
 * marker, which is the picture of what the solver did. Without one it is sliced at the viewer's eye
 * height, so the rings pass through (or near) the player, off by the quantisation and any NLOS bias
 * the server put into the range. That is the lesson the rings are for: where they fail to meet at
 * your feet, the ranges were wrong.
 *
 * <p><b>Drawn on the ground, 1.62 below the slice.</b> Every horizontal shape (rings, the error
 * circle, the crosses) is lowered from the slice height to the ground under it (the height the
 * solver assumed you stand on, or your own feet without a FIX). Its radius is unchanged: it is still
 * the range at eye height. Drawn at eye height they would all lie in the plane of the camera and, in
 * first person, collapse onto the horizon as one flat line. On the ground they read as circles round
 * your feet. On a slope a ring dips into the hillside; the faint pass keeps it traceable there.
 *
 * <p>The AMBIGUOUS markers stand on the viewer's feet height: the payload carries each candidate's
 * (x, z) but not the height the solver assumed there. The markers are vertical strokes, so the
 * height reads as "somewhere on this column" rather than a claim.
 *
 * <p>Each line is drawn twice, as the lens's link rays are: faintly with no depth test, so a ring
 * running behind a hill can still be followed, then solid with the depth test.
 */
@EventBusSubscriber(modid = RanCraft.MOD_ID, value = Dist.CLIENT)
public final class LocatorRenderer {

    private LocatorRenderer() {
    }

    /** Ring opacity in plain view and behind terrain. */
    private static final int RING_ALPHA = 220;
    private static final int GHOST_ALPHA_SCALE = 60;

    /** Marker: a cross on the assumed ground, and a vertical stroke from it up past the eye. */
    private static final double MARKER_ABOVE_EYE = 0.6;
    private static final double CROSS_HALF = 0.5;

    /** Shapes on the ground float this far above it, so they do not z-fight with the top face. */
    private static final double GROUND_LIFT = 0.05;

    /** A marker's vertical stroke this close (horizontally) to the camera would run through it. */
    private static final double NEAR_CLIP_BLOCKS = 0.75;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null || LocatorHudOverlay.heldLocator(player) == null) {
            return;
        }
        LocatorFixPayload payload = ClientLocatorState.latest();
        if (payload == null || ClientLocatorState.isStale()) {
            return;
        }
        if (payload.rings().isEmpty() && !(payload.fix() instanceof LocatorFix.Fix)
                && !(payload.fix() instanceof LocatorFix.Ambiguous)) {
            return;
        }

        Camera camera = event.getCamera();
        Vec3 eye = camera.getPosition();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        double sliceY = LocatorStyle.sliceHeight(payload.fix(), player.getEyePosition(partialTick).y);

        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        poseStack.pushPose();
        Matrix4f matrix = poseStack.last().pose();

        // The two passes share the buffer source's one shared buffer, so each is finished before the
        // next asks for a buffer (as in LinkRenderer).
        VertexConsumer ghost = buffers.getBuffer(LinkRenderer.SeeThroughLines.TYPE);
        drawAll(ghost, matrix, eye, payload, sliceY, GHOST_ALPHA_SCALE);
        buffers.endBatch(LinkRenderer.SeeThroughLines.TYPE);

        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        drawAll(lines, matrix, eye, payload, sliceY, 255);
        buffers.endBatch(RenderType.lines());

        poseStack.popPose();
    }

    /**
     * Rings, then the answer's markers, every alpha scaled by {@code alphaScale / 255}. Ring radii
     * are taken at {@code sliceY}; the shapes are drawn on the ground under it (class comment).
     */
    private static void drawAll(VertexConsumer consumer, Matrix4f matrix, Vec3 eye,
                                LocatorFixPayload payload, double sliceY, int alphaScale) {
        double groundY = sliceY - LocatorSolver.RECEIVER_EYE_HEIGHT + GROUND_LIFT;
        for (LocatorFixPayload.Ring ring : payload.rings()) {
            double radius = LocatorStyle.sliceRadius(ring.radius(), ring.cy(), sliceY);
            circle(consumer, matrix, eye, ring.cx(), groundY, ring.cz(), radius,
                    BandColours.of(ring.bandId()), scale(RING_ALPHA, alphaScale));
        }

        switch (payload.fix()) {
            case LocatorFix.Fix fix -> {
                int rgb = LocatorStyle.FIX_ARGB & 0xFFFFFF;
                int alpha = scale(LocatorStyle.BRIGHT_ALPHA, alphaScale);
                marker(consumer, matrix, eye, fix.x(), sliceY, fix.z(), rgb, alpha);
                circle(consumer, matrix, eye, fix.x(), groundY, fix.z(), fix.errorBlocks(), rgb, alpha);
            }
            case LocatorFix.Ambiguous two -> {
                int rgb = LocatorStyle.AMBIGUOUS_ARGB & 0xFFFFFF;
                marker(consumer, matrix, eye, two.ax(), sliceY, two.az(), rgb,
                        scale(LocatorStyle.candidateAlpha(two.likely(), 0), alphaScale));
                marker(consumer, matrix, eye, two.bx(), sliceY, two.bz(), rgb,
                        scale(LocatorStyle.candidateAlpha(two.likely(), 1), alphaScale));
            }
            default -> {
                // RANGE ONLY and POOR GEOMETRY have no position to mark: the rings are the answer.
            }
        }
    }

    /**
     * A horizontal circle at height {@code y}. Coordinates are made camera-relative in double
     * precision before they become floats, so a ring hundreds of blocks across stays steady far from
     * the world origin.
     */
    private static void circle(VertexConsumer consumer, Matrix4f matrix, Vec3 eye,
                               double cx, double y, double cz, double radius, int rgb, int alpha) {
        int segments = LocatorStyle.segments(radius);
        if (segments == 0) {
            return;
        }
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        float ry = (float) (y - eye.y);
        double step = 2.0 * Math.PI / segments;
        double x0 = cx + radius;
        double z0 = cz;
        for (int i = 1; i <= segments; i++) {
            double angle = step * i;
            double x1 = cx + radius * Math.cos(angle);
            double z1 = cz + radius * Math.sin(angle);
            segment(consumer, matrix, x0 - eye.x, ry, z0 - eye.z, x1 - eye.x, ry, z1 - eye.z, red, green, blue, alpha);
            x0 = x1;
            z0 = z1;
        }
    }

    /**
     * A position marker for an estimate whose eye height is {@code eyeY}: a cross on the ground the
     * solver assumed ({@code eyeY - 1.62}) and a vertical stroke from there to a little above the
     * eye. The stroke is left out when the camera stands in it (first person on your own estimate),
     * where it would be a line through the eye.
     */
    private static void marker(VertexConsumer consumer, Matrix4f matrix, Vec3 eye,
                               double x, double eyeY, double z, int rgb, int alpha) {
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        double dx = x - eye.x;
        double dz = z - eye.z;
        double ground = eyeY - LocatorSolver.RECEIVER_EYE_HEIGHT + GROUND_LIFT - eye.y;
        segment(consumer, matrix, dx - CROSS_HALF, ground, dz, dx + CROSS_HALF, ground, dz, red, green, blue, alpha);
        segment(consumer, matrix, dx, ground, dz - CROSS_HALF, dx, ground, dz + CROSS_HALF, red, green, blue, alpha);
        if (dx * dx + dz * dz >= NEAR_CLIP_BLOCKS * NEAR_CLIP_BLOCKS) {
            segment(consumer, matrix, dx, ground, dz, dx, eyeY + MARKER_ABOVE_EYE - eye.y, dz,
                    red, green, blue, alpha);
        }
    }

    /** One line, camera-relative. {@link RenderType#lines()} wants the line's direction as its normal. */
    private static void segment(VertexConsumer consumer, Matrix4f matrix,
                                double x0, double y0, double z0, double x1, double y1, double z1,
                                int red, int green, int blue, int alpha) {
        double lx = x1 - x0;
        double ly = y1 - y0;
        double lz = z1 - z0;
        double length = Math.sqrt(lx * lx + ly * ly + lz * lz);
        if (length < 1.0e-6) {
            return;
        }
        float nx = (float) (lx / length);
        float ny = (float) (ly / length);
        float nz = (float) (lz / length);
        consumer.addVertex(matrix, (float) x0, (float) y0, (float) z0)
                .setColor(red, green, blue, alpha)
                .setNormal(nx, ny, nz);
        consumer.addVertex(matrix, (float) x1, (float) y1, (float) z1)
                .setColor(red, green, blue, alpha)
                .setNormal(nx, ny, nz);
    }

    private static int scale(int alpha, int alphaScale) {
        return Math.max(0, Math.min(255, alpha * alphaScale / 255));
    }
}
